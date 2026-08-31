package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.model.KnowledgeGraphStatus;
import com.luky.nexusmind.model.OrganizationTag;
import com.luky.nexusmind.repository.OrganizationTagRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class OrganizationKnowledgeGraphService {
    private static final int DEFAULT_RELATION_LIMIT = 300;
    private static final int MAX_RELATION_LIMIT = 1000;

    private final DocumentService documentService;
    private final OrganizationTagRepository organizationTagRepository;
    private final KnowledgeGraphStoreService graphStoreService;

    public OrganizationKnowledgeGraphService(DocumentService documentService,
                                             OrganizationTagRepository organizationTagRepository,
                                             KnowledgeGraphStoreService graphStoreService) {
        this.documentService = documentService;
        this.organizationTagRepository = organizationTagRepository;
        this.graphStoreService = graphStoreService;
    }

    @Transactional(readOnly = true)
    public List<OrganizationOption> listOrganizations(String userId, String role) {
        return scopeSelections(userId, role).stream().map(OrganizationScopeSelection::option).toList();
    }

    @Transactional(readOnly = true)
    public OrganizationGraphResponse getOrganizationGraph(String requestedScope,
                                                           String userId,
                                                           String role,
                                                           String query,
                                                           String entityType,
                                                           Collection<Long> requestedFileIds,
                                                           Integer requestedLimit) {
        List<OrganizationScopeSelection> selections = scopeSelections(userId, role);
        OrganizationScopeSelection selection = selections.stream()
                .filter(value -> value.scopeId().equals(requestedScope))
                .findFirst()
                // Keep the previous org-tag URL compatible: members get the internal graph,
                // other users get the public graph when one exists.
                .orElseGet(() -> selections.stream()
                        .filter(value -> value.tagId().equals(requestedScope))
                        .min(Comparator.comparingInt(value -> value.scopeType() == ScopeType.INTERNAL ? 0 : 1))
                        .orElse(null));
        if (selection == null) {
            throw new CustomException("无权访问该组织知识图谱", HttpStatus.FORBIDDEN);
        }

        List<FileUpload> publishedFiles = selection.files().stream().filter(this::isPublished).toList();
        Set<Long> requestedIds = requestedFileIds == null
                ? Set.of()
                : requestedFileIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        List<FileUpload> selectedFiles = requestedIds.isEmpty()
                ? publishedFiles
                : publishedFiles.stream().filter(file -> requestedIds.contains(file.getId())).toList();

        int limit = requestedLimit == null
                ? DEFAULT_RELATION_LIMIT
                : Math.min(Math.max(requestedLimit, 1), MAX_RELATION_LIMIT);
        List<KnowledgeGraphStoreService.OrganizationRelation> loaded = graphStoreService.loadOrganizationRelations(
                queryScopeIds(selection),
                selectedFiles.stream().map(FileUpload::getId).toList(),
                query,
                entityType,
                limit + 1
        );
        boolean truncated = loaded.size() > limit;
        List<KnowledgeGraphStoreService.OrganizationRelation> relations = truncated
                ? loaded.subList(0, limit)
                : loaded;

        Map<FactKey, MutableFact> facts = new LinkedHashMap<>();
        relations.forEach(relation -> facts.computeIfAbsent(FactKey.of(relation), ignored -> new MutableFact(relation))
                .addEvidence(relation));
        Map<StatementKey, Set<String>> statementTargets = new HashMap<>();
        facts.values().forEach(fact -> statementTargets
                .computeIfAbsent(fact.statementKey(), ignored -> new HashSet<>()).add(fact.targetKey));

        Map<String, MutableNode> nodes = new LinkedHashMap<>();
        List<GraphEdge> edges = facts.values().stream().map(fact -> {
            nodes.computeIfAbsent(fact.sourceKey, ignored -> new MutableNode(
                    fact.sourceKey, fact.sourceName, fact.sourceType)).incrementDegree();
            nodes.computeIfAbsent(fact.targetKey, ignored -> new MutableNode(
                    fact.targetKey, fact.targetName, fact.targetType)).incrementDegree();
            return fact.response(statementTargets.getOrDefault(fact.statementKey(), Set.of()).size() > 1);
        }).toList();

        List<GraphNode> graphNodes = nodes.values().stream().map(MutableNode::response).toList();
        List<String> entityTypes = graphNodes.stream().map(GraphNode::type).distinct().sorted().toList();
        Set<Long> contributingDocuments = edges.stream().flatMap(edge -> edge.evidences().stream())
                .map(GraphEvidence::fileUploadId).collect(Collectors.toSet());
        List<DocumentOption> documents = publishedFiles.stream()
                .sorted(Comparator.comparing(FileUpload::getFileName, String.CASE_INSENSITIVE_ORDER))
                .map(file -> new DocumentOption(file.getId(), file.getFileMd5(), file.getFileName()))
                .toList();

        return new OrganizationGraphResponse(
                selection.scopeId(),
                selection.tagId(),
                selection.name(),
                selection.scopeType(),
                graphNodes,
                edges,
                entityTypes,
                documents,
                new GraphStats(graphNodes.size(), edges.size(), contributingDocuments.size(),
                        (int) edges.stream().filter(GraphEdge::disputed).count()),
                truncated,
                graphStoreService.isEnabled()
        );
    }

    private List<OrganizationScopeSelection> scopeSelections(String userId, String role) {
        List<FileUpload> accessible = documentService.getAccessibleFiles(userId, "", role).stream()
                .filter(file -> file.getOrgTag() != null && !file.getOrgTag().isBlank())
                .toList();
        Set<String> memberships = isAdministrator(role)
                ? Set.of()
                : new HashSet<>(documentService.getEffectiveOrganizationTags(userId));
        Map<String, String> names = organizationTagRepository.findAll().stream()
                .collect(Collectors.toMap(OrganizationTag::getTagId, OrganizationTag::getName, (left, right) -> left));
        Map<String, MutableScope> scopes = new LinkedHashMap<>();

        for (FileUpload file : accessible) {
            String orgTag = file.getOrgTag();
            String orgName = names.getOrDefault(orgTag, orgTag);
            if (DocumentPermissionPolicy.isPrivateOrgTag(orgTag)) {
                addScope(scopes, KnowledgeGraphStoreService.privateScope(orgTag), orgTag,
                        orgName, ScopeType.PRIVATE, file);
                continue;
            }
            if (file.isPublic()) {
                addScope(scopes, KnowledgeGraphStoreService.publicOrganizationScope(orgTag), orgTag,
                        orgName, ScopeType.PUBLIC, file);
            }
            if (isAdministrator(role) || memberships.contains(orgTag)) {
                addScope(scopes, KnowledgeGraphStoreService.internalOrganizationScope(orgTag), orgTag,
                        orgName, ScopeType.INTERNAL, file);
            }
        }

        return scopes.values().stream()
                .map(MutableScope::selection)
                .sorted(Comparator.comparing(OrganizationScopeSelection::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(OrganizationScopeSelection::scopeType))
                .toList();
    }

    private boolean isAdministrator(String role) {
        return "ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
    }

    private void addScope(Map<String, MutableScope> scopes, String scopeId, String tagId,
                          String name, ScopeType scopeType, FileUpload file) {
        scopes.computeIfAbsent(scopeId, ignored -> new MutableScope(scopeId, tagId, name, scopeType)).files.add(file);
    }

    private List<String> queryScopeIds(OrganizationScopeSelection selection) {
        return switch (selection.scopeType()) {
            case PUBLIC -> List.of(selection.scopeId(), "PUBLIC");
            case INTERNAL -> List.of(selection.scopeId(), "ORG:" + selection.tagId(), "PUBLIC");
            case PRIVATE -> {
                List<String> scopeIds = new ArrayList<>();
                scopeIds.add(selection.scopeId());
                selection.files().stream().map(FileUpload::getUserId).filter(Objects::nonNull).distinct()
                        .map(ownerId -> "USER:" + ownerId).forEach(scopeIds::add);
                yield scopeIds;
            }
        };
    }

    private boolean isPublished(FileUpload file) {
        return file.isGraphEnabled() && file.getGraphStatus() == KnowledgeGraphStatus.PUBLISHED;
    }

    public enum ScopeType { PUBLIC, INTERNAL, PRIVATE }

    public record OrganizationOption(String scopeId, String tagId, String name, ScopeType scopeType,
                                     int documentCount, long publishedDocumentCount) {}

    public record OrganizationGraphResponse(String scopeId, String orgTag, String orgName, ScopeType scopeType,
                                            List<GraphNode> nodes, List<GraphEdge> edges,
                                            List<String> entityTypes, List<DocumentOption> documents,
                                            GraphStats stats, boolean truncated, boolean neo4jEnabled) {}

    public record GraphNode(String id, String name, String type, int degree) {}

    public record GraphEdge(String id, String source, String target, String predicate,
                            Double confidence, Integer evidenceChunkId, String evidenceText,
                            Long fileUploadId, String fileMd5, String fileName,
                            int supportCount, int documentCount, boolean disputed,
                            List<GraphEvidence> evidences) {}

    public record GraphEvidence(Long claimId, Long fileUploadId, String fileMd5, String fileName,
                                Integer chunkId, String evidenceText, Double confidence) {}

    public record DocumentOption(Long id, String fileMd5, String fileName) {}

    public record GraphStats(int entityCount, int relationCount, int documentCount, int disputedRelationCount) {}

    private record OrganizationScopeSelection(String scopeId, String tagId, String name, ScopeType scopeType,
                                              List<FileUpload> files) {
        private OrganizationOption option() {
            return new OrganizationOption(scopeId, tagId, name, scopeType, files.size(),
                    files.stream().filter(file -> file.isGraphEnabled()
                            && file.getGraphStatus() == KnowledgeGraphStatus.PUBLISHED).count());
        }
    }

    private static final class MutableScope {
        private final String scopeId;
        private final String tagId;
        private final String name;
        private final ScopeType scopeType;
        private final List<FileUpload> files = new ArrayList<>();

        private MutableScope(String scopeId, String tagId, String name, ScopeType scopeType) {
            this.scopeId = scopeId;
            this.tagId = tagId;
            this.name = name;
            this.scopeType = scopeType;
        }

        private OrganizationScopeSelection selection() {
            return new OrganizationScopeSelection(scopeId, tagId, name, scopeType, List.copyOf(files));
        }
    }

    private static final class MutableNode {
        private final String id;
        private final String name;
        private final String type;
        private int degree;

        private MutableNode(String id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }

        private void incrementDegree() {
            degree++;
        }

        private GraphNode response() {
            return new GraphNode(id, name, type, degree);
        }
    }

    private record FactKey(String sourceKey, String predicate, String targetKey) {
        private static FactKey of(KnowledgeGraphStoreService.OrganizationRelation relation) {
            return new FactKey(relation.sourceKey(), normalizePredicate(relation.predicate()), relation.targetKey());
        }
    }

    private record StatementKey(String sourceKey, String predicate) {}

    private static String normalizePredicate(String value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static final class MutableFact {
        private final String sourceKey;
        private final String sourceName;
        private final String sourceType;
        private final String targetKey;
        private final String targetName;
        private final String targetType;
        private final String predicate;
        private final List<GraphEvidence> evidences = new ArrayList<>();

        private MutableFact(KnowledgeGraphStoreService.OrganizationRelation relation) {
            sourceKey = relation.sourceKey();
            sourceName = relation.sourceName();
            sourceType = relation.sourceType();
            targetKey = relation.targetKey();
            targetName = relation.targetName();
            targetType = relation.targetType();
            predicate = relation.predicate();
        }

        private void addEvidence(KnowledgeGraphStoreService.OrganizationRelation relation) {
            evidences.add(new GraphEvidence(relation.candidateId(), relation.fileUploadId(), relation.fileMd5(),
                    relation.fileName(), relation.chunkId(), relation.evidence(), relation.confidence()));
        }

        private StatementKey statementKey() {
            return new StatementKey(sourceKey, normalizePredicate(predicate));
        }

        private GraphEdge response(boolean disputed) {
            GraphEvidence primary = evidences.stream().max(Comparator.comparingDouble(
                    value -> value.confidence() == null ? 0.0 : value.confidence())).orElseThrow();
            int documents = (int) evidences.stream().map(GraphEvidence::fileUploadId).distinct().count();
            return new GraphEdge("claim-" + primary.claimId(), sourceKey, targetKey, predicate,
                    primary.confidence(), primary.chunkId(), primary.evidenceText(), primary.fileUploadId(),
                    primary.fileMd5(), primary.fileName(), evidences.size(), documents, disputed,
                    List.copyOf(evidences));
        }
    }
}
