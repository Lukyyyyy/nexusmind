package com.luky.nexusmind.service;

import com.luky.nexusmind.client.KnowledgeGraphExtractionClient;
import com.luky.nexusmind.model.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class KnowledgeGraphExtractionService {
    private static final Logger logger =
            LoggerFactory.getLogger(KnowledgeGraphExtractionService.class);
    private static final double MIN_CONFIDENCE = 0.60;
    private static final double MIN_VALUE_SCORE = 0.60;
    private static final Pattern AMBIGUOUS_ENTITY_PATTERN =
            Pattern.compile(
                    "^(?:本文|本研究|本工作|本论文|本系统|本项目|本方案|本团队|本|该|此|上述|前述|所提|所提出的|提出的|我们的|我们(?:所)?提出的?)"
                            + "(?:所?提出的?|采用的?|设计的?|构建的?|改进的?)?"
                            + "(?:模型|方法|算法|系统|方案|框架|网络|技术|研究|工作|模块|机制)$");
    private static final Set<String> LOW_VALUE_DOCUMENT_PREDICATES =
            Set.of("引用", "参考", "发表于", "发表", "发布", "收录", "撰写", "著作", "参与撰写", "共同发表");
    private static final Pattern BIBLIOGRAPHY_EVIDENCE_PATTERN =
            Pattern.compile(
                    "(?i).*(?:\\[[JCMD]\\]|\\bet\\s+al\\.?|\\bdoi\\s*:|\\bvol\\.?\\s*\\d+|\\bpp?\\.?\\s*\\d+|"
                        + "\\d{4}\\s*[,，]\\s*\\d+\\s*\\(\\d+\\)\\s*[:：]\\s*\\d+[-–—]\\d+).*",
                    Pattern.DOTALL);

    private final GraphExtractionEngine engine;

    public KnowledgeGraphExtractionService(GraphExtractionEngine engine) {
        this.engine = engine;
    }

    @Async
    public void extractAsync(String fileMd5, String ownerId) {
        try {
            extract(fileMd5, ownerId);
        } catch (Exception e) {
            logger.error("知识图谱抽取失败，fileMd5={}", fileMd5, e);
        }
    }

    public void extract(String fileMd5, String ownerId) {
        engine.run(fileMd5, ownerId, false, this);
    }

    @Async
    public void retryAsync(String fileMd5, String ownerId) {
        engine.run(fileMd5, ownerId, true, this);
    }

    GraphCandidate toCandidate(
            FileUpload file,
            KnowledgeGraphExtractionClient.ExtractedRelation value,
            Map<Integer, String> evidenceByChunk,
            Map<String, KnowledgeGraphExtractionClient.EntityResolution> glossary,
            String modelName) {
        if (value == null
                || value.subject() == null
                || value.object() == null
                || !hasText(value.subject().name())
                || !hasText(value.object().name())
                || !hasText(value.predicate())
                || value.chunkId() == null
                || !evidenceByChunk.containsKey(value.chunkId())) return null;
        double confidence = value.confidence() == null ? 0.0 : value.confidence();
        if (confidence < MIN_CONFIDENCE) return null;
        double valueScore = value.valueScore() == null ? 0.0 : value.valueScore();
        if (valueScore < MIN_VALUE_SCORE) return null;
        ResolvedEntity subject = resolveEntity(value.subject(), glossary);
        ResolvedEntity object = resolveEntity(value.object(), glossary);
        if (subject == null || object == null) {
            logger.warn(
                    "跳过含模糊指代的图谱关系: {} -[{}]-> {}",
                    value.subject().name(),
                    value.predicate(),
                    value.object().name());
            return null;
        }
        String source = evidenceByChunk.get(value.chunkId());
        String evidence =
                hasText(value.evidence()) && source.contains(value.evidence().trim())
                        ? value.evidence().trim()
                        : abbreviate(source, 600);
        GraphCandidate candidate = new GraphCandidate();
        candidate.setFileUploadId(file.getId());
        candidate.setSubjectName(subject.canonicalName());
        candidate.setSubjectMentionName(subject.mention());
        candidate.setSubjectType(subject.type());
        candidate.setPredicate(value.predicate().trim());
        candidate.setObjectName(object.canonicalName());
        candidate.setObjectMentionName(object.mention());
        candidate.setObjectType(object.type());
        candidate.setEvidenceChunkId(value.chunkId());
        candidate.setEvidenceText(evidence);
        candidate.setConfidence(Math.min(1.0, confidence));
        candidate.setValueScore(Math.min(1.0, valueScore));
        candidate.setSelected(true);
        candidate.setStatus(GraphCandidateStatus.PENDING);
        candidate.setModelName(modelName);
        return candidate;
    }

    private ResolvedEntity resolveEntity(
            KnowledgeGraphExtractionClient.EntityValue value,
            Map<String, KnowledgeGraphExtractionClient.EntityResolution> glossary) {
        String mention = value.name().trim();
        KnowledgeGraphExtractionClient.EntityResolution resolution =
                glossary.get(normalizeMention(mention));
        if (resolution != null) {
            String type = hasText(resolution.type()) ? resolution.type() : value.type();
            return new ResolvedEntity(
                    mention, resolution.canonicalName().trim(), normalizeType(type));
        }
        if (isAmbiguousEntityName(mention)) return null;
        return new ResolvedEntity(mention, mention, normalizeType(value.type()));
    }

    static boolean isAmbiguousEntityName(String value) {
        if (value == null || value.isBlank()) return true;
        String normalized =
                Normalizer.normalize(value, Normalizer.Form.NFKC)
                        .trim()
                        .replaceAll("[\\s·•._\\-—–]+", "");
        return AMBIGUOUS_ENTITY_PATTERN.matcher(normalized).matches();
    }

    private String normalizeMention(String value) {
        return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s·•._\\-—–]+", "");
    }

    static boolean isLowValueRelation(GraphCandidate candidate) {
        if (candidate == null) return true;
        String subjectType = normalizeStaticType(candidate.getSubjectType());
        String objectType = normalizeStaticType(candidate.getObjectType());
        String predicate =
                Objects.toString(candidate.getPredicate(), "").trim().replaceAll("\\s+", "");
        boolean documentMetadata =
                LOW_VALUE_DOCUMENT_PREDICATES.contains(predicate)
                        && ("DOCUMENT".equals(subjectType) || "DOCUMENT".equals(objectType));
        boolean authorBibliography =
                "PERSON".equals(subjectType)
                        && "DOCUMENT".equals(objectType)
                        && LOW_VALUE_DOCUMENT_PREDICATES.contains(predicate);
        boolean bibliographyEntry =
                ("DOCUMENT".equals(subjectType)
                                || "DOCUMENT".equals(objectType)
                                || "PERSON".equals(subjectType))
                        && BIBLIOGRAPHY_EVIDENCE_PATTERN
                                .matcher(Objects.toString(candidate.getEvidenceText(), "").trim())
                                .matches();
        return documentMetadata || authorBibliography || bibliographyEntry;
    }

    private static String normalizeStaticType(String value) {
        return Objects.toString(value, "OTHER").trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeType(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "OTHER";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String abbreviate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record ResolvedEntity(String mention, String canonicalName, String type) {}
}
