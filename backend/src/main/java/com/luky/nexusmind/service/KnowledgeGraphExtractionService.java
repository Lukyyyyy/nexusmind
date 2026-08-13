package com.luky.nexusmind.service;

import com.luky.nexusmind.client.KnowledgeGraphExtractionClient;
import com.luky.nexusmind.model.*;
import com.luky.nexusmind.repository.DocumentVectorRepository;
import com.luky.nexusmind.repository.FileUploadRepository;
import com.luky.nexusmind.repository.GraphCandidateRepository;
import com.luky.nexusmind.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class KnowledgeGraphExtractionService {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeGraphExtractionService.class);
    // Smaller batches leave enough output budget for exhaustive relation coverage instead of a short summary.
    private static final int MAX_BATCH_CHARS = 3200;
    private static final int MAX_GLOSSARY_CONTEXT_CHARS = 9000;
    private static final double MIN_CONFIDENCE = 0.60;
    private static final double MIN_VALUE_SCORE = 0.60;
    private static final Pattern AMBIGUOUS_ENTITY_PATTERN = Pattern.compile(
            "^(?:本文|本研究|本工作|本论文|本系统|本项目|本方案|本团队|本|该|此|上述|前述|所提|所提出的|提出的|我们的|我们(?:所)?提出的?)" +
                    "(?:所?提出的?|采用的?|设计的?|构建的?|改进的?)?" +
                    "(?:模型|方法|算法|系统|方案|框架|网络|技术|研究|工作|模块|机制)$");
    private static final Set<String> LOW_VALUE_DOCUMENT_PREDICATES = Set.of(
            "引用", "参考", "发表于", "发表", "发布", "收录", "撰写", "著作", "参与撰写", "共同发表");
    private static final Pattern BIBLIOGRAPHY_EVIDENCE_PATTERN = Pattern.compile(
            "(?i).*(?:\\[[JCMD]\\]|\\bet\\s+al\\.?|\\bdoi\\s*:|\\bvol\\.?\\s*\\d+|\\bpp?\\.?\\s*\\d+|" +
                    "\\d{4}\\s*[,，]\\s*\\d+\\s*\\(\\d+\\)\\s*[:：]\\s*\\d+[-–—]\\d+).*",
            Pattern.DOTALL);

    private final FileUploadRepository fileUploadRepository;
    private final DocumentVectorRepository documentVectorRepository;
    private final GraphCandidateRepository candidateRepository;
    private final UserRepository userRepository;
    private final KnowledgeGraphExtractionClient extractionClient;
    private final GraphPromptTemplateService templateService;

    public KnowledgeGraphExtractionService(FileUploadRepository fileUploadRepository,
                                           DocumentVectorRepository documentVectorRepository,
                                           GraphCandidateRepository candidateRepository,
                                           UserRepository userRepository,
                                           KnowledgeGraphExtractionClient extractionClient,
                                           GraphPromptTemplateService templateService) {
        this.fileUploadRepository = fileUploadRepository;
        this.documentVectorRepository = documentVectorRepository;
        this.candidateRepository = candidateRepository;
        this.userRepository = userRepository;
        this.extractionClient = extractionClient;
        this.templateService = templateService;
    }

    @Async
    public void extractAsync(String fileMd5, String ownerId) {
        try {
            extract(fileMd5, ownerId);
        } catch (Exception e) {
            logger.error("知识图谱抽取失败，fileMd5={}", fileMd5, e);
        }
    }

    @Transactional
    public void extract(String fileMd5, String ownerId) {
        FileUpload file = fileUploadRepository.findByFileMd5AndUserId(fileMd5, ownerId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        if (!file.isGraphEnabled()) return;
        file.setGraphStatus(KnowledgeGraphStatus.EXTRACTING);
        file.setGraphError(null);
        fileUploadRepository.save(file);
        candidateRepository.deleteByFileUploadId(file.getId());

        try {
            List<DocumentVector> chunks = distinctChunks(documentVectorRepository.findByFileMd5(fileMd5));
            if (chunks.isEmpty()) throw new IllegalStateException("文档尚未完成解析");
            String username = resolveUsername(file.getUserId());
            GraphPromptTemplateService.ResolvedTemplate template = templateService.resolve(file.getGraphPromptTemplateId());
            if (file.getGraphPromptTemplateId() == null && template.id() != null) {
                file.setGraphPromptTemplateId(template.id());
                fileUploadRepository.save(file);
            }
            int saved = 0;
            int filteredLowValue = 0;
            int failedBatches = 0;
            Exception lastBatchError = null;
            Set<String> savedRelationKeys = new HashSet<>();
            List<List<DocumentVector>> chunkBatches = batches(chunks);
            List<KnowledgeGraphExtractionClient.EntityResolution> glossary = loadEntityGlossary(
                    username, file.getFileName(), chunks, template.instructions());
            Map<String, KnowledgeGraphExtractionClient.EntityResolution> glossaryByMention = glossaryMap(glossary);
            for (int batchIndex = 0; batchIndex < chunkBatches.size(); batchIndex++) {
                List<DocumentVector> batch = chunkBatches.get(batchIndex);
                try {
                    String input = buildInput(file.getFileName(), glossary, batch);
                    KnowledgeGraphExtractionClient.ExtractionResult result = extractionClient.extract(
                            username, input, template.instructions());
                    Map<Integer, String> evidenceByChunk = new HashMap<>();
                    batch.forEach(chunk -> evidenceByChunk.put(chunk.getChunkId(), chunk.getTextContent()));
                    for (KnowledgeGraphExtractionClient.ExtractedRelation relation : result.relations()) {
                        GraphCandidate candidate = toCandidate(
                                file, relation, evidenceByChunk, glossaryByMention, result.modelName());
                        if (candidate != null && isLowValueRelation(candidate)) {
                            filteredLowValue++;
                            logger.debug("过滤低价值图谱关系: {} -[{}]-> {}",
                                    candidate.getSubjectName(), candidate.getPredicate(), candidate.getObjectName());
                        } else if (candidate != null && savedRelationKeys.add(relationKey(candidate))) {
                            candidateRepository.save(candidate);
                            saved++;
                        }
                    }
                } catch (Exception batchError) {
                    failedBatches++;
                    lastBatchError = batchError;
                    logger.warn("知识图谱第 {}/{} 批抽取失败，fileMd5={}",
                            batchIndex + 1, chunkBatches.size(), fileMd5, batchError);
                }
            }

            if (saved == 0 && lastBatchError != null) {
                throw new IllegalStateException(lastBatchError.getMessage(), lastBatchError);
            }
            file.setGraphStatus(KnowledgeGraphStatus.PENDING_REVIEW);
            if (failedBatches > 0) {
                String reason = hasText(lastBatchError.getMessage())
                        ? abbreviate(lastBatchError.getMessage(), 300) : "未知错误";
                file.setGraphError(String.format("已抽取 %d 条关系，但有 %d/%d 批抽取未完成：%s。请审核已有结果或重新抽取",
                        saved, failedBatches, chunkBatches.size(), reason));
            } else {
                file.setGraphError(saved == 0
                        ? filteredLowValue > 0
                            ? String.format("未识别到有业务价值的关系，已过滤 %d 条参考文献或文档元数据关系", filteredLowValue)
                            : "未从文档中识别到可靠关系"
                        : null);
            }
            fileUploadRepository.save(file);
        } catch (Exception e) {
            file.setGraphStatus(KnowledgeGraphStatus.FAILED);
            file.setGraphError(abbreviate(e.getMessage(), 1000));
            fileUploadRepository.save(file);
            throw e;
        }
    }

    private GraphCandidate toCandidate(FileUpload file,
                                       KnowledgeGraphExtractionClient.ExtractedRelation value,
                                       Map<Integer, String> evidenceByChunk,
                                       Map<String, KnowledgeGraphExtractionClient.EntityResolution> glossary,
                                       String modelName) {
        if (value == null || value.subject() == null || value.object() == null
                || !hasText(value.subject().name()) || !hasText(value.object().name())
                || !hasText(value.predicate()) || value.chunkId() == null
                || !evidenceByChunk.containsKey(value.chunkId())) return null;
        double confidence = value.confidence() == null ? 0.0 : value.confidence();
        if (confidence < MIN_CONFIDENCE) return null;
        double valueScore = value.valueScore() == null ? 0.0 : value.valueScore();
        if (valueScore < MIN_VALUE_SCORE) return null;
        ResolvedEntity subject = resolveEntity(value.subject(), glossary);
        ResolvedEntity object = resolveEntity(value.object(), glossary);
        if (subject == null || object == null) {
            logger.warn("跳过含模糊指代的图谱关系: {} -[{}]-> {}",
                    value.subject().name(), value.predicate(), value.object().name());
            return null;
        }
        String source = evidenceByChunk.get(value.chunkId());
        String evidence = hasText(value.evidence()) && source.contains(value.evidence().trim())
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

    private List<DocumentVector> distinctChunks(List<DocumentVector> values) {
        Map<Integer, DocumentVector> byId = new TreeMap<>();
        values.forEach(value -> byId.putIfAbsent(value.getChunkId(), value));
        return new ArrayList<>(byId.values());
    }

    private List<List<DocumentVector>> batches(List<DocumentVector> chunks) {
        List<List<DocumentVector>> result = new ArrayList<>();
        List<DocumentVector> current = new ArrayList<>();
        int chars = 0;
        for (DocumentVector chunk : chunks) {
            int size = chunk.getTextContent() == null ? 0 : chunk.getTextContent().length();
            if (!current.isEmpty() && chars + size > MAX_BATCH_CHARS) {
                result.add(current);
                current = new ArrayList<>();
                chars = 0;
            }
            current.add(chunk);
            chars += size;
        }
        if (!current.isEmpty()) result.add(current);
        return result;
    }

    private List<KnowledgeGraphExtractionClient.EntityResolution> loadEntityGlossary(
            String username, String fileName, List<DocumentVector> chunks, String templateInstructions) {
        try {
            KnowledgeGraphExtractionClient.EntityGlossary result = extractionClient.extractEntityGlossary(
                    username, documentTitle(fileName), buildGlossaryContext(chunks), templateInstructions);
            return result.entities().stream()
                    .filter(this::validResolution)
                    .toList();
        } catch (Exception e) {
            logger.warn("文档实体词典生成失败，将由关系抽取模型直接解析指代: fileName={}", fileName, e);
            return List.of();
        }
    }

    private boolean validResolution(KnowledgeGraphExtractionClient.EntityResolution value) {
        return value != null && hasText(value.mention()) && hasText(value.canonicalName())
                && !isAmbiguousEntityName(value.canonicalName());
    }

    private Map<String, KnowledgeGraphExtractionClient.EntityResolution> glossaryMap(
            List<KnowledgeGraphExtractionClient.EntityResolution> glossary) {
        Map<String, KnowledgeGraphExtractionClient.EntityResolution> result = new LinkedHashMap<>();
        glossary.forEach(value -> result.putIfAbsent(normalizeMention(value.mention()), value));
        return result;
    }

    private ResolvedEntity resolveEntity(KnowledgeGraphExtractionClient.EntityValue value,
                                         Map<String, KnowledgeGraphExtractionClient.EntityResolution> glossary) {
        String mention = value.name().trim();
        KnowledgeGraphExtractionClient.EntityResolution resolution = glossary.get(normalizeMention(mention));
        if (resolution != null) {
            String type = hasText(resolution.type()) ? resolution.type() : value.type();
            return new ResolvedEntity(mention, resolution.canonicalName().trim(), normalizeType(type));
        }
        if (isAmbiguousEntityName(mention)) return null;
        return new ResolvedEntity(mention, mention, normalizeType(value.type()));
    }

    static boolean isAmbiguousEntityName(String value) {
        if (value == null || value.isBlank()) return true;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim().replaceAll("[\\s·•._\\-—–]+", "");
        return AMBIGUOUS_ENTITY_PATTERN.matcher(normalized).matches();
    }

    private String normalizeMention(String value) {
        return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
                .trim().toLowerCase(Locale.ROOT).replaceAll("[\\s·•._\\-—–]+", "");
    }

    private String entitySignature(String type, String name) {
        return normalizeType(type) + "|" + normalizeMention(name);
    }

    static boolean isLowValueRelation(GraphCandidate candidate) {
        if (candidate == null) return true;
        String subjectType = normalizeStaticType(candidate.getSubjectType());
        String objectType = normalizeStaticType(candidate.getObjectType());
        String predicate = Objects.toString(candidate.getPredicate(), "").trim().replaceAll("\\s+", "");
        boolean documentMetadata = LOW_VALUE_DOCUMENT_PREDICATES.contains(predicate)
                && ("DOCUMENT".equals(subjectType) || "DOCUMENT".equals(objectType));
        boolean authorBibliography = "PERSON".equals(subjectType) && "DOCUMENT".equals(objectType)
                && LOW_VALUE_DOCUMENT_PREDICATES.contains(predicate);
        boolean bibliographyEntry = ("DOCUMENT".equals(subjectType) || "DOCUMENT".equals(objectType)
                || "PERSON".equals(subjectType))
                && BIBLIOGRAPHY_EVIDENCE_PATTERN.matcher(
                        Objects.toString(candidate.getEvidenceText(), "").trim()).matches();
        return documentMetadata || authorBibliography || bibliographyEntry;
    }

    private static String normalizeStaticType(String value) {
        return Objects.toString(value, "OTHER").trim().toUpperCase(Locale.ROOT);
    }

    private String relationKey(GraphCandidate candidate) {
        return entitySignature(candidate.getSubjectType(), candidate.getSubjectName()) + "|"
                + normalizeMention(candidate.getPredicate()) + "|"
                + entitySignature(candidate.getObjectType(), candidate.getObjectName()) + "|"
                + candidate.getEvidenceChunkId();
    }

    private String buildGlossaryContext(List<DocumentVector> chunks) {
        StringBuilder value = new StringBuilder();
        for (DocumentVector chunk : chunks) {
            if (value.length() >= MAX_GLOSSARY_CONTEXT_CHARS) break;
            String text = Objects.toString(chunk.getTextContent(), "");
            int remaining = MAX_GLOSSARY_CONTEXT_CHARS - value.length();
            value.append(text, 0, Math.min(text.length(), remaining)).append('\n');
        }
        return value.toString();
    }

    private String buildInput(String fileName,
                              List<KnowledgeGraphExtractionClient.EntityResolution> glossary,
                              List<DocumentVector> chunks) {
        StringBuilder value = new StringBuilder()
                .append("文档标题：").append(documentTitle(fileName)).append("\n")
                .append("这些关系将合并到包含多篇文档的组织知识图谱中。\n")
                .append("实体词典（遇到对应原文称呼时必须使用右侧标准名称）：\n");
        if (glossary.isEmpty()) {
            value.append("（未识别到可可靠解析的文档指代；不要输出含模糊指代的关系）\n");
        } else {
            glossary.forEach(item -> value.append("- ").append(item.mention()).append(" => ")
                    .append(item.canonicalName()).append(" [").append(normalizeType(item.type())).append("]\n"));
        }
        value.append("\n请从以下文档切片抽取实体关系：\n\n");
        for (DocumentVector chunk : chunks) {
            value.append("[CHUNK ").append(chunk.getChunkId()).append("]\n")
                    .append(chunk.getTextContent()).append("\n\n");
        }
        return value.toString();
    }

    private String documentTitle(String fileName) {
        String value = Objects.toString(fileName, "未命名文档").trim();
        return value.replaceFirst("(?i)\\.(pdf|docx?|pptx?|xlsx?|txt|md)$", "");
    }

    private String resolveUsername(String ownerId) {
        Optional<User> user = userRepository.findByUsername(ownerId);
        if (user.isEmpty()) {
            try { user = userRepository.findById(Long.parseLong(ownerId)); }
            catch (NumberFormatException ignored) { }
        }
        return user.map(User::getUsername).orElse(ownerId);
    }

    private String normalizeType(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "OTHER";
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String abbreviate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record ResolvedEntity(String mention, String canonicalName, String type) {}
}
