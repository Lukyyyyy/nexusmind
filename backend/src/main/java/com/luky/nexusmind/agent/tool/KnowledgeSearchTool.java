package com.luky.nexusmind.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luky.nexusmind.agent.AgentContext;
import com.luky.nexusmind.agent.ToolDefinition;
import com.luky.nexusmind.agent.ToolResult;
import com.luky.nexusmind.entity.SearchResult;
import com.luky.nexusmind.service.HybridSearchService;
import com.luky.nexusmind.service.ModelConfigService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KnowledgeSearchTool implements AgentTool {
    private static final int RESULT_LIMIT = ModelConfigService.STANDARD_FINAL_TOP_K;

    private final HybridSearchService searchService;
    private final ObjectMapper mapper;

    public KnowledgeSearchTool(HybridSearchService searchService, ObjectMapper mapper) {
        this.searchService = searchService;
        this.mapper = mapper;
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("query", mapper.createObjectNode().put("type", "string")
                .put("description", "完整、独立、适合检索的问题"));
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        schema.set("required", mapper.createArrayNode().add("query"));
        return new ToolDefinition("search_knowledge_base",
                "从用户有权限访问的知识库中检索相关文档片段。查询必须基于用户问题或已返回资料，不要猜测未提及的内容类别。", schema);
    }

    @Override
    public ToolResult execute(String callId, JsonNode arguments, AgentContext context) {
        String query = requiredText(arguments, "query");
        List<SearchResult> results = searchService.searchWithPermission(
                query, context.traceUserId(), RESULT_LIMIT, context.scopeFileMd5s());
        ObjectNode output = mapper.createObjectNode();
        output.put("status", "success");
        output.put("query", query);
        ArrayNode sources = output.putArray("sources");
        for (SearchResult result : results) {
            context.allowSource(result.getFileMd5(), result.getChunkId());
            ObjectNode source = sources.addObject();
            source.put("sourceId", AgentContext.sourceId(result.getFileMd5(), result.getChunkId()));
            source.put("fileMd5", result.getFileMd5());
            source.put("fileName", result.getFileName() == null ? "unknown" : result.getFileName());
            source.put("chunkId", result.getChunkId());
            source.put("content", abbreviate(result.getTextContent(), 1200));
            if (result.getScore() != null) source.put("score", result.getScore());
        }
        return new ToolResult(callId, definition().name(), output, true, results.size());
    }

    private String requiredText(JsonNode arguments, String field) {
        String value = arguments.path(field).asText("").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(field + " 不能为空");
        return abbreviate(value, 500);
    }

    private String abbreviate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
