package com.luky.nexusmind.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.*;
import io.minio.messages.Item;
import io.minio.messages.DeleteError;
import java.util.ArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParsedAssetServiceTest {
    private final String md5 = "93b6bc30de63fcdcc246dbbe6f14d6d4";
    @Test
    void savesImagesRewritesMarkdownAndHtmlAndDeletesOnlyDocumentPrefix() throws Exception {
        MinioClient minio = mock(MinioClient.class);
        ParsedAssetService service = new ParsedAssetService(minio);
        ReflectionTestUtils.setField(service, "bucket", "uploads");
        var response = new ObjectMapper().readTree("{\"results\":{\"doc\":{\"images\":{\"a.jpg\":\"data:image/jpeg;base64,aGVsbG8=\"}}}}");
        String text = service.persist(md5, "![](images/a.jpg) <img src=\"images/a.jpg\">", response);
        assertFalse(text.contains("images/a.jpg"));
        assertTrue(text.contains("/api/v1/documents/" + md5 + "/assets/"));
        var put = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minio).putObject(put.capture());
        assertTrue(put.getValue().object().startsWith("parsed-assets/" + md5 + "/"));
        Item item = mock(Item.class);
        when(item.objectName()).thenReturn(put.getValue().object());
        when(minio.listObjects(any())).thenReturn(List.of(new Result<>(item)));
        when(minio.removeObjects(any())).thenReturn(List.of());
        service.delete(md5);
        var listing = ArgumentCaptor.forClass(ListObjectsArgs.class);
        verify(minio).listObjects(listing.capture());
        assertEquals("parsed-assets/" + md5 + "/", listing.getValue().prefix());
        verify(minio).removeObjects(any());
        verify(minio, never()).removeObject(any());
    }

    @Test
    void batchesLargeDocumentsAndConsumesLazyResults() throws Exception {
        MinioClient minio = mock(MinioClient.class);
        ParsedAssetService service = new ParsedAssetService(minio);
        ReflectionTestUtils.setField(service, "bucket", "uploads");
        var items = IntStream.range(0, 1001).mapToObj(i -> {
            Item item = mock(Item.class);
            when(item.objectName()).thenReturn("parsed-assets/" + md5 + "/" + i + ".jpg");
            return new Result<>(item);
        }).toList();
        when(minio.listObjects(any())).thenReturn(items);
        List<Integer> sizes = new ArrayList<>();
        when(minio.removeObjects(any())).thenAnswer(invocation -> {
            RemoveObjectsArgs args = invocation.getArgument(0);
            int size = 0;
            for (var ignored : args.objects()) size++;
            sizes.add(size);
            return List.of();
        });
        service.delete(md5);
        assertEquals(List.of(1000, 1), sizes);
        verify(minio, never()).removeObject(any());
    }

    @Test
    void reportsPerObjectErrorsAndListingErrors() throws Exception {
        MinioClient minio = mock(MinioClient.class);
        ParsedAssetService service = new ParsedAssetService(minio);
        ReflectionTestUtils.setField(service, "bucket", "uploads");
        Item item = mock(Item.class);
        when(item.objectName()).thenReturn("parsed-assets/" + md5 + "/a.jpg");
        when(minio.listObjects(any())).thenReturn(List.of(new Result<>(item)));
        DeleteError failure = mock(DeleteError.class);
        when(failure.code()).thenReturn("AccessDenied");
        when(minio.removeObjects(any())).thenReturn(List.of(new Result<>(failure)));
        assertThrows(java.io.IOException.class, () -> service.delete(md5));
        when(minio.listObjects(any())).thenReturn(List.of(new Result<Item>(new java.io.IOException("listing failed"))));
        assertThrows(java.io.IOException.class, () -> service.delete(md5));
    }

    @Test
    void rejectsTraversalAndActiveImageFormats() throws Exception {
        MinioClient minio = mock(MinioClient.class);
        ParsedAssetService service = new ParsedAssetService(minio);
        assertThrows(IllegalArgumentException.class, () -> service.open(md5, "../../secret"));
        var response = new ObjectMapper().readTree("{\"images\":{\"x.svg\":\"data:image/svg+xml;base64,PHN2Zz4=\"}}");
        assertThrows(java.io.IOException.class, () -> service.persist(md5, "![](images/x.svg)", response));
        verifyNoInteractions(minio);
    }
}
