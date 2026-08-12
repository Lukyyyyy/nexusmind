package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.model.KnowledgeGraphStatus;
import com.luky.nexusmind.model.OrganizationTag;
import com.luky.nexusmind.repository.OrganizationTagRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrganizationKnowledgeGraphServiceTest {
    @Test
    void returnsOnlyAccessiblePublishedOrganizationDocumentsAndKeepsEvidence() {
        DocumentService documents = mock(DocumentService.class);
        OrganizationTagRepository organizations = mock(OrganizationTagRepository.class);
        KnowledgeGraphStoreService store = mock(KnowledgeGraphStoreService.class);
        OrganizationKnowledgeGraphService service = new OrganizationKnowledgeGraphService(documents, organizations, store);

        FileUpload published = file(7L, "研发部", false, true, KnowledgeGraphStatus.PUBLISHED);
        FileUpload pending = file(8L, "研发部", false, true, KnowledgeGraphStatus.PENDING_REVIEW);
        FileUpload publicFile = file(9L, "研发部", true, true, KnowledgeGraphStatus.PUBLISHED);
        when(documents.getAccessibleFiles("jack", "", "USER")).thenReturn(List.of(published, pending, publicFile));
        when(organizations.findById("研发部")).thenReturn(Optional.empty());
        when(store.isEnabled()).thenReturn(true);
        when(store.loadOrganizationRelations("研发部", List.of(7L), "订单", "SYSTEM", 301))
                .thenReturn(List.of(new KnowledgeGraphStoreService.OrganizationRelation(
                        "ORG:研发部|SYSTEM|订单系统", "订单系统", "SYSTEM",
                        "ORG:研发部|SERVICE|redis", "Redis", "SERVICE",
                        11L, "依赖", 7L, "abc", "架构说明.pdf", 3,
                        "订单系统依赖 Redis", 0.94
                )));

        OrganizationKnowledgeGraphService.OrganizationGraphResponse response = service.getOrganizationGraph(
                "研发部", "jack", "USER", "订单", "SYSTEM", null, null);

        assertEquals(2, response.nodes().size());
        assertEquals(1, response.edges().size());
        assertEquals("架构说明.pdf", response.edges().get(0).fileName());
        assertEquals("订单系统依赖 Redis", response.edges().get(0).evidenceText());
        assertEquals(1, response.documents().size());
        assertEquals(1, response.stats().documentCount());
        assertTrue(response.neo4jEnabled());
    }

    @Test
    void rejectsOrganizationWithoutAnyAccessibleDocument() {
        DocumentService documents = mock(DocumentService.class);
        OrganizationTagRepository organizations = mock(OrganizationTagRepository.class);
        KnowledgeGraphStoreService store = mock(KnowledgeGraphStoreService.class);
        OrganizationKnowledgeGraphService service = new OrganizationKnowledgeGraphService(documents, organizations, store);
        when(documents.getAccessibleFiles("jack", "", "USER")).thenReturn(List.of());

        assertThrows(CustomException.class, () -> service.getOrganizationGraph(
                "财务部", "jack", "USER", "", "", null, 100));
        verifyNoInteractions(store);
    }

    private FileUpload file(Long id, String orgTag, boolean isPublic, boolean graphEnabled,
                            KnowledgeGraphStatus graphStatus) {
        FileUpload file = new FileUpload();
        file.setId(id);
        file.setFileMd5("md5-" + id);
        file.setFileName("file-" + id + ".pdf");
        file.setUserId("jack");
        file.setOrgTag(orgTag);
        file.setPublic(isPublic);
        file.setGraphEnabled(graphEnabled);
        file.setGraphStatus(graphStatus);
        return file;
    }
}
