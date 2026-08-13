package com.luky.nexusmind.service;

import com.luky.nexusmind.model.GraphCandidate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeGraphExtractionServiceValidationTest {

    @Test
    void rejectsDocumentRelativeEntityNames() {
        assertTrue(KnowledgeGraphExtractionService.isAmbiguousEntityName("本文模型"));
        assertTrue(KnowledgeGraphExtractionService.isAmbiguousEntityName("本方法"));
        assertTrue(KnowledgeGraphExtractionService.isAmbiguousEntityName("该系统"));
        assertTrue(KnowledgeGraphExtractionService.isAmbiguousEntityName("本文提出的模型"));
        assertTrue(KnowledgeGraphExtractionService.isAmbiguousEntityName("我们的模型"));
        assertTrue(KnowledgeGraphExtractionService.isAmbiguousEntityName("所提出的方法"));
    }

    @Test
    void keepsNamesThatCanStandOutsideTheDocument() {
        assertFalse(KnowledgeGraphExtractionService.isAmbiguousEntityName("基于卷积神经网络的车辆碰撞声识别模型"));
        assertFalse(KnowledgeGraphExtractionService.isAmbiguousEntityName("RetNet"));
        assertFalse(KnowledgeGraphExtractionService.isAmbiguousEntityName("UrbanSound8K数据集"));
    }

    @Test
    void filtersBibliographyMetadataRelations() {
        assertTrue(KnowledgeGraphExtractionService.isLowValueRelation(candidate(
                "基于卷积神经网络的车辆碰撞声识别方法", "DOCUMENT", "引用",
                "Moving vehicle noise classification", "DOCUMENT",
                "Abdul Rahim N, et al. Moving vehicle noise classification[C]//IEEE, 2011: 105-110.")));
        assertTrue(KnowledgeGraphExtractionService.isLowValueRelation(candidate(
                "Sainburg T", "PERSON", "发表", "Finding latent structure", "DOCUMENT",
                "Sainburg T, Thielk M, et al. Finding latent structure[J]. 2020, 16(10): 1-20.")));
    }

    @Test
    void keepsDecisionUsefulDomainRelations() {
        assertFalse(KnowledgeGraphExtractionService.isLowValueRelation(candidate(
                "Bottle2neck残差模块", "COMPONENT", "结合", "log-Mel特征", "TECHNOLOGY",
                "引入Bottle2neck残差模块并结合log-Mel特征提取声音特征")));
        assertFalse(KnowledgeGraphExtractionService.isLowValueRelation(candidate(
                "车辆碰撞声识别模型", "MODEL", "提升", "碰撞声分类准确率", "METRIC",
                "该模型使碰撞声分类准确率提升了10.35%")));
    }

    private GraphCandidate candidate(String subject, String subjectType, String predicate,
                                     String object, String objectType, String evidence) {
        GraphCandidate candidate = new GraphCandidate();
        candidate.setSubjectName(subject);
        candidate.setSubjectType(subjectType);
        candidate.setPredicate(predicate);
        candidate.setObjectName(object);
        candidate.setObjectType(objectType);
        candidate.setEvidenceText(evidence);
        return candidate;
    }
}
