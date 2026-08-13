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

        Map<String, MutableNode> nodes = new LinkedHashMap<>();
        List<GraphEdge> edges = relations.stream().map(relation -> {
            nodes.computeIfAbsent(relation.sourceKey(), ignored -> new MutableNode(
                    relation.sourceKey(), relation.sourceName(), relation.sourceType())).incrementDegree();
            nodes.computeIfAbsent(relation.targetKey(), ignored -> new MutableNode(
                    relation.targetKey(), relation.targetName(), relation.targetType())).incrementDegree();
            return new GraphEdge(
                    "candidate-" + relation.candidateId(), relation.sourceKey(), relation.targetKey(),
                    relation.predicate(), relation.confidence(), relation.chunkId(), relation.evidence(),
                    relation.fileUploadId(), relation.fileMd5(), relation.fileName()
            );
        }).toList();

        List<GraphNode> graphNodes = nodes.values().stream().map(MutableNode::response).toList();
        List<String> entityTypes = graphNodes.stream().map(GraphNode::type).distinct().sorted().toList();
        Set<Long> contributingDocuments = edges.stream().map(GraphEdge::fileUploadId).collect(Collectors.toSet());
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
                new GraphStats(graphNodes.size(), edges.size(), contributingDocuments.size()),
                truncated,
                graphStoreService.isEnabled()
        );
    }

    private List<OrganizationScopeSelection> scopeSelections(String userId, String role) {
        List<FileUpload> accessible = documentService.getAccessibleFiles(userId, "", role).stream()
                .filter(file -> file.getOrgTag() != null && !file.getOrgTag().isBlank())
                .toList();
        Set<String> memberships = "ADMIN".equals(role)
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
            if ("ADMIN".equals(role) || memberships.contains(orgTag)) {
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
                            Long fileUploadId, String fileMd5, String fileName) {}

    public record DocumentOption(Long id, String fileMd5, String fileName) {}

    public record GraphStats(int entityCount, int relationCount, int documentCount) {}

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
}
