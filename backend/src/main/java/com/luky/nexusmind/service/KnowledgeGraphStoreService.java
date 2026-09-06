package com.luky.nexusmind.service;

import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.model.GraphCandidate;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Duration;
import java.util.*;

@Service
public class KnowledgeGraphStoreService {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeGraphStoreService.class);
    private static final int SEED_LIMIT = 5;
    private final ObjectProvider<Driver> driverProvider;
    private volatile boolean constraintsReady;

    @Value("${knowledge-graph.retrieval.default-max-hops:3}")
    private int defaultMaxHops = 3;
    @Value("${knowledge-graph.retrieval.hard-max-hops:4}")
    private int hardMaxHops = 4;
    @Value("${knowledge-graph.retrieval.candidate-path-limit:200}")
    private int candidatePathLimit = 200;
    @Value("${knowledge-graph.retrieval.timeout-seconds:5}")
    private int retrievalTimeoutSeconds = 5;
    @Value("${knowledge-graph.retrieval.backfill-batches-per-call:2}")
    private int backfillBatchesPerCall = 2;

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
                    tx.run("MATCH ()-[f:FACT {fileUploadId: $fileUploadId}]->() DELETE f",
                            Values.parameters("fileUploadId", file.getId())).consume();
                    tx.run("MATCH (c:Claim {fileUploadId: $fileUploadId}) DETACH DELETE c",
                            Values.parameters("fileUploadId", file.getId())).consume();
                    tx.run("MATCH (d:Document {fileUploadId: $fileUploadId}) DETACH DELETE d",
                            Values.parameters("fileUploadId", file.getId())).consume();
                    // Clean up graphs published by versions before Claim nodes were introduced.
                    tx.run("MATCH ()-[r:RELATED {fileUploadId: $fileUploadId}]->() DELETE r",
                            Values.parameters("fileUploadId", file.getId())).consume();
                    for (int scopeIndex = 0; scopeIndex < scopeIds.size(); scopeIndex++) {
                        String scopeId = scopeIds.get(scopeIndex);
                        List<Map<String, Object>> rows = new ArrayList<>();
                        for (GraphCandidate candidate : candidates) {
                            String subjectMention = hasText(candidate.getSubjectMentionName())
                                    ? candidate.getSubjectMentionName() : candidate.getSubjectName();
                            String objectMention = hasText(candidate.getObjectMentionName())
                                    ? candidate.getObjectMentionName() : candidate.getObjectName();
                            Map<String, Object> params = new HashMap<>();
                            params.put("subjectKey", entityKey(scopeId, candidate.getSubjectType(), candidate.getSubjectName()));
                            params.put("subjectName", candidate.getSubjectName());
                            params.put("subjectNormalizedName", normalizeName(candidate.getSubjectName()));
                            params.put("subjectMentionNormalizedName", normalizeName(subjectMention));
                            params.put("subjectType", normalizeType(candidate.getSubjectType()));
                            params.put("objectKey", entityKey(scopeId, candidate.getObjectType(), candidate.getObjectName()));
                            params.put("objectName", candidate.getObjectName());
                            params.put("objectNormalizedName", normalizeName(candidate.getObjectName()));
                            params.put("objectMentionNormalizedName", normalizeName(objectMention));
                            params.put("objectType", normalizeType(candidate.getObjectType()));
                            params.put("scopeId", scopeId);
                            params.put("documentKey", scopeId + "|" + file.getId());
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
                            rows.add(params);
                        }
                        for (int offset = 0; offset < rows.size(); offset += 100) {
                            tx.run("""
                                UNWIND $rows AS row
                                MERGE (d:Document {key: row.documentKey})
                                SET d.fileUploadId = row.fileUploadId,
                                    d.fileMd5 = row.fileMd5,
                                    d.title = row.fileName,
                                    d.scopeId = row.scopeId,
                                    d.updatedAt = datetime()
                                MERGE (s:Entity {key: row.subjectKey})
                                ON CREATE SET s.name = row.subjectName, s.type = row.subjectType, s.scopeId = row.scopeId,
                                              s.createdAt = datetime()
                                SET s.normalizedName = row.subjectNormalizedName,
                                    s.updatedAt = datetime()
                                MERGE (o:Entity {key: row.objectKey})
                                ON CREATE SET o.name = row.objectName, o.type = row.objectType, o.scopeId = row.scopeId,
                                              o.createdAt = datetime()
                                SET o.normalizedName = row.objectNormalizedName,
                                    o.updatedAt = datetime()
                                MERGE (sa:EntityAlias {key: row.subjectAliasKey})
                                SET sa.name = row.subjectMention,
                                    sa.normalizedName = row.subjectMentionNormalizedName,
                                    sa.type = row.subjectType, sa.scopeId = row.scopeId
                                MERGE (sa)-[:REFERS_TO]->(s)
                                MERGE (oa:EntityAlias {key: row.objectAliasKey})
                                SET oa.name = row.objectMention,
                                    oa.normalizedName = row.objectMentionNormalizedName,
                                    oa.type = row.objectType, oa.scopeId = row.scopeId
                                MERGE (oa)-[:REFERS_TO]->(o)
                                MERGE (c:Claim {key: row.claimKey})
                                SET c.candidateId = row.candidateId,
                                    c.scopeId = row.scopeId,
                                    c.predicate = row.predicate,
                                    c.normalizedPredicate = toLower(trim(row.predicate)),
                                    c.fileUploadId = row.fileUploadId,
                                    c.fileMd5 = row.fileMd5,
                                    c.fileName = row.fileName,
                                    c.primaryScope = row.primaryScope,
                                    c.chunkId = row.chunkId,
                                    c.evidence = row.evidence,
                                    c.confidence = row.confidence,
                                    c.valueScore = row.valueScore,
                                    c.subjectMention = row.subjectMention,
                                    c.objectMention = row.objectMention,
                                    c.updatedAt = datetime()
                                MERGE (c)-[:SUBJECT]->(s)
                                MERGE (c)-[:OBJECT]->(o)
                                MERGE (c)-[:SUBJECT_MENTION]->(sa)
                                MERGE (c)-[:OBJECT_MENTION]->(oa)
                                MERGE (d)-[:ASSERTS]->(c)
                                MERGE (s)-[f:FACT {key: row.claimKey}]->(o)
                                SET f.claimKey = row.claimKey,
                                    f.candidateId = row.candidateId,
                                    f.scopeId = row.scopeId,
                                    f.predicate = row.predicate,
                                    f.normalizedPredicate = toLower(trim(row.predicate)),
                                    f.fileUploadId = row.fileUploadId,
                                    f.fileMd5 = row.fileMd5,
                                    f.fileName = row.fileName,
                                    f.primaryScope = row.primaryScope,
                                    f.chunkId = row.chunkId,
                                    f.evidence = row.evidence,
                                    f.confidence = row.confidence,
                                    f.valueScore = row.valueScore,
                                    f.sourceKey = row.subjectKey,
                                    f.targetKey = row.objectKey,
                                    f.updatedAt = datetime()
                                """, Map.of("rows", rows.subList(offset, Math.min(offset + 100, rows.size())))).consume();
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
                tx.run("MATCH ()-[f:FACT {fileUploadId: $fileUploadId}]->() DELETE f",
                        Values.parameters("fileUploadId", fileUploadId)).consume();
                tx.run("MATCH (c:Claim {fileUploadId: $fileUploadId}) DETACH DELETE c",
                        Values.parameters("fileUploadId", fileUploadId)).consume();
                tx.run("MATCH (d:Document {fileUploadId: $fileUploadId}) DETACH DELETE d",
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
        return search(query, accessibleFileIds, limit, defaultMaxHops);
    }

    public List<GraphPath> search(String query, Collection<Long> accessibleFileIds, int limit, int maxHops) {
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null || accessibleFileIds == null || accessibleFileIds.isEmpty()
                || query == null || query.isBlank()) return List.of();
        List<Long> ids = accessibleFileIds.stream().filter(Objects::nonNull).distinct().toList();
        int safeLimit = Math.min(Math.max(limit, 1), 20);
        int safeMaxHops = Math.min(Math.max(maxHops, 1), Math.min(Math.max(hardMaxHops, 1), 4));
        String normalizedQuery = normalizeName(query);
        TransactionConfig retrievalConfig = TransactionConfig.builder()
                .withTimeout(Duration.ofSeconds(Math.min(Math.max(retrievalTimeoutSeconds, 1), 30)))
                .build();
        try {
            ensureConstraints();
            try (Session session = driver.session()) {
                List<Seed> seeds = resolveSeeds(session, query.trim(), normalizedQuery, ids, retrievalConfig);
                if (seeds.isEmpty()) return List.of();
                Map<String, Double> seedScores = new HashMap<>();
                seeds.forEach(seed -> seedScores.merge(seed.key(), seed.relevance(), Math::max));
                String cypher = """
                        MATCH (start:Entity)
                        WHERE start.key IN $seedKeys
                        MATCH path = (start)-[facts:FACT*1..%d]-(end:Entity)
                        WHERE end <> start
                          AND all(fact IN facts WHERE fact.fileUploadId IN $fileIds
                                  AND coalesce(fact.primaryScope, true) = true)
                          AND all(node IN nodes(path)
                                  WHERE single(other IN nodes(path) WHERE other.key = node.key))
                        WITH path, facts,
                             reduce(weakest = 1.0, fact IN facts |
                                 CASE WHEN coalesce(fact.confidence, 0.0) < weakest
                                      THEN coalesce(fact.confidence, 0.0) ELSE weakest END) AS weakest
                        ORDER BY length(path) ASC, weakest DESC
                        LIMIT $candidateLimit
                        RETURN [node IN nodes(path) |
                                  {key:node.key, name:node.name, type:node.type}] AS nodes,
                               [fact IN facts |
                                  {claimKey:fact.claimKey, candidateId:fact.candidateId,
                                   predicate:fact.predicate, fileUploadId:fact.fileUploadId,
                                   fileName:fact.fileName, fileMd5:fact.fileMd5,
                                   chunkId:fact.chunkId, evidence:fact.evidence,
                                   confidence:fact.confidence, valueScore:fact.valueScore,
                                   source:fact.sourceKey, target:fact.targetKey}] AS relations
                        """.formatted(safeMaxHops);
                List<GraphPath> candidates = session.executeRead(tx -> tx.run(
                                cypher,
                                Values.parameters(
                                        "seedKeys", seeds.stream().map(Seed::key).distinct().toList(),
                                        "fileIds", ids,
                                        "candidateLimit", Math.min(Math.max(candidatePathLimit, 20), 1000)))
                        .list(record -> new GraphPath(
                                record.get("nodes").asList(value -> value.asMap()),
                                record.get("relations").asList(value -> value.asMap()))), retrievalConfig);
                return rankPaths(query, seedScores, candidates, safeLimit);
            }
        } catch (RuntimeException e) {
            logger.warn("图谱检索不可用，自动回退现有检索: {}", e.getMessage());
            return List.of();
        }
    }
    private List<Seed> resolveSeeds(Session session, String query, String normalizedQuery,
                                    List<Long> fileIds, TransactionConfig retrievalConfig) {
        return session.executeRead(tx -> tx.run("""
                CALL {
                    MATCH (seed:Entity)
                    WHERE size(seed.name) >= 2
                      AND ((size(coalesce(seed.normalizedName, '')) >= 2
                              AND $normalizedQuery CONTAINS seed.normalizedName)
                           OR toLower($query) CONTAINS toLower(seed.name)
                           OR toLower(seed.name) CONTAINS toLower($query))
                      AND EXISTS {
                          MATCH (seed)<-[:SUBJECT|OBJECT]-(claim:Claim)
                          WHERE claim.fileUploadId IN $fileIds
                            AND coalesce(claim.primaryScope, true) = true
                      }
                    RETURN seed, 1.0 AS relevance
                    UNION
                    MATCH (alias:EntityAlias)-[:REFERS_TO]->(seed:Entity)
                    WHERE size(alias.normalizedName) >= 2
                      AND ($normalizedQuery CONTAINS alias.normalizedName
                           OR alias.normalizedName CONTAINS $normalizedQuery)
                      AND EXISTS {
                          MATCH (alias)<-[:SUBJECT_MENTION|OBJECT_MENTION]-(claim:Claim)
                          WHERE claim.fileUploadId IN $fileIds
                            AND coalesce(claim.primaryScope, true) = true
                      }
                    RETURN seed, 0.95 AS relevance
                    UNION
                    MATCH (document:Document)-[:ASSERTS]->(claim:Claim)-[:SUBJECT|OBJECT]->(seed:Entity)
                    WHERE claim.fileUploadId IN $fileIds
                      AND coalesce(claim.primaryScope, true) = true
                      AND size(coalesce(document.title, '')) >= 2
                      AND (toLower($query) CONTAINS toLower(document.title)
                           OR toLower(document.title) CONTAINS toLower($query))
                    RETURN seed, 0.85 AS relevance
                }
                WITH seed, max(relevance) AS relevance
                RETURN seed.key AS key, seed.name AS name, seed.type AS type, relevance
                ORDER BY relevance DESC, size(seed.name) DESC
                LIMIT $seedLimit
                """, Values.parameters(
                        "query", query,
                        "normalizedQuery", normalizedQuery,
                        "fileIds", fileIds,
                        "seedLimit", SEED_LIMIT))
                .list(record -> new Seed(
                        record.get("key").asString(),
                        record.get("name").asString(),
                        record.get("type").asString("OTHER"),
                        record.get("relevance").asDouble())), retrievalConfig);
    }
    static List<GraphPath> rankPaths(String query, Map<String, Double> seedScores,
                                     List<GraphPath> candidates, int limit) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        String normalizedQuery = normalizeName(query);
        Set<String> seedKeys = seedScores.keySet();
        Map<String, GraphPath> bestBySignature = new LinkedHashMap<>();
        for (GraphPath candidate : candidates) {
            if (candidate == null || candidate.nodes().size() < 2 || candidate.relations().isEmpty()) continue;
            Map<String, Object> first = candidate.nodes().get(0);
            Map<String, Object> last = candidate.nodes().get(candidate.nodes().size() - 1);
            String firstKey = Objects.toString(first.get("key"), "");
            String lastKey = Objects.toString(last.get("key"), "");
            double seedRelevance = seedScores.getOrDefault(firstKey, 0.5);
            String endpointName = Objects.toString(last.get("name"), "");
            String normalizedEndpoint = normalizeName(endpointName);
            double endpointRelevance = (!normalizedEndpoint.isBlank()
                            && normalizedQuery.contains(normalizedEndpoint))
                    || seedKeys.contains(lastKey) ? 1.0 : 0.45;
            double minConfidence = 1.0;
            double totalValue = 0.0;
            double predicateMatch = 0.0;
            Set<Long> documents = new HashSet<>();
            List<String> claimKeys = new ArrayList<>();
            for (Map<String, Object> relation : candidate.relations()) {
                minConfidence = Math.min(minConfidence, number(relation.get("confidence"), 0.0));
                totalValue += number(relation.get("valueScore"), 0.0);
                String predicate = Objects.toString(relation.get("predicate"), "");
                if (!predicate.isBlank() && normalizedQuery.contains(normalizeName(predicate))) predicateMatch = 1.0;
                Object fileId = relation.get("fileUploadId");
                if (fileId instanceof Number value) documents.add(value.longValue());
                claimKeys.add(Objects.toString(relation.get("claimKey"), ""));
            }
            int hops = candidate.relations().size();
            double averageValue = totalValue / hops;
            double documentSupport = Math.min(1.0, documents.size() / 2.0);
            double score = 0.30 * seedRelevance
                    + 0.25 * endpointRelevance
                    + 0.15 * minConfidence
                    + 0.15 * averageValue
                    + 0.10 * documentSupport
                    + 0.05 * predicateMatch
                    - 0.10 * Math.max(0, hops - 1);
            score = Math.max(0.0, Math.min(1.0, score));
            String terminalReason = seedKeys.contains(lastKey) ? "EXPLICIT_ENTITY" : "RANKED_RELEVANCE";
            GraphPath ranked = new GraphPath(
                    candidate.nodes(), candidate.relations(), score, hops,
                    documents.size() > 1, hops > 1, terminalReason);
            Collections.sort(claimKeys);
            String signature = String.join("|", claimKeys);
            bestBySignature.merge(signature, ranked,
                    (left, right) -> left.score() >= right.score() ? left : right);
        }
        return bestBySignature.values().stream()
                .sorted(Comparator.comparingDouble(GraphPath::score).reversed()
                        .thenComparingInt(GraphPath::hops))
                .limit(Math.min(Math.max(limit, 1), 20))
                .toList();
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private record Seed(String key, String name, String type, double relevance) {}


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

    public synchronized void ensureConstraints() {
        if (constraintsReady) return;
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) return;
        try (Session session = driver.session()) {
            session.run("CREATE CONSTRAINT entity_key_unique IF NOT EXISTS FOR (e:Entity) REQUIRE e.key IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT entity_alias_key_unique IF NOT EXISTS FOR (a:EntityAlias) REQUIRE a.key IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT claim_key_unique IF NOT EXISTS FOR (c:Claim) REQUIRE c.key IS UNIQUE").consume();
            session.run("CREATE CONSTRAINT document_key_unique IF NOT EXISTS FOR (d:Document) REQUIRE d.key IS UNIQUE").consume();
            session.run("CREATE INDEX entity_scope_name IF NOT EXISTS FOR (e:Entity) ON (e.scopeId, e.normalizedName)").consume();
            session.run("CREATE INDEX alias_scope_name IF NOT EXISTS FOR (a:EntityAlias) ON (a.scopeId, a.normalizedName)").consume();
            session.run("CREATE INDEX claim_scope_file IF NOT EXISTS FOR (c:Claim) ON (c.scopeId, c.fileUploadId)").consume();
            session.run("CREATE INDEX document_scope_file IF NOT EXISTS FOR (d:Document) ON (d.scopeId, d.fileUploadId)").consume();
            session.run("CREATE INDEX fact_scope_file IF NOT EXISTS FOR ()-[f:FACT]-() ON (f.scopeId, f.fileUploadId)").consume();

            long migrated;
            int completedBatches = 0;
            int maxBatches = Math.min(Math.max(backfillBatchesPerCall, 1), 10);
            do {
                completedBatches++;
                migrated = session.executeWrite(tx -> tx.run("""
                        MATCH (c:Claim)-[:SUBJECT]->(s:Entity)
                        MATCH (c)-[:OBJECT]->(o:Entity)
                        WHERE c.key IS NOT NULL AND (
                            NOT EXISTS { MATCH (:Document)-[:ASSERTS]->(c) }
                            OR NOT EXISTS { MATCH ()-[:FACT {key: c.key}]->() }
                        )
                        WITH c, s, o
                        LIMIT 500
                        MERGE (d:Document {
                            key: coalesce(c.scopeId, 'LEGACY') + '|' + toString(c.fileUploadId)
                        })
                        SET d.fileUploadId = c.fileUploadId,
                            d.fileMd5 = c.fileMd5,
                            d.title = c.fileName,
                            d.scopeId = c.scopeId,
                            d.updatedAt = datetime()
                        MERGE (d)-[:ASSERTS]->(c)
                        MERGE (s)-[f:FACT {key: c.key}]->(o)
                        SET f.claimKey = c.key,
                            f.candidateId = c.candidateId,
                            f.scopeId = c.scopeId,
                            f.predicate = c.predicate,
                            f.normalizedPredicate = c.normalizedPredicate,
                            f.fileUploadId = c.fileUploadId,
                            f.fileMd5 = c.fileMd5,
                            f.fileName = c.fileName,
                            f.primaryScope = c.primaryScope,
                            f.chunkId = c.chunkId,
                            f.evidence = c.evidence,
                            f.confidence = c.confidence,
                            f.valueScore = c.valueScore,
                            f.sourceKey = s.key,
                            f.targetKey = o.key,
                            f.updatedAt = datetime()
                        RETURN count(c) AS migrated
                        """).single().get("migrated").asLong());
            } while (migrated == 500 && completedBatches < maxBatches);
            constraintsReady = migrated < 500;
            if (!constraintsReady) {
                logger.info("旧图谱投影本轮已回填 {} 批，将在后续调用继续", completedBatches);
            }
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

    public record GraphPath(List<Map<String, Object>> nodes,
                            List<Map<String, Object>> relations,
                            double score,
                            int hops,
                            boolean crossDocument,
                            boolean inferred,
                            String terminalReason) {
        public GraphPath(List<Map<String, Object>> nodes, List<Map<String, Object>> relations) {
            this(nodes, relations, 0.0, relations == null ? 0 : relations.size(),
                    false, relations != null && relations.size() > 1, "UNRANKED");
        }
    }

    public record OrganizationRelation(String sourceKey, String sourceName, String sourceType,
                                       String targetKey, String targetName, String targetType,
                                       Long candidateId, String predicate, Long fileUploadId,
                                       String fileMd5, String fileName, Integer chunkId,
                                       String evidence, Double confidence) {}
}
