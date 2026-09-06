package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.model.KnowledgeGraphStatus;
import com.luky.nexusmind.model.OrganizationTag;
import com.luky.nexusmind.repository.OrganizationTagRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        when(documents.getEffectiveOrganizationTags("jack")).thenReturn(List.of("研发部"));
        when(organizations.findAll()).thenReturn(List.of());
        when(store.isEnabled()).thenReturn(true);
        when(store.loadOrganizationRelations(
                List.of("ORG_INTERNAL:研发部", "ORG:研发部", "PUBLIC"),
                List.of(7L, 9L), "订单", "SYSTEM", 301))
                .thenReturn(List.of(new KnowledgeGraphStoreService.OrganizationRelation(
                        "ORG:研发部|SYSTEM|订单系统", "订单系统", "SYSTEM",
                        "ORG:研发部|SERVICE|redis", "Redis", "SERVICE",
                        11L, "依赖", 7L, "abc", "架构说明.pdf", 3,
                        "订单系统依赖 Redis", 0.94
                )));

        OrganizationKnowledgeGraphService.OrganizationGraphResponse response = service.getOrganizationGraph(
                "ORG_INTERNAL:研发部", "jack", "USER", "订单", "SYSTEM", null, null);

        assertEquals(2, response.nodes().size());
        assertEquals(1, response.communities().size());
        assertTrue(response.nodes().stream().allMatch(node -> node.componentId() != null
                && node.communityId() != null && node.importance() > 0));
        assertEquals(1, response.edges().size());
        assertEquals("架构说明.pdf", response.edges().get(0).fileName());
        assertEquals("订单系统依赖 Redis", response.edges().get(0).evidenceText());
        assertEquals(1, response.edges().get(0).supportCount());
        assertEquals(1, response.edges().get(0).documentCount());
        assertFalse(response.edges().get(0).disputed());
        assertEquals("ASSERTED", response.edges().get(0).relationKind());
        assertFalse(response.edges().get(0).crossDocument());
        assertEquals(0, response.stats().crossDocumentRelationCount());
        assertEquals(1, response.edges().get(0).evidences().size());
        assertEquals(2, response.documents().size());
        assertEquals(1, response.stats().documentCount());
        assertTrue(response.neo4jEnabled());
    }

    @Test
    void aggregatesDuplicateFactsAndMarksDifferentStatementsWithoutOverwritingEvidence() {
        DocumentService documents = mock(DocumentService.class);
        OrganizationTagRepository organizations = mock(OrganizationTagRepository.class);
        KnowledgeGraphStoreService store = mock(KnowledgeGraphStoreService.class);
        OrganizationKnowledgeGraphService service = new OrganizationKnowledgeGraphService(documents, organizations, store);
        FileUpload first = file(7L, "研发部", false, true, KnowledgeGraphStatus.PUBLISHED);
        FileUpload second = file(8L, "研发部", false, true, KnowledgeGraphStatus.PUBLISHED);
        when(documents.getAccessibleFiles("jack", "", "USER")).thenReturn(List.of(first, second));
        when(documents.getEffectiveOrganizationTags("jack")).thenReturn(List.of("研发部"));
        when(organizations.findAll()).thenReturn(List.of());
        when(store.isEnabled()).thenReturn(true);
        when(store.loadOrganizationRelations(anyList(), anyList(), isNull(), isNull(), eq(301))).thenReturn(List.of(
                relation(11L, "Redis", 7L, "架构说明.pdf", "订单系统依赖 Redis", 0.94),
                relation(12L, "Redis", 8L, "部署说明.pdf", "生产环境仍依赖 Redis", 0.91),
                relation(13L, "KeyDB", 8L, "部署说明.pdf", "新版本改为依赖 KeyDB", 0.88)
        ));

        OrganizationKnowledgeGraphService.OrganizationGraphResponse response = service.getOrganizationGraph(
                "ORG_INTERNAL:研发部", "jack", "USER", null, null, null, null);

        assertEquals(2, response.edges().size());
        OrganizationKnowledgeGraphService.GraphEdge redis = response.edges().stream()
                .filter(edge -> edge.evidenceText().contains("Redis")).findFirst().orElseThrow();
        assertEquals(2, redis.supportCount());
        assertEquals(2, redis.documentCount());
        assertTrue(redis.disputed());
        assertEquals(2, redis.evidences().size());
        assertEquals("ASSERTED", redis.relationKind());
        assertTrue(redis.crossDocument());
        assertEquals(1, response.stats().crossDocumentRelationCount());
        assertTrue(response.edges().stream().allMatch(OrganizationKnowledgeGraphService.GraphEdge::disputed));
    }


    @Test
    void limitsFactsWithoutDroppingEvidenceRows() {
        DocumentService documents = mock(DocumentService.class);
        OrganizationTagRepository organizations = mock(OrganizationTagRepository.class);
        KnowledgeGraphStoreService store = mock(KnowledgeGraphStoreService.class);
        OrganizationKnowledgeGraphService service = new OrganizationKnowledgeGraphService(documents, organizations, store);
        FileUpload first = file(7L, "研发部", false, true, KnowledgeGraphStatus.PUBLISHED);
        FileUpload second = file(8L, "研发部", false, true, KnowledgeGraphStatus.PUBLISHED);
        when(documents.getAccessibleFiles("jack", "", "USER")).thenReturn(List.of(first, second));
        when(documents.getEffectiveOrganizationTags("jack")).thenReturn(List.of("研发部"));
        when(organizations.findAll()).thenReturn(List.of());
        when(store.isEnabled()).thenReturn(true);
        when(store.loadOrganizationRelations(anyList(), anyList(), isNull(), isNull(), eq(2))).thenReturn(List.of(
                relation(11L, "Redis", 7L, "架构说明.pdf", "订单系统依赖 Redis", 0.94),
                relation(12L, "Redis", 8L, "部署说明.pdf", "生产环境仍依赖 Redis", 0.91),
                relation(13L, "KeyDB", 8L, "部署说明.pdf", "新版本改为依赖 KeyDB", 0.88)
        ));

        OrganizationKnowledgeGraphService.OrganizationGraphResponse response = service.getOrganizationGraph(
                "ORG_INTERNAL:研发部", "jack", "USER", null, null, null, 1);

        assertTrue(response.truncated());
        assertEquals(1, response.edges().size());
        assertEquals(2, response.edges().get(0).evidences().size());
        assertEquals(2, response.edges().get(0).documentCount());
    }

    @Test
    void rejectsOrganizationWithoutAnyAccessibleDocument() {
        DocumentService documents = mock(DocumentService.class);
        OrganizationTagRepository organizations = mock(OrganizationTagRepository.class);
        KnowledgeGraphStoreService store = mock(KnowledgeGraphStoreService.class);
        OrganizationKnowledgeGraphService service = new OrganizationKnowledgeGraphService(documents, organizations, store);
        when(documents.getAccessibleFiles("jack", "", "USER")).thenReturn(List.of());
        when(documents.getEffectiveOrganizationTags("jack")).thenReturn(List.of());
        when(organizations.findAll()).thenReturn(List.of());

        assertThrows(CustomException.class, () -> service.getOrganizationGraph(
                "财务部", "jack", "USER", "", "", null, 100));
        verifyNoInteractions(store);
    }

    @Test
    void refreshesInternalGraphAccessAfterMembershipChanges() {
        DocumentService documents = mock(DocumentService.class);
        OrganizationTagRepository organizations = mock(OrganizationTagRepository.class);
        KnowledgeGraphStoreService store = mock(KnowledgeGraphStoreService.class);
        OrganizationKnowledgeGraphService service = new OrganizationKnowledgeGraphService(documents, organizations, store);
        FileUpload publicFile = file(9L, "研发部", true, true, KnowledgeGraphStatus.PUBLISHED);
        FileUpload internalFile = file(10L, "研发部", false, true, KnowledgeGraphStatus.PUBLISHED);
        when(documents.getAccessibleFiles("jack", "", "USER"))
                .thenReturn(List.of(publicFile, internalFile));
        when(documents.getEffectiveOrganizationTags("jack"))
                .thenReturn(List.of(), List.of("研发部"));
        when(organizations.findAll()).thenReturn(List.of());

        List<OrganizationKnowledgeGraphService.OrganizationOption> before = service.listOrganizations("jack", "USER");
        List<OrganizationKnowledgeGraphService.OrganizationOption> after = service.listOrganizations("jack", "USER");

        assertEquals(List.of("ORG_PUBLIC:研发部"), before.stream().map(
                OrganizationKnowledgeGraphService.OrganizationOption::scopeId).toList());
        assertEquals(List.of("ORG_PUBLIC:研发部", "ORG_INTERNAL:研发部"), after.stream().map(
                OrganizationKnowledgeGraphService.OrganizationOption::scopeId).toList());
        verify(documents, times(2)).getEffectiveOrganizationTags("jack");
    }

    @Test
    void exposesPrivateSpaceAsItsOwnGraph() {
        DocumentService documents = mock(DocumentService.class);
        OrganizationTagRepository organizations = mock(OrganizationTagRepository.class);
        KnowledgeGraphStoreService store = mock(KnowledgeGraphStoreService.class);
        OrganizationKnowledgeGraphService service = new OrganizationKnowledgeGraphService(documents, organizations, store);
        FileUpload privateFile = file(11L, "PRIVATE_jack", false, true, KnowledgeGraphStatus.PUBLISHED);
        when(documents.getAccessibleFiles("jack", "", "USER")).thenReturn(List.of(privateFile));
        when(documents.getEffectiveOrganizationTags("jack")).thenReturn(List.of("PRIVATE_jack"));
        when(organizations.findAll()).thenReturn(List.of());

        List<OrganizationKnowledgeGraphService.OrganizationOption> options = service.listOrganizations("jack", "USER");

        assertEquals(1, options.size());
        assertEquals("PRIVATE:jack", options.get(0).scopeId());
        assertEquals(OrganizationKnowledgeGraphService.ScopeType.PRIVATE, options.get(0).scopeType());
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

    private KnowledgeGraphStoreService.OrganizationRelation relation(Long candidateId, String targetName,
                                                                      Long fileId, String fileName,
                                                                      String evidence, double confidence) {
        return new KnowledgeGraphStoreService.OrganizationRelation(
                "ORG_INTERNAL:研发部|SYSTEM|订单系统", "订单系统", "SYSTEM",
                "ORG_INTERNAL:研发部|SERVICE|" + targetName.toLowerCase(), targetName, "SERVICE",
                candidateId, "依赖", fileId, "md5-" + fileId, fileName, 3, evidence, confidence);
    }
}
