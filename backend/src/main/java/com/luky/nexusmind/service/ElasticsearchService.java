package com.luky.nexusmind.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.luky.nexusmind.entity.EsDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.HashMap;
import com.luky.nexusmind.model.DocumentVector;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Refresh;

// Elasticsearch操作封装服务
@Service
public class ElasticsearchService {

    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchService.class);

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private AiTraceService aiTraceService;

    /**
     * 批量索引文档到Elasticsearch中
     * 通过接收一个EsDocument对象列表，将这些文档批量索引到名为"knowledge_base"的索引中
     * 使用Elasticsearch的Bulk API来执行批量索引操作，以提高索引效率
     *
     * @param documents 文档列表，每个文档都将被索引到Elasticsearch中
     */
    public void bulkIndex(List<EsDocument> documents) {
        String fileMd5 = documents == null || documents.isEmpty() ? null : documents.get(0).getFileMd5();
        String userId = documents == null || documents.isEmpty() ? null : documents.get(0).getUserId();
        AiTraceService.TraceSpan span = aiTraceService.startFileSpan("file.elasticsearch.bulk_index", userId, fileMd5, null)
                .attribute("db.system", "elasticsearch")
                .attribute("db.operation", "bulk_index")
                .attribute("db.elasticsearch.index", "knowledge_base")
                .attribute("nexusmind.elasticsearch.document.count", documents != null ? documents.size() : 0);
        try {
            logger.info("开始批量索引文档到Elasticsearch，文档数量: {}", documents.size());
            
            // 将文档列表转换为批量操作列表，每个文档都对应一个索引操作
            List<BulkOperation> bulkOperations = documents.stream()
                    .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                            .index("knowledge_base") // 指定索引名称
                            .id(doc.getId()) // 使用文档的ID作为Elasticsearch中的文档ID
                            .document(doc) // 将文档对象作为数据源
                    )))
                    .toList();

            // 创建BulkRequest对象，并将批量操作列表添加到请求中
            BulkRequest request = BulkRequest.of(b -> b.operations(bulkOperations).refresh(Refresh.WaitFor));
            
            // 执行批量索引操作
            BulkResponse response = esClient.bulk(request);
            
            // 检查响应结果
            if (response.errors()) {
                logger.error("批量索引过程中发生错误:");
                long failedCount = response.items().stream().filter(item -> item.error() != null).count();
                span.attribute("nexusmind.elasticsearch.failed_count", failedCount);
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        logger.error("文档索引失败 - ID: {}, 错误: {}", item.id(), item.error().reason());
                    }
                }
                String reason = response.items().stream().filter(item -> item.error() != null)
                        .map(item -> item.error().type() + ": " + item.error().reason()).findFirst().orElse("未知错误");
                throw new RuntimeException("批量索引部分失败: " + reason);
            } else {
                span.attribute("nexusmind.elasticsearch.status", "success");
                logger.info("批量索引成功完成，文档数量: {}", documents.size());
            }
        } catch (Exception e) {
            span.error(e);
            span.attribute("nexusmind.elasticsearch.status", "failed");
            logger.error("批量索引失败，文档数量: {}", documents.size(), e);
            // 如果发生异常，抛出运行时异常，表明批量索引失败
            throw new RuntimeException("批量索引失败", e);
        } finally {
            span.end();
            span.close();
        }
    }

    /**
     * 根据file_md5删除文档
     * @param fileMd5 文件指纹
     */
    public void deleteByFileMd5(String fileMd5) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index("knowledge_base")
                    .query(q -> q.term(t -> t.field("fileMd5").value(fileMd5)))
                    .refresh(true)
            );
            var response = esClient.deleteByQuery(request);
            if (response.timedOut() || !response.failures().isEmpty() || response.versionConflicts() > 0) {
                throw new IllegalStateException("搜索索引尚未清理完成");
            }
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            if (e.status() == 404 && "index_not_found_exception".equals(e.error().type())) return;
            throw new RuntimeException("清理搜索索引失败", e);
        } catch (Exception e) {
            throw new RuntimeException("删除文档失败", e);
        }
    }

    /** 数量只是初筛；逐批核对切片身份、内容、模型及权限，避免旧索引被误认作成功。 */
    public boolean hasCompleteIndex(String fileMd5, List<DocumentVector> chunks, String modelName) {
        if (chunks.isEmpty()) return false;
        try {
            for (int start = 0; start < chunks.size(); start += 128) {
                var batch = chunks.subList(start, Math.min(start + 128, chunks.size()));
                var expected = new HashMap<Integer, DocumentVector>();
                for (var chunk : batch) {
                    if (expected.put(chunk.getChunkId(), chunk) != null) return false;
                }
                var ids = batch.stream().map(c -> FieldValue.of(c.getChunkId().longValue())).toList();
                var request = SearchRequest.of(r -> r.index("knowledge_base").size(batch.size())
                        .query(q -> q.bool(b -> b
                                .filter(f -> f.term(t -> t.field("fileMd5").value(fileMd5)))
                                .filter(f -> f.terms(t -> t.field("chunkId").terms(v -> v.value(ids)))))));
                var response = esClient.search(request, EsDocument.class);
                if (response.timedOut() || response.shards().failed().intValue() > 0
                        || response.hits().hits().size() != batch.size()) return false;
                for (var hit : response.hits().hits()) {
                    EsDocument doc = hit.source();
                    if (doc == null) return false;
                    DocumentVector chunk = expected.remove(doc.getChunkId());
                    if (chunk == null || !matchesChunk(doc, chunk, modelName)) return false;
                }
                if (!expected.isEmpty()) return false;
            }
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("校验文件索引失败", e);
        }
    }

    static boolean matchesChunk(EsDocument doc, DocumentVector chunk, String modelName) {
        return Objects.equals(doc.getFileMd5(), chunk.getFileMd5())
                && Objects.equals(doc.getTextContent(), chunk.getTextContent())
                && Objects.equals(doc.getModelVersion(), modelName)
                && Objects.equals(doc.getUserId(), chunk.getUserId())
                && Objects.equals(doc.getOrgTag(), chunk.getOrgTag())
                && doc.isPublic() == chunk.isPublic()
                && doc.getVector() != null && doc.getVector().length > 0;
    }

    /**
     * 统计指定文件已经写入 Elasticsearch 的文档数量。
     *
     * @param fileMd5 文件指纹
     * @return ES 中对应文件的文档数量
     */
    public long countByFileMd5(String fileMd5) {
        try {
            CountResponse response = esClient.count(c -> c
                    .index("knowledge_base")
                    .query(q -> q.term(t -> t.field("fileMd5").value(fileMd5)))
            );
            return response.count();
        } catch (Exception e) {
            logger.error("统计ES文档数量失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("统计ES文档数量失败", e);
        }
    }
}
