package com.luky.nexusmind.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luky.nexusmind.agent.AgentContext;
import com.luky.nexusmind.agent.ToolDefinition;
import com.luky.nexusmind.agent.ToolResult;
import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.service.DocumentService;
import com.luky.nexusmind.service.KnowledgeGraphStoreService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class KnowledgeGraphSearchTool implements AgentTool {
    private static final int RESULT_LIMIT = 10;

    private final KnowledgeGraphStoreService graphStore;
    private final DocumentService documentService;
    private final ObjectMapper mapper;

    public KnowledgeGraphSearchTool(KnowledgeGraphStoreService graphStore, DocumentService documentService,
                                    ObjectMapper mapper) {
        this.graphStore = graphStore;
        this.documentService = documentService;
        this.mapper = mapper;
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("query", mapper.createObjectNode().put("type", "string")
                .put("description", "需要查询的实体及关系"));
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        schema.set("required", mapper.createArrayNode().add("query"));
        return new ToolDefinition("search_knowledge_graph",
                "查询实体之间的关系、依赖、归属以及跨文档关联路径。", schema);
    }

    @Override
    public ToolResult execute(String callId, JsonNode arguments, AgentContext context) {
        String query = arguments.path("query").asText("").trim();
        if (query.isEmpty()) throw new IllegalArgumentException("query 不能为空");
        if (!graphStore.isEnabled()) return unavailable(callId);
        List<FileUpload> accessible = documentService.getAccessibleFiles(context.userId(), "");
        List<KnowledgeGraphStoreService.GraphPath> paths = graphStore.search(
                query.substring(0, Math.min(query.length(), 500)),
                accessible.stream().map(FileUpload::getId).toList(), RESULT_LIMIT);
        ObjectNode output = mapper.createObjectNode();
        output.put("status", "success");
        output.put("query", query);
        ArrayNode pathNodes = output.putArray("paths");
        for (KnowledgeGraphStoreService.GraphPath path : paths) {
            ObjectNode pathNode = pathNodes.addObject();
            pathNode.set("nodes", mapper.valueToTree(path.nodes()));
            ArrayNode relations = pathNode.putArray("relations");
            for (Map<String, Object> relation : path.relations()) {
                ObjectNode relationNode = mapper.valueToTree(relation);
                String md5 = String.valueOf(relation.getOrDefault("fileMd5", ""));
                Integer chunkId = relation.get("chunkId") instanceof Number number ? number.intValue() : null;
                if (!md5.isBlank() && chunkId != null) {
                    context.allowSource(md5, chunkId);
                    relationNode.put("sourceId", AgentContext.sourceId(md5, chunkId));
                }
                relations.add(relationNode);
            }
        }
        return new ToolResult(callId, definition().name(), output, true, paths.size());
    }

    private ToolResult unavailable(String callId) {
        ObjectNode output = mapper.createObjectNode();
        output.put("status", "error");
        output.put("code", "GRAPH_DISABLED");
        output.put("message", "知识图谱当前不可用，请改用知识库检索");
        return new ToolResult(callId, definition().name(), output, false, 0);
    }
}
