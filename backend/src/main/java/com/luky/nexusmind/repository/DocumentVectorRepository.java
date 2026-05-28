package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.DocumentVector;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DocumentVectorRepository extends JpaRepository<DocumentVector, Long> {
    List<DocumentVector> findByFileMd5(String fileMd5); // 查询某文件的所有分块

    @Query(
            value = """
                    SELECT dv.* FROM document_vectors dv
                    INNER JOIN (
                        SELECT MIN(vector_id) AS vector_id
                        FROM document_vectors
                        WHERE file_md5 = :fileMd5
                        GROUP BY chunk_id
                    ) first_chunk ON dv.vector_id = first_chunk.vector_id
                    ORDER BY dv.chunk_id ASC
                    """,
            countQuery = "SELECT COUNT(DISTINCT chunk_id) FROM document_vectors WHERE file_md5 = :fileMd5",
            nativeQuery = true
    )
    Page<DocumentVector> findDistinctChunksByFileMd5(@Param("fileMd5") String fileMd5, Pageable pageable);

    @Query(
            value = """
                    SELECT dv.* FROM document_vectors dv
                    INNER JOIN (
                        SELECT MIN(vector_id) AS vector_id
                        FROM document_vectors
                        WHERE file_md5 = :fileMd5 AND text_content LIKE CONCAT('%', :keyword, '%')
                        GROUP BY chunk_id
                    ) first_chunk ON dv.vector_id = first_chunk.vector_id
                    ORDER BY dv.chunk_id ASC
                    """,
            countQuery = "SELECT COUNT(DISTINCT chunk_id) FROM document_vectors WHERE file_md5 = :fileMd5 AND text_content LIKE CONCAT('%', :keyword, '%')",
            nativeQuery = true
    )
    Page<DocumentVector> findDistinctChunksByFileMd5AndKeyword(@Param("fileMd5") String fileMd5,
                                                               @Param("keyword") String keyword,
                                                               Pageable pageable);

    List<DocumentVector> findByFileMd5AndChunkIdOrderByVectorIdAsc(String fileMd5, Integer chunkId);

    long countByFileMd5(String fileMd5);

    @Query(value = "SELECT COUNT(DISTINCT chunk_id) FROM document_vectors WHERE file_md5 = :fileMd5", nativeQuery = true)
    long countDistinctChunksByFileMd5(@Param("fileMd5") String fileMd5);
    
    /**
     * 删除指定文件MD5的所有文档向量记录
     * 
     * @param fileMd5 文件MD5
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM document_vectors WHERE file_md5 = ?1", nativeQuery = true)
    void deleteByFileMd5(String fileMd5);
}
