package com.luky.nexusmind.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luky.nexusmind.client.KnowledgeGraphExtractionClient;
import com.luky.nexusmind.model.*;
import com.luky.nexusmind.repository.*;

import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.*;
import java.util.concurrent.atomic.*;

class GraphExtractionEngineTest {
    FileUpload file;
    GraphExtractionRun snapshot;
    FileUploadRepository files = mock(FileUploadRepository.class);
    GraphExtractionRunRepository runs = mock(GraphExtractionRunRepository.class);
    DocumentVectorRepository vectors = mock(DocumentVectorRepository.class);
    GraphCandidateRepository candidates = mock(GraphCandidateRepository.class);
    UserRepository users = mock(UserRepository.class);
    GraphPromptTemplateService templates = mock(GraphPromptTemplateService.class);
    KnowledgeGraphExtractionClient client = mock(KnowledgeGraphExtractionClient.class);
    ModelConfigService configs = mock(ModelConfigService.class);
    GraphModelScheduler scheduler;
    GraphExtractionEngine engine;
    List<GraphCandidate> saved = new ArrayList<>();
    KnowledgeGraphExtractionService factory;

    @BeforeEach
    void setup() {
        file = new FileUpload();
        file.setId(1L);
        file.setFileMd5("md5");
        file.setUserId("owner");
        file.setFileName("文档");
        file.setGraphEnabled(true);
        file.setGraphRunToken("run-1");
        file.setGraphBatchChars(10);
        when(files.findByFileMd5AndUserId("md5", "owner")).thenAnswer(i -> Optional.of(file));
        when(files.findById(1L)).thenAnswer(i -> Optional.of(file));
        when(files.lockById(1L)).thenAnswer(i -> Optional.of(file));
        when(runs.findById(1L)).thenAnswer(i -> Optional.ofNullable(snapshot));
        when(runs.save(any()))
                .thenAnswer(
                        i -> {
                            snapshot = i.getArgument(0);
                            return snapshot;
                        });
        when(templates.resolve(any()))
                .thenReturn(new GraphPromptTemplateService.ResolvedTemplate(null, "test", ""));
        when(configs.resolveGraphExtractionConfig(anyString()))
                .thenReturn(GraphModelSchedulerTest.config(16384, 2));
        scheduler = new GraphModelScheduler(configs);
        var tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        engine =
                new GraphExtractionEngine(
                        files,
                        runs,
                        vectors,
                        candidates,
                        users,
                        templates,
                        client,
                        scheduler,
                        new ObjectMapper(),
                        tx);
        factory = new KnowledgeGraphExtractionService(engine);
        var a = new DocumentVector();
        a.setChunkId(1);
        a.setTextContent("甲模型使用乙方法。");
        var b = new DocumentVector();
        b.setChunkId(2);
        b.setTextContent("丙模型使用丁方法。");
        when(vectors.findByFileMd5("md5")).thenReturn(List.of(a, b));
        when(candidates.findByFileUploadIdOrderByEvidenceChunkIdAscIdAsc(1L))
                .thenAnswer(i -> new ArrayList<>(saved));
        when(candidates.save(any()))
                .thenAnswer(
                        i -> {
                            GraphCandidate c = i.getArgument(0);
                            c.setId((long) saved.size() + 1);
                            saved.add(c);
                            return c;
                        });
        when(client.dictionaryOnce(any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(List.of());
    }

    @AfterEach
    void close() {
        scheduler.close();
    }

    private KnowledgeGraphExtractionClient.ExtractionResult relation(int id) {
        String a = id == 1 ? "甲模型" : "丙模型", b = id == 1 ? "乙方法" : "丁方法";
        return new KnowledgeGraphExtractionClient.ExtractionResult(
                "test",
                List.of(
                        new KnowledgeGraphExtractionClient.ExtractedRelation(
                                new KnowledgeGraphExtractionClient.EntityValue(a, "MODEL"),
                                "使用",
                                new KnowledgeGraphExtractionClient.EntityValue(b, "METHOD"),
                                id,
                                a + "使用" + b + "。",
                                .9,
                                .9)));
    }

    private int batch(String input) {
        return input.contains("[CHUNK 1 start=") ? 1 : 2;
    }

    @Test
    void keepsSuccessAndRetriesOnlyFailedRanges() {
        AtomicBoolean failing = new AtomicBoolean(true);
        AtomicInteger first = new AtomicInteger(), second = new AtomicInteger();
        when(client.relationsOnce(any(), anyString(), anyString()))
                .thenAnswer(
                        i -> {
                            int n = batch(i.getArgument(1));
                            if (n == 1) {
                                first.incrementAndGet();
                                return relation(1);
                            }
                            second.incrementAndGet();
                            if (failing.get()) throw new IllegalStateException("unavailable");
                            return relation(2);
                        });
        engine.run("md5", "owner", false, factory);
        assertEquals(1, saved.size());
        assertEquals(1, first.get());
        assertEquals(2, second.get());
        assertEquals(1, engine.progress(1L).relations().failed());
        assertEquals(KnowledgeGraphStatus.PENDING_REVIEW, file.getGraphStatus());
        saved.get(0).setSelected(false);
        failing.set(false);
        file.setGraphRunToken("run-2");
        engine.run("md5", "owner", true, factory);
        assertEquals(2, saved.size());
        assertFalse(saved.get(0).isSelected());
        assertEquals(1, first.get());
        assertEquals(3, second.get());
        assertFalse(engine.progress(1L).canRetry());
        assertEquals(0, saved.get(0).getEvidenceStart());
    }

    @Test
    void disabledGenerationCannotWriteLateResults() {
        when(client.relationsOnce(any(), anyString(), anyString()))
                .thenAnswer(
                        i -> {
                            file.setGraphEnabled(false);
                            file.setGraphRunToken("disabled");
                            return relation(1);
                        });
        engine.run("md5", "owner", false, factory);
        assertTrue(saved.isEmpty());
    }

    @Test
    void rejectsEvidenceOnlyPresentInNeighborContext() {
        when(client.relationsOnce(any(), anyString(), anyString())).thenReturn(relation(1));
        engine.run("md5", "owner", false, factory);
        assertEquals(1, saved.size());
    }

    @Test
    void restartMarksUnfinishedWorkAsRetryable() {
        when(client.relationsOnce(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("offline"));
        engine.run("md5", "owner", false, factory);
        file.setGraphStatus(KnowledgeGraphStatus.EXTRACTING);
        when(runs.findAll()).thenReturn(List.of(snapshot));
        engine.recoverInterruptedRuns();
        assertEquals(KnowledgeGraphStatus.FAILED, file.getGraphStatus());
        assertTrue(engine.progress(1L).canRetry());
    }

    @Test
    void truncationHasExactlyOneSplitRetryRoundAndStableProgressTotals() {
        AtomicInteger calls = new AtomicInteger();
        when(client.relationsOnce(any(), anyString(), anyString()))
                .thenAnswer(
                        i -> {
                            if (batch(i.getArgument(1)) == 1) {
                                calls.incrementAndGet();
                                throw new KnowledgeGraphExtractionClient.OutputTruncatedException();
                            }
                            return relation(2);
                        });
        engine.run("md5", "owner", false, factory);
        assertEquals(
                3, calls.get()); // one original request + two child requests, no recursive retry
        assertEquals(2, engine.progress(1L).relations().total());
        assertEquals(2, engine.progress(1L).relations().ended());
        assertEquals(1, engine.progress(1L).relations().failed());
        assertEquals(2, engine.progress(1L).failures().get(0).ranges().size());
        assertEquals(1, saved.size());
    }

    @Test
    void invalidDictionaryEntryIsDiscardedWithoutFailingWholeBatch() {
        when(client.dictionaryOnce(any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(
                        List.of(
                                new KnowledgeGraphExtractionClient.DictionaryEntry(
                                        "不存在", "MODEL", "虚构模型", 1, 0, 3, "虚构证据")));
        when(client.relationsOnce(any(), anyString(), anyString()))
                .thenAnswer(i -> relation(batch(i.getArgument(1))));
        engine.run("md5", "owner", false, factory);
        assertEquals(0, engine.progress(1L).dictionary().failed());
        assertEquals(2, engine.progress(1L).dictionary().succeeded());
        assertEquals(2, saved.size());
        assertNull(file.getGraphError());
    }

    @Test
    void localPronounsResolveByPositionRatherThanWholeChunk() {
        var entries =
                List.of(
                        new KnowledgeGraphExtractionClient.DictionaryEntry(
                                "该模型", "MODEL", "甲模型", 1, 0, 3, "该模型使用乙方法"),
                        new KnowledgeGraphExtractionClient.DictionaryEntry(
                                "该模型", "MODEL", "丙模型", 1, 9, 12, "该模型使用丁方法"));
        var part = new GraphBatchPlan.Part(1, 0, "该模型使用乙方法。该模型使用丁方法。");
        Map<String, KnowledgeGraphExtractionClient.EntityResolution> mapping =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        engine, "scopedMappings", entries, part, "该模型使用丁方法。");
        assertEquals("丙模型", mapping.get("该模型").canonicalName());
    }

    @Test
    void repairsOnlyUniquelyLocatableDictionaryOffsets() {
        var part = new GraphBatchPlan.Part(7, 100, "声音事件检测简称SED。");
        var batch = new GraphBatchPlan.Batch(0, List.of(part), "", "");
        var entry =
                new KnowledgeGraphExtractionClient.DictionaryEntry(
                        "SED", "TASK", "声音事件检测", 7, 0, 3, "声音事件检测简称SED。");
        KnowledgeGraphExtractionClient.DictionaryEntry fixed =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        engine, "locate", entry, batch);
        assertEquals(108, fixed.start());
        assertEquals(111, fixed.end());
        Boolean valid =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        engine, "valid", fixed, batch, "文档");
        assertTrue(valid);
    }

    @Test
    void computesMissingDictionaryOffsetsFromExactEvidence() {
        var part = new GraphBatchPlan.Part(7, 100, "声音事件检测简称SED。");
        var batch = new GraphBatchPlan.Batch(0, List.of(part), "", "");
        var entry =
                new KnowledgeGraphExtractionClient.DictionaryEntry(
                        "SED", "TASK", "声音事件检测", 7, null, null, "声音事件检测简称SED");
        KnowledgeGraphExtractionClient.DictionaryEntry fixed =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        engine, "locate", entry, batch);
        assertEquals(108, fixed.start());
        assertEquals(111, fixed.end());
        Boolean valid =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        engine, "valid", fixed, batch, "文档");
        assertTrue(valid);
    }
}
