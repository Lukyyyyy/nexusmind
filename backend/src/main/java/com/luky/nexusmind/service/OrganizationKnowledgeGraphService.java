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
        List<FileUpload> accessible = organizationFiles(documentService.getAccessibleFiles(userId, "", role));
        Map<String, String> names = organizationTagRepository.findAll().stream()
                .collect(Collectors.toMap(OrganizationTag::getTagId, OrganizationTag::getName, (left, right) -> left));

        return accessible.stream()
                .collect(Collectors.groupingBy(FileUpload::getOrgTag, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(entry -> new OrganizationOption(
                        entry.getKey(),
                        names.getOrDefault(entry.getKey(), entry.getKey()),
                        entry.getValue().size(),
                        entry.getValue().stream().filter(this::isPublished).count()
                ))
                .sorted(Comparator.comparing(OrganizationOption::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    public OrganizationGraphResponse getOrganizationGraph(String orgTag,
                                                           String userId,
                                                           String role,
                                                           String query,
                                                           String entityType,
                                                           Collection<Long> requestedFileIds,
                                                           Integer requestedLimit) {
        List<FileUpload> accessibleOrgFiles = organizationFiles(documentService.getAccessibleFiles(userId, "", role))
                .stream()
                .filter(file -> Objects.equals(file.getOrgTag(), orgTag))
                .toList();
        if (accessibleOrgFiles.isEmpty()) {
            throw new CustomException("无权访问该组织知识图谱", HttpStatus.FORBIDDEN);
        }

        List<FileUpload> publishedFiles = accessibleOrgFiles.stream().filter(this::isPublished).toList();
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
                orgTag,
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
        Map<String, String> orgNames = organizationTagRepository.findById(orgTag)
                .map(tag -> Map.of(tag.getTagId(), tag.getName()))
                .orElseGet(Map::of);
        List<DocumentOption> documents = publishedFiles.stream()
                .sorted(Comparator.comparing(FileUpload::getFileName, String.CASE_INSENSITIVE_ORDER))
                .map(file -> new DocumentOption(file.getId(), file.getFileMd5(), file.getFileName()))
                .toList();

        return new OrganizationGraphResponse(
                orgTag,
                orgNames.getOrDefault(orgTag, orgTag),
                graphNodes,
                edges,
                entityTypes,
                documents,
                new GraphStats(graphNodes.size(), edges.size(), contributingDocuments.size()),
                truncated,
                graphStoreService.isEnabled()
        );
    }

    private List<FileUpload> organizationFiles(List<FileUpload> files) {
        return files.stream()
                .filter(file -> !file.isPublic())
                .filter(file -> !DocumentPermissionPolicy.isPrivateOrgTag(file.getOrgTag()))
                .filter(file -> file.getOrgTag() != null && !file.getOrgTag().isBlank())
                .toList();
    }

    private boolean isPublished(FileUpload file) {
        return file.isGraphEnabled() && file.getGraphStatus() == KnowledgeGraphStatus.PUBLISHED;
    }

    public record OrganizationOption(String tagId, String name, int documentCount, long publishedDocumentCount) {}

    public record OrganizationGraphResponse(String orgTag, String orgName,
                                            List<GraphNode> nodes, List<GraphEdge> edges,
                                            List<String> entityTypes, List<DocumentOption> documents,
                                            GraphStats stats, boolean truncated, boolean neo4jEnabled) {}

    public record GraphNode(String id, String name, String type, int degree) {}

    public record GraphEdge(String id, String source, String target, String predicate,
                            Double confidence, Integer evidenceChunkId, String evidenceText,
                            Long fileUploadId, String fileMd5, String fileName) {}

    public record DocumentOption(Long id, String fileMd5, String fileName) {}

    public record GraphStats(int entityCount, int relationCount, int documentCount) {}

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
