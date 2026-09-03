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
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 枚举当前会话问答范围内的知识库文档清单。
 * 语义检索工具无法精确回答“知识库中有哪些文档”类问题，由本工具提供精确清单与统计。
 * 返回结果同时受用户访问权限和会话有效文档集合约束；仅统计已完成解析、可被检索的文档。
 */
@Component
public class DocumentListTool implements AgentTool {
    private static final int DOCUMENT_LIMIT = 100;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DocumentService documentService;
    private final ObjectMapper mapper;

    public DocumentListTool(DocumentService documentService, ObjectMapper mapper) {
        this.documentService = documentService;
        this.mapper = mapper;
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        schema.set("properties", mapper.createObjectNode());
        schema.set("required", mapper.createArrayNode());
        return new ToolDefinition("list_knowledge_documents",
                "列出当前会话问答范围内的知识库文档清单（文件名、所属组织、是否公开、大小、上传时间）。"
                        + "当用户询问知识库中有哪些文档、可以访问哪些文档或文档数量时使用；仅枚举文档清单，不要用于内容检索。"
                        + "清单只包含已完成解析、当前可被检索的文档。", schema);
    }

    @Override
    public ToolResult execute(String callId, JsonNode arguments, AgentContext context) {
        Set<Long> scopeFileIds = Set.copyOf(context.scopeFileIds());
        List<FileUpload> accessible = documentService.getAccessibleFiles(context.traceUserId(), null).stream()
                .filter(file -> scopeFileIds.contains(file.getId()))
                .toList();
        List<FileUpload> ready = accessible.stream()
                .filter(file -> file.getStatus() == 1)
                .sorted(Comparator.comparing(FileUpload::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        long processingCount = accessible.size() - ready.size();

        ObjectNode output = mapper.createObjectNode();
        output.put("status", "success");
        output.put("totalCount", ready.size());
        output.put("processingCount", processingCount);
        boolean truncated = ready.size() > DOCUMENT_LIMIT;
        output.put("returnedCount", Math.min(ready.size(), DOCUMENT_LIMIT));
        output.put("truncated", truncated);
        ArrayNode documents = output.putArray("documents");
        ready.stream().limit(DOCUMENT_LIMIT).forEach(file -> {
            ObjectNode document = documents.addObject();
            document.put("fileName", file.getFileName() == null ? "unknown" : file.getFileName());
            document.put("fileMd5", file.getFileMd5());
            document.put("orgTag", file.getOrgTag() == null ? "" : file.getOrgTag());
            document.put("isPublic", file.isPublic());
            document.put("sizeBytes", file.getTotalSize());
            if (file.getCreatedAt() != null) {
                document.put("uploadedAt", TIME_FORMAT.format(file.getCreatedAt()));
            }
        });
        if (truncated) {
            output.put("note", "文档较多，仅返回最近上传的前 " + DOCUMENT_LIMIT + " 个，完整清单请在知识库页面查看");
        }
        return new ToolResult(callId, definition().name(), output, true, ready.size());
    }
}
