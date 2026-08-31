package com.luky.nexusmind.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.agent.tool.DocumentListTool;
import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.service.DocumentService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentListToolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DocumentService documentService = mock(DocumentService.class);
    private final DocumentListTool tool = new DocumentListTool(documentService, mapper);
    private final AgentContext context = new AgentContext("alice", 1L, "ws-1", "42");

    private FileUpload file(String fileName, String fileMd5, String orgTag, boolean isPublic,
                            int status, LocalDateTime createdAt) {
        FileUpload file = new FileUpload();
        file.setFileName(fileName);
        file.setFileMd5(fileMd5);
        file.setOrgTag(orgTag);
        file.setPublic(isPublic);
        file.setStatus(status);
        file.setTotalSize(1024);
        file.setCreatedAt(createdAt);
        return file;
    }

    @Test
    void listsOnlyReadyDocumentsWithMetadataAndProcessingCount() {
        LocalDateTime newer = LocalDateTime.of(2026, 8, 30, 10, 0);
        LocalDateTime older = LocalDateTime.of(2026, 8, 1, 9, 30);
        when(documentService.getAccessibleFiles("42", null)).thenReturn(List.of(
                file("新文档.pdf", "a".repeat(32), "研发部", true, 1, newer),
                file("处理中.pdf", "b".repeat(32), "研发部", false, 0, newer),
                file("旧文档.docx", "c".repeat(32), "default", false, 1, older)));

        ToolResult result = tool.execute("call-1", mapper.createObjectNode(), context);
        JsonNode output = result.content();

        assertTrue(result.success());
        assertEquals(2, result.resultCount());
        assertEquals(2, output.path("totalCount").asInt());
        assertEquals(1, output.path("processingCount").asInt());
        assertFalse(output.path("truncated").asBoolean());
        JsonNode documents = output.path("documents");
        assertEquals(2, documents.size());
        assertEquals("新文档.pdf", documents.get(0).path("fileName").asText());
        assertEquals("研发部", documents.get(0).path("orgTag").asText());
        assertTrue(documents.get(0).path("isPublic").asBoolean());
        assertEquals("2026-08-30 10:00", documents.get(0).path("uploadedAt").asText());
        assertEquals("旧文档.docx", documents.get(1).path("fileName").asText());
        verify(documentService).getAccessibleFiles("42", null);
    }

    @Test
    void capsDocumentListAndMarksTruncation() {
        var files = new java.util.ArrayList<FileUpload>();
        for (int i = 0; i < 130; i++) {
            files.add(file("文档" + i + ".pdf", String.format("%032x", i), "研发部", false, 1,
                    LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(i)));
        }
        when(documentService.getAccessibleFiles("42", null)).thenReturn(files);

        ToolResult result = tool.execute("call-2", mapper.createObjectNode(), context);

        assertEquals(130, result.resultCount());
        JsonNode output = result.content();
        assertEquals(130, output.path("totalCount").asInt());
        assertEquals(100, output.path("returnedCount").asInt());
        assertTrue(output.path("truncated").asBoolean());
        assertEquals(100, output.path("documents").size());
        assertFalse(output.path("note").asText("").isEmpty());
    }

    @Test
    void definitionRequiresNoArgumentsAndExplainsUsage() {
        ToolDefinition definition = tool.definition();

        assertEquals("list_knowledge_documents", definition.name());
        assertEquals("object", definition.parameters().path("type").asText());
        assertTrue(definition.parameters().path("required").isEmpty());
        assertTrue(definition.description().contains("清单"));
    }
}
