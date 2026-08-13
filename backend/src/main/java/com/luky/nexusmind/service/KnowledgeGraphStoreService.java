package com.luky.nexusmind.service;

import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.model.GraphCandidate;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;

@Service
public class KnowledgeGraphStoreService {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeGraphStoreService.class);
    private final ObjectProvider<Driver> driverProvider;

    public KnowledgeGraphStoreService(ObjectProvider<Driver> driverProvider) {
        this.driverProvider = driverProvider;
    }

    public boolean isEnabled() {
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) return false;
        try {
            driver.verifyConnectivity();
            return true;
        } catch (RuntimeException e) {
            logger.warn("Neo4j 服务不可用: {}", e.getMessage());
            return false;
        }
    }

    public void publish(FileUpload file, List<GraphCandidate> candidates) {
        Driver driver = requireDriver();
        List<String> scopeIds = scopeIds(file);
        try {
            driver.verifyConnectivity();
            try (Session session = driver.session()) {
                session.executeWrite(tx -> {
                    tx.run("MATCH (c:Claim {fileUploadId: $fileUploadId}) DETACH DELETE c",
                            Values.parameters("fileUploadId", file.getId())).consume();
                    // Clean up graphs published by versions before Claim nodes were introduced.
                    tx.run("MATCH ()-[r:RELATED {fileUploadId: $fileUploadId}]->() DELETE r",
                            Values.parameters("fileUploadId", file.getId())).consume();
                    for (int scopeIndex = 0; scopeIndex < scopeIds.size(); scopeIndex++) {
                        String scopeId = scopeIds.get(scopeIndex);
                        for (GraphCandidate candidate : candidates) {
                            String subjectMention = hasText(candidate.getSubjectMentionName())
                                    ? candidate.getSubjectMentionName() : candidate.getSubjectName();
                            String objectMention = hasText(candidate.getObjectMentionName())
                                    ? candidate.getObjectMentionName() : candidate.getObjectName();
                            Map<String, Object> params = new HashMap<>();
                            params.put("subjectKey", entityKey(scopeId, candidate.getSubjectType(), candidate.getSubjectName()));
                            params.put("subjectName", candidate.getSubjectName());
                            params.put("subjectNormalizedName", normalizeName(subjectMention));
                            params.put("subjectType", normalizeType(candidate.getSubjectType()));
                            params.put("objectKey", entityKey(scopeId, candidate.getObjectType(), candidate.getObjectName()));
                            params.put("objectName", candidate.getObjectName());
                            params.put("objectNormalizedName", normalizeName(objectMention));
                            params.put("objectType", normalizeType(candidate.getObjectType()));
                            params.put("scopeId", scopeId);
                            params.put("subjectMention", subjectMention);
                            params.put("objectMention", objectMention);
                            params.put("subjectAliasKey", aliasKey(scopeId, candidate.getSubjectType(),
                                    subjectMention, (String) params.get("subjectKey")));
                            params.put("objectAliasKey", aliasKey(scopeId, candidate.getObjectType(),
                                    objectMention, (String) params.get("objectKey")));
                            params.put("claimKey", scopeId + "|" + candidate.getId());
                            // Question answering reads one canonical copy per document. For public
                            // documents the internal copy is canonical so organization members can
                            // still traverse paths between public and organization-only documents.
                            params.put("primaryScope", scopeIndex == scopeIds.size() - 1);
                            params.put("candidateId", candidate.getId());
                            params.put("predicate", candidate.getPredicate());
                            params.put("fileUploadId", file.getId());
                            params.put("fileMd5", file.getFileMd5());
                            params.put("fileName", file.getFileName());
                            params.put("chunkId", candidate.getEvidenceChunkId());
                            params.put("evidence", candidate.getEvidenceText());
                            params.put("confidence", candidate.getConfidence() == null ? 0.0 : candidate.getConfidence());
                            params.put("valueScore", candidate.getValueScore() == null ? 0.0 : candidate.getValueScore());
                            tx.run("""
                                MERGE (s:Entity {key: $subjectKey})
                                ON CREATE SET s.name = $subjectName, s.type = $subjectType, s.scopeId = $scopeId,
                                              s.createdAt = datetime()
                                SET s.updatedAt = datetime()
                                MERGE (o:Entity {key: $objectKey})
                                ON CREATE SET o.name = $objectName, o.type = $objectType, o.scopeId = $scopeId,
                                              o.createdAt = datetime()
                                SET o.updatedAt = datetime()
                                MERGE (sa:EntityAlias {key: $subjectAliasKey})
                                SET sa.name = $subjectMention, sa.normalizedName = $subjectNormalizedName,
                                    sa.type = $subjectType, sa.scopeId = $scopeId
                                MERGE (sa)-[:REFERS_TO]->(s)
                                MERGE (oa:EntityAlias {key: $objectAliasKey})
                                SET oa.name = $objectMention, oa.normalizedName = $objectNormalizedName,
                                    oa.type = $objectType, oa.scopeId = $scopeId
                                MERGE (oa)-[:REFERS_TO]->(o)
                                MERGE (c:Claim {key: $claimKey})
                                SET c.candidateId = $candidateId,
                                    c.scopeId = $scopeId,
                                    c.predicate = $predicate,
                                    c.normalizedPredicate = toLower(trim($predicate)),
                                    c.fileUploadId = $fileUploadId,
                                    c.fileMd5 = $fileMd5,
                                    c.fileName = $fileName,
                                    c.primaryScope = $primaryScope,
                                    c.chunkId = $chunkId,
                                    c.evidence = $evidence,
                                    c.confidence = $confidence,
                                    c.valueScore = $valueScore,
                                    c.subjectMention = $subjectMention,
                                    c.objectMention = $objectMention,
                                    c.updatedAt = datetime()
                                MERGE (c)-[:SUBJECT]->(s)
                                MERGE (c)-[:OBJECT]->(o)
                                MERGE (c)-[:SUBJECT_MENTION]->(sa)
                                MERGE (c)-[:OBJECT_MENTION]->(oa)
                                """, params).consume();
                        }
                    }
                    tx.run("MATCH (a:EntityAlias) WHERE NOT (a)<-[:SUBJECT_MENTION]-(:Claim) " +
                            "AND NOT (a)<-[:OBJECT_MENTION]-(:Claim) DETACH DELETE a").consume();
                    tx.run("MATCH (n:Entity) WHERE NOT (n)--() DELETE n").consume();
                    return null;
                });
            }
        } catch (RuntimeException e) {
            logger.warn("发布文档图谱失败，fileUploadId={}: {}", file.getId(), e.getMessage());
            throw new IllegalStateException("Neo4j 服务不可用，请启动数据库后重试", e);
        }
    }

    public void removeDocument(Long fileUploadId) {
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null || fileUploadId == null) return;
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (c:Claim {fileUploadId: $fileUploadId}) DETACH DELETE c",
                        Values.parameters("fileUploadId", fileUploadId)).consume();
                tx.run("MATCH ()-[r:RELATED {fileUploadId: $fileUploadId}]->() DELETE r",
                        Values.parameters("fileUploadId", fileUploadId)).consume();
                tx.run("MATCH (a:EntityAlias) WHERE NOT (a)<-[:SUBJECT_MENTION]-(:Claim) " +
                        "AND NOT (a)<-[:OBJECT_MENTION]-(:Claim) DETACH DELETE a").consume();
                tx.run("MATCH (n:Entity) WHERE NOT (n)--() DELETE n").consume();
                return null;
            });
        } catch (RuntimeException e) {
            logger.warn("移除文档图谱失败，fileUploadId={}: {}", fileUploadId, e.getMessage());
        }
    }

    public List<GraphPath> search(String query, Collection<Long> accessibleFileIds, int limit) {
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null || accessibleFileIds == null || accessibleFileIds.isEmpty()) return List.of();
        List<Long> ids = accessibleFileIds.stream().filter(Objects::nonNull).distinct().toList();
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (start:Entity)<-[:SUBJECT]-(c:Claim)-[:OBJECT]->(end:Entity)
                    WHERE c.fileUploadId IN $fileIds AND coalesce(c.primaryScope, true) = true
                      AND size(start.name) >= 2 AND size(end.name) >= 2
                      AND (toLower(start.name) CONTAINS toLower($query)
                           OR toLower(end.name) CONTAINS toLower($query)
                           OR toLower($query) CONTAINS toLower(start.name)
                           OR toLower($query) CONTAINS toLower(end.name))
                    RETURN [{key:start.key, name:start.name, type:start.type},
                            {key:end.key, name:end.name, type:end.type}] AS nodes,
                           [{predicate:c.predicate, fileName:c.fileName,
                              fileMd5:c.fileMd5, chunkId:c.chunkId, evidence:c.evidence,
                              source:start.key, target:end.key}] AS relations
                    LIMIT $limit
                    """, Values.parameters("fileIds", ids, "query", query, "limit", limit))
                    .list(record -> new GraphPath(
                            record.get("nodes").asList(value -> value.asMap()),
                            record.get("relations").asList(value -> value.asMap()))));
        } catch (RuntimeException e) {
            logger.warn("图谱检索不可用，自动回退现有检索: {}", e.getMessage());
            return List.of();
        }
    }

    public List<OrganizationRelation> loadOrganizationRelations(Collection<String> scopeIds,
                                                                 Collection<Long> accessibleFileIds,
                                                                 String query,
                                                                 String entityType,
                                                                 int limit) {
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null || scopeIds == null || scopeIds.isEmpty()
                || accessibleFileIds == null || accessibleFileIds.isEmpty()) return List.of();
        List<Long> ids = accessibleFileIds.stream().filter(Objects::nonNull).distinct().toList();
        String normalizedQuery = Objects.toString(query, "").trim();
        String normalizedEntityType = Objects.toString(entityType, "").trim().toUpperCase(Locale.ROOT);
        int safeLimit = Math.min(Math.max(limit, 1), 1001);
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                    MATCH (s:Entity)<-[:SUBJECT]-(c:Claim)-[:OBJECT]->(o:Entity)
                    WHERE c.scopeId IN $scopeIds
                      AND c.fileUploadId IN $fileIds
                      AND ($query = '' OR toLower(s.name) CONTAINS toLower($query)
                           OR toLower(o.name) CONTAINS toLower($query)
                           OR toLower(c.predicate) CONTAINS toLower($query)
                           OR toLower(coalesce(c.evidence, '')) CONTAINS toLower($query))
                      AND ($entityType = '' OR s.type = $entityType OR o.type = $entityType)
                    RETURN s.key AS sourceKey, s.name AS sourceName, s.type AS sourceType,
                           o.key AS targetKey, o.name AS targetName, o.type AS targetType,
                           c.candidateId AS candidateId, c.predicate AS predicate,
                           c.fileUploadId AS fileUploadId, c.fileMd5 AS fileMd5,
                           c.fileName AS fileName, c.chunkId AS chunkId,
                           c.evidence AS evidence, c.confidence AS confidence
                    ORDER BY c.confidence DESC, c.candidateId ASC
                    LIMIT $limit
                    """, Values.parameters(
                            "scopeIds", scopeIds.stream().filter(Objects::nonNull).distinct().toList(),
                            "fileIds", ids,
                            "query", normalizedQuery,
                            "entityType", normalizedEntityType,
                            "limit", safeLimit
                    )).list(record -> new OrganizationRelation(
                            record.get("sourceKey").asString(),
                            record.get("sourceName").asString(),
                            record.get("sourceType").asString(),
                            record.get("targetKey").asString(),
                            record.get("targetName").asString(),
                            record.get("targetType").asString(),
                            record.get("candidateId").asLong(),
                            record.get("predicate").asString(),
                            record.get("fileUploadId").asLong(),
                            record.get("fileMd5").asString(""),
                            record.get("fileName").asString(""),
                            record.get("chunkId").isNull() ? null : record.get("chunkId").asInt(),
                            record.get("evidence").asString(""),
                            record.get("confidence").isNull() ? 0.0 : record.get("confidence").asDouble()
                    )));
        } catch (RuntimeException e) {
            logger.warn("组织图谱查询不可用，scopeIds={}: {}", scopeIds, e.getMessage());
            return List.of();
        }
    }

    public void ensureConstraints() {
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) return;
        try (Session session = driver.session()) {
            session.run("CREATE CONSTRAINT entity_key_unique IF NOT EXISTS FOR (e:Entity) REQUIRE e.key IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT entity_alias_key_unique IF NOT EXISTS FOR (a:EntityAlias) REQUIRE a.key IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT claim_key_unique IF NOT EXISTS FOR (c:Claim) REQUIRE c.key IS UNIQUE").consume();
            session.run("CREATE INDEX claim_scope IF NOT EXISTS FOR (c:Claim) ON (c.scopeId)").consume();
            session.run("CREATE INDEX claim_file IF NOT EXISTS FOR (c:Claim) ON (c.fileUploadId)").consume();
        }
    }

    private Driver requireDriver() {
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) throw new IllegalStateException("知识图谱未启用，请配置 Neo4j");
        return driver;
    }

    static List<String> scopeIds(FileUpload file) {
        if (DocumentPermissionPolicy.isPrivateOrgTag(file.getOrgTag())) {
            return List.of(privateScope(file.getOrgTag()));
        }
        String orgTag = Objects.toString(file.getOrgTag(), "default");
        if (file.isPublic()) {
            return List.of(publicOrganizationScope(orgTag), internalOrganizationScope(orgTag));
        }
        return List.of(internalOrganizationScope(orgTag));
    }

    static String publicOrganizationScope(String orgTag) {
        return "ORG_PUBLIC:" + Objects.toString(orgTag, "default");
    }

    static String internalOrganizationScope(String orgTag) {
        return "ORG_INTERNAL:" + Objects.toString(orgTag, "default");
    }

    static String privateScope(String privateOrgTag) {
        String value = Objects.toString(privateOrgTag, "");
        if (DocumentPermissionPolicy.isPrivateOrgTag(value)) {
            value = value.substring(DocumentPermissionPolicy.PRIVATE_TAG_PREFIX.length());
        }
        return "PRIVATE:" + value;
    }

    private String entityKey(String scopeId, String type, String name) {
        return scopeId + "|" + normalizeType(type) + "|" + normalizeName(name);
    }

    private String aliasKey(String scopeId, String type, String name, String entityKey) {
        // Include the resolved entity key so homonyms may remain attached to different entities.
        return scopeId + "|" + normalizeType(type) + "|" + normalizeName(name) + "|" + entityKey;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static String normalizeName(String value) {
        String normalized = Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
                .trim().toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[\\s·•._\\-—–]+", "");
    }

    static String normalizeType(String value) {
        return Objects.toString(value, "OTHER").trim().toUpperCase(Locale.ROOT);
    }

    public record GraphPath(List<Map<String, Object>> nodes, List<Map<String, Object>> relations) {}

    public record OrganizationRelation(String sourceKey, String sourceName, String sourceType,
                                       String targetKey, String targetName, String targetType,
                                       Long candidateId, String predicate, Long fileUploadId,
                                       String fileMd5, String fileName, Integer chunkId,
                                       String evidence, Double confidence) {}
}
