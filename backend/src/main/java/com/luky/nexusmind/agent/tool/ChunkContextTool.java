package com.luky.nexusmind.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luky.nexusmind.agent.AgentContext;
import com.luky.nexusmind.agent.ToolDefinition;
import com.luky.nexusmind.agent.ToolResult;
import com.luky.nexusmind.model.DocumentVector;
import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.repository.DocumentVectorRepository;
import com.luky.nexusmind.service.DocumentService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ChunkContextTool implements AgentTool {
    private final DocumentVectorRepository repository;
    private final DocumentService documentService;
    private final ObjectMapper mapper;

    public ChunkContextTool(DocumentVectorRepository repository, DocumentService documentService, ObjectMapper mapper) {
        this.repository = repository;
        this.documentService = documentService;
        this.mapper = mapper;
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("fileMd5", mapper.createObjectNode().put("type", "string"));
        properties.set("chunkId", mapper.createObjectNode().put("type", "integer").put("minimum", 0));
        properties.set("before", mapper.createObjectNode().put("type", "integer").put("minimum", 0).put("maximum", 2).put("default", 1));
        properties.set("after", mapper.createObjectNode().put("type", "integer").put("minimum", 0).put("maximum", 2).put("default", 1));
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        schema.set("required", mapper.createArrayNode().add("fileMd5").add("chunkId"));
        return new ToolDefinition("get_chunk_context",
                "获取已检索知识片段前后的相邻内容。仅当现有片段上下文不完整时使用。", schema);
    }

    @Override
    public ToolResult execute(String callId, JsonNode arguments, AgentContext context) {
        String fileMd5 = arguments.path("fileMd5").asText("").trim();
        int chunkId = arguments.path("chunkId").asInt(-1);
        if (fileMd5.isEmpty() || chunkId < 0) throw new IllegalArgumentException("fileMd5 和 chunkId 无效");
        if (!context.isSourceAllowed(fileMd5, chunkId)) {
            throw new IllegalArgumentException("只能读取本轮检索结果的相邻切片");
        }
        boolean accessible = documentService.getAccessibleFiles(context.userId(), "").stream()
                .map(FileUpload::getFileMd5).anyMatch(fileMd5::equals);
        if (!accessible) throw new IllegalArgumentException("无权访问该文档");

        int before = Math.max(0, Math.min(2, arguments.path("before").asInt(1)));
        int after = Math.max(0, Math.min(2, arguments.path("after").asInt(1)));
        List<DocumentVector> rows = repository.findByFileMd5AndChunkIdBetweenOrderByChunkIdAscVectorIdAsc(
                fileMd5, Math.max(0, chunkId - before), chunkId + after);
        Map<Integer, DocumentVector> distinct = new LinkedHashMap<>();
        for (DocumentVector row : rows) distinct.putIfAbsent(row.getChunkId(), row);

        ObjectNode output = mapper.createObjectNode();
        output.put("status", "success");
        ArrayNode sources = output.putArray("sources");
        for (DocumentVector row : distinct.values()) {
            context.allowSource(fileMd5, row.getChunkId());
            ObjectNode source = sources.addObject();
            source.put("sourceId", AgentContext.sourceId(fileMd5, row.getChunkId()));
            source.put("fileMd5", fileMd5);
            source.put("chunkId", row.getChunkId());
            String content = row.getTextContent() == null ? "" : row.getTextContent();
            source.put("content", content.length() <= 1200 ? content : content.substring(0, 1200) + "…");
        }
        return new ToolResult(callId, definition().name(), output, true, distinct.size());
    }
}
