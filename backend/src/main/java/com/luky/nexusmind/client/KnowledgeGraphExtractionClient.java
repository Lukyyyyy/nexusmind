package com.luky.nexusmind.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.service.ModelConfigService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeGraphExtractionClient {
    private static final String SYSTEM_PROMPT = """
            你是知枢 NexusMind 的知识图谱抽取器。抽取结果将合并到包含多篇文档的组织知识图谱中。
            请只抽取输入文本明确表达的事实，不得使用常识补充或猜测。
            实体类型优先使用 PERSON、ORGANIZATION、PROJECT、PRODUCT、SYSTEM、SERVICE、MODEL、METHOD、ALGORITHM、COMPONENT、TECHNOLOGY、DATASET、TASK、METRIC、LOCATION、EVENT、CONCEPT、DOCUMENT、OTHER。
            关系使用简短、稳定的中文动词或动宾短语，例如：属于、负责、依赖、调用、部署于、影响、参与、包含。
            每条关系必须能由一个指定的 CHUNK 直接证明。若无法确定则不要输出。
            实体名称必须脱离当前文档后仍可独立识别，并能与其他文档中的实体区分。
            严禁把“本文模型、本文方法、本研究、本系统、该模型、该方法、所提模型、提出的方法、我们的模型”等依赖当前语境的指代直接作为实体名称。
            遇到指代时，优先使用输入中提供的实体词典；否则依据文档标题和当前文本解析为有原文依据的明确名称，不得凭空创造。仍无法可靠解析时，不输出该关系。
            同一实体在本次输入的所有关系中必须使用完全一致的名称。
            实体词典只用于名称消歧和指代替换，不是实体白名单。必须继续识别切片中出现的其他明确实体。

            抽取目标不是追求数量，而是构建能支持知识检索、问答、技术比较、因果分析和决策判断的高价值事实。
            抽取时逐个检查全部 CHUNK，保留其中明确且有独立知识价值的事实：
            1. 主体和客体都可以是任何明确实体，禁止把所有关系都强行连接到论文主模型或主方法；
            2. 优先提取方法/模型的组成、依赖、输入输出、适用任务、使用数据、实验指标、比较结果、约束条件、因果影响和明确结论；
            3. 一句话包含多个事实时拆成多条关系。例如“A 使用 B 和 C”应输出 A-使用-B、A-使用-C；
            4. 非主模型关系只有在能形成可复用领域知识时才提取，例如“数据集用于任务”“模块基于算法”“方法优于基线”；
            5. 同一主谓宾只输出一次，但不同主体、不同客体或不同谓词均应保留；
            6. 不设最低数量，不要为了覆盖更多主体而凑数；单次最多输出 40 条高价值关系。

            以下内容默认没有组织知识图谱价值，禁止输出：
            - 参考文献列表、文末书目、引用格式及“本文引用某论文”；
            - 作者—发表—论文、作者—参与—论文、期刊/会议—收录—论文；
            - 仅从参考文献条目推断出的论文标题、作者、年份、页码关系；
            - 目录、页眉页脚、致谢、基金编号以及没有领域含义的文档元数据；
            - “本文进行了研究”“方法效果较好”等缺少具体对象或结论的空泛陈述。
            只有当输入正文明确讨论某项既有工作的具体方法、能力、局限或对比结果时，才可抽取该工作相关事实；仅出现于参考文献列表不算证据。
            只输出 JSON，格式为：
            {"relations":[{"subject":{"name":"","type":"SYSTEM"},"predicate":"依赖","object":{"name":"","type":"SERVICE"},"chunkId":1,"evidence":"原文中的直接证据","confidence":0.95,"valueScore":0.85}]}
            confidence 表示原文支持强度；valueScore 表示该事实进入跨文档组织图谱后的复用和决策价值，范围均为 0 到 1。
            valueScore 评分参考：能回答“如何实现、使用什么、效果多少、优于什么、受什么限制、导致什么”的具体事实通常不低于 0.7；普通背景事实约 0.5；书目和文档元数据为 0 且不得输出。
            只输出 valueScore 不低于 0.6 的关系。实体名称使用原文中最完整、最明确的名称。
            """;

    private static final String GLOSSARY_PROMPT = """
            你是知枢 NexusMind 的文档实体解析器。后续抽取结果将合并到包含多篇文档的组织知识图谱中。
            请根据文档标题和正文，为依赖当前文档语境的称呼解析可跨文档识别的标准实体名称。
            重点处理“本文模型、本文方法、本研究、本系统、该模型、该方法、所提模型、提出的方法、我们的模型”等指代，以及同一实体的简称或别名。
            标准名称必须能够脱离本文独立理解，并且必须有标题或正文依据；不得使用外部知识或凭空创造。
            无法可靠解析的指代不要输出。不要为普通名词或已经明确的专有名称创建映射。
            只输出 JSON，格式为：
            {"entities":[{"mention":"本文模型","canonicalName":"基于卷积神经网络的车辆碰撞声识别模型","type":"SYSTEM"}]}
            实体类型优先使用 PERSON、ORGANIZATION、PROJECT、PRODUCT、SYSTEM、SERVICE、MODEL、METHOD、ALGORITHM、COMPONENT、TECHNOLOGY、DATASET、TASK、METRIC、LOCATION、EVENT、CONCEPT、DOCUMENT、OTHER。
            """;

    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;

    public KnowledgeGraphExtractionClient(ModelConfigService modelConfigService, ObjectMapper objectMapper) {
        this.modelConfigService = modelConfigService;
        this.objectMapper = objectMapper;
    }

    public ExtractionResult extract(String username, String chunkText, String templateInstructions) {
        ModelConfigService.ResolvedModelConfig config = modelConfigService.resolveGraphExtractionConfig(username);
        String prompt = withTemplate(SYSTEM_PROMPT, templateInstructions);
        String response = complete(config, prompt, chunkText, Math.max(4000,
                config.maxTokens() == null ? 4000 : config.maxTokens()));
        if (response == null || response.isBlank()) return new ExtractionResult(config.modelName(), List.of());
        try {
            List<ExtractedRelation> relations = parseRelations(response);
            return new ExtractionResult(config.modelName(), relations == null ? List.of() : relations);
        } catch (Exception firstError) {
            String retryPrompt = chunkText + "\n\n上一次响应不是有效 JSON。请重新检查并仅输出规定的 JSON 对象，" +
                    "不要使用 Markdown，不要附加解释；本次最多返回 25 条最高价值关系。";
            try {
                String retry = complete(config, prompt, retryPrompt, Math.max(3000,
                        config.maxTokens() == null ? 3000 : config.maxTokens()));
                List<ExtractedRelation> relations = parseRelations(retry);
                return new ExtractionResult(config.modelName(), relations == null ? List.of() : relations);
            } catch (Exception retryError) {
                retryError.addSuppressed(firstError);
                throw new IllegalStateException("图谱抽取模型返回了无效数据", retryError);
            }
        }
    }

    private List<ExtractedRelation> parseRelations(String response) throws Exception {
        if (response == null || response.isBlank()) return List.of();
        JsonNode root = responseJson(response);
        return objectMapper.readerForListOf(ExtractedRelation.class).readValue(root.path("relations"));
    }

    public EntityGlossary extractEntityGlossary(String username, String documentTitle, String documentContext,
                                                String templateInstructions) {
        ModelConfigService.ResolvedModelConfig config = modelConfigService.resolveGraphExtractionConfig(username);
        String input = "文档标题：" + documentTitle + "\n\n文档正文：\n" + documentContext;
        String response = complete(config, withTemplate(GLOSSARY_PROMPT, templateInstructions), input, 1200);
        if (response == null || response.isBlank()) return new EntityGlossary(config.modelName(), List.of());
        try {
            JsonNode root = responseJson(response);
            List<EntityResolution> entities = objectMapper.readerForListOf(EntityResolution.class)
                    .readValue(root.path("entities"));
            return new EntityGlossary(config.modelName(), entities == null ? List.of() : entities);
        } catch (Exception e) {
            throw new IllegalStateException("实体词典模型返回了无效数据", e);
        }
    }

    private String complete(ModelConfigService.ResolvedModelConfig config, String systemPrompt,
                            String userPrompt, int maxTokens) {
        WebClient.Builder builder = WebClient.builder().baseUrl(config.baseUrl());
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey());
        }
        Map<String, Object> request = new HashMap<>();
        request.put("model", config.modelName());
        request.put("stream", false);
        request.put("temperature", 0);
        request.put("max_tokens", maxTokens);
        request.put("response_format", Map.of("type", "json_object"));
        request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));

        WebClient webClient = builder.build();
        String response;
        try {
            response = invoke(webClient, request);
        } catch (WebClientResponseException.BadRequest unsupportedStructuredOutput) {
            // Some OpenAI-compatible providers do not implement response_format yet.
            request.remove("response_format");
            response = invoke(webClient, request);
        }
        return response;
    }

    private String withTemplate(String basePrompt, String templateInstructions) {
        if (templateInstructions == null || templateInstructions.isBlank()) return basePrompt;
        return basePrompt + "\n\n当前文档选用的抽取模板（在通用安全与输出规则基础上执行）：\n"
                + templateInstructions.trim()
                + "\n\n模板只补充领域关注点，不得改变以上证据要求、价值阈值、实体消歧、JSON 格式和禁止项；如有冲突，以上通用规则优先。";
    }

    private JsonNode responseJson(String response) throws Exception {
        String content = objectMapper.readTree(response).path("choices").path(0).path("message").path("content").asText();
        return objectMapper.readTree(stripCodeFence(content));
    }

    private String invoke(WebClient webClient, Map<String, Object> request) {
        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private String stripCodeFence(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstLine = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        return firstLine >= 0 && lastFence > firstLine
                ? trimmed.substring(firstLine + 1, lastFence).trim()
                : trimmed;
    }

    public record ExtractionResult(String modelName, List<ExtractedRelation> relations) {}
    public record EntityGlossary(String modelName, List<EntityResolution> entities) {}
    public record EntityResolution(String mention, String canonicalName, String type) {}
    public record ExtractedRelation(EntityValue subject, String predicate, EntityValue object,
                                    Integer chunkId, String evidence, Double confidence, Double valueScore) {}
    public record EntityValue(String name, String type) {}
}
