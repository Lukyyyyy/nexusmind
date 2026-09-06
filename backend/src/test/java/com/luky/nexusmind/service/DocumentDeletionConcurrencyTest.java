package com.luky.nexusmind.service;

import com.luky.nexusmind.model.FileUpload;
import com.luky.nexusmind.repository.DocumentVectorRepository;
import com.luky.nexusmind.repository.FileUploadRepository;
import io.minio.MinioClient;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅连接显式指定的独立测试库；验证真实行锁、事务提交和失败回滚。 */
@EnabledIfEnvironmentVariable(named = "DOCUMENT_DELETE_TEST_JDBC_URL", matches = ".+")
class DocumentDeletionConcurrencyTest {
    @Test
    void serializesDuplicateDeletesAndRollsBackFailedCleanup() throws Exception {
        var dataSource = new DriverManagerDataSource(System.getenv("DOCUMENT_DELETE_TEST_JDBC_URL"),
                "root", "delete-test-only");
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setManagedTypes(PersistenceManagedTypes.of(FileUpload.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        factory.afterPropertiesSet();
        EntityManagerFactory emf = factory.getObject();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var manager = new JpaTransactionManager(emf);
            var tx = new TransactionTemplate(manager);
            var em = SharedEntityManagerCreator.createSharedEntityManager(emf);
            var files = new JpaRepositoryFactory(em).getRepository(FileUploadRepository.class);
            var assets = mock(ParsedAssetService.class);
            var graphs = mock(KnowledgeGraphService.class);
            var vectors = mock(DocumentVectorRepository.class);
            var statuses = mock(FileProcessingStatusService.class);
            var search = mock(ElasticsearchService.class);
            var minio = mock(MinioClient.class);
            var target = new DocumentService();
            ReflectionTestUtils.setField(target, "fileUploadRepository", files);
            ReflectionTestUtils.setField(target, "parsedAssetService", assets);
            ReflectionTestUtils.setField(target, "knowledgeGraphService", graphs);
            ReflectionTestUtils.setField(target, "documentVectorRepository", vectors);
            ReflectionTestUtils.setField(target, "processingStatusService", statuses);
            ReflectionTestUtils.setField(target, "elasticsearchService", search);
            ReflectionTestUtils.setField(target, "minioClient", minio);
            var proxy = new ProxyFactory(target);
            proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            var documents = (DocumentService) proxy.getProxy();

            // 模拟真实图谱删除对 FileUpload 的更新，以覆盖原来触发 stale update 的路径。
            doAnswer(call -> {
                FileUpload file = call.getArgument(0);
                file.setGraphRunToken("cancelled");
                files.save(file);
                return null;
            }).when(graphs).removeDocument(any());
            String md5 = "0123456789abcdef0123456789abcdef";
            FileUpload file = new FileUpload();
            file.setFileMd5(md5);
            file.setUserId("owner");
            file.setFileName("delete-test.pdf");
            tx.executeWithoutResult(ignored -> files.saveAndFlush(file));

            // 使用错误的所有者不能清理任何资源。
            documents.deleteDocument(md5, "outsider");
            verifyNoInteractions(assets);
            assertEquals(1, files.count());

            CountDownLatch firstInsideCleanup = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            doAnswer(call -> {
                firstInsideCleanup.countDown();
                assertTrue(releaseFirst.await(10, TimeUnit.SECONDS));
                return null;
            }).when(assets).delete(md5);
            var first = executor.submit(() -> documents.deleteDocument(md5, "owner"));
            assertTrue(firstInsideCleanup.await(10, TimeUnit.SECONDS));
            CountDownLatch secondStarted = new CountDownLatch(1);
            var second = executor.submit(() -> {
                secondStarted.countDown();
                documents.deleteDocument(md5, "owner");
            });
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            documents.deleteDocument(md5, "owner");
            assertEquals(0, files.count());
            verify(assets, times(1)).delete(md5);
            verify(graphs, times(1)).removeDocument(any());

            FileUpload retryFile = new FileUpload();
            retryFile.setFileMd5(md5);
            retryFile.setUserId("owner");
            retryFile.setFileName("retry-test.pdf");
            tx.executeWithoutResult(ignored -> files.saveAndFlush(retryFile));
            doNothing().when(assets).delete(md5);
            doThrow(new IOException("storage unavailable")).when(minio).removeObject(any());
            assertThrows(RuntimeException.class, () -> documents.deleteDocument(md5, "owner"));
            assertEquals(1, files.count());
            assertNull(files.findByFileMd5(md5).orElseThrow().getGraphRunToken());
            doNothing().when(minio).removeObject(any());
            documents.deleteDocument(md5, "owner");
            assertEquals(0, files.count());
        } finally {
            executor.shutdownNow();
            factory.destroy();
        }
    }
}
