package com.luky.nexusmind.controller;

import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.repository.FileUploadRepository;
import com.luky.nexusmind.service.DocumentDownloadTicketService;
import com.luky.nexusmind.service.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.InputStreamResource;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentDownloadContentTest {
    @Test
    void invalidOrExpiredTicketNeverOpensStorage() {
        DocumentController controller = new DocumentController();
        DocumentDownloadTicketService tickets = mock(DocumentDownloadTicketService.class);
        DocumentService documents = mock(DocumentService.class);
        FileUploadRepository files = mock(FileUploadRepository.class);
        ReflectionTestUtils.setField(controller, "downloadTickets", tickets);
        ReflectionTestUtils.setField(controller, "documentService", documents);
        ReflectionTestUtils.setField(controller, "fileUploadRepository", files);
        when(tickets.resolve("expired")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, controller.downloadContent("expired").getStatusCode());
        verifyNoInteractions(documents, files);
    }

    @Test
    void validTicketStreamsFileAsAttachment() throws Exception {
        DocumentController controller = new DocumentController();
        DocumentDownloadTicketService tickets = mock(DocumentDownloadTicketService.class);
        DocumentService documents = mock(DocumentService.class);
        FileUploadRepository files = mock(FileUploadRepository.class);
        ReflectionTestUtils.setField(controller, "downloadTickets", tickets);
        ReflectionTestUtils.setField(controller, "documentService", documents);
        ReflectionTestUtils.setField(controller, "fileUploadRepository", files);
        FileUpload file = new FileUpload();
        file.setFileName("报告.pdf");
        when(tickets.resolve("valid")).thenReturn(Optional.of("md5"));
        when(files.findByFileMd5("md5")).thenReturn(Optional.of(file));
        when(documents.openFileStream("报告.pdf")).thenReturn(new ByteArrayInputStream("file".getBytes(StandardCharsets.UTF_8)));
        var response = controller.downloadContent("valid");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("attachment", response.getHeaders().getContentDisposition().getType());
        assertEquals("报告.pdf", response.getHeaders().getContentDisposition().getFilename());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        try (var stream = ((InputStreamResource) response.getBody()).getInputStream()) {
            assertEquals("file", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void deletedFileDoesNotOpenStorage() {
        DocumentController controller = new DocumentController();
        DocumentDownloadTicketService tickets = mock(DocumentDownloadTicketService.class);
        DocumentService documents = mock(DocumentService.class);
        FileUploadRepository files = mock(FileUploadRepository.class);
        ReflectionTestUtils.setField(controller, "downloadTickets", tickets);
        ReflectionTestUtils.setField(controller, "documentService", documents);
        ReflectionTestUtils.setField(controller, "fileUploadRepository", files);
        when(tickets.resolve("valid")).thenReturn(Optional.of("deleted"));
        when(files.findByFileMd5("deleted")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, controller.downloadContent("valid").getStatusCode());
        verifyNoInteractions(documents);
    }
}
