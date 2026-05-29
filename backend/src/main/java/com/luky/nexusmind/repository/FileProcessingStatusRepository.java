package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.FileProcessingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FileProcessingStatusRepository extends JpaRepository<FileProcessingStatus, Long> {
    Optional<FileProcessingStatus> findByFileMd5AndUserId(String fileMd5, String userId);

    List<FileProcessingStatus> findByFileMd5InAndUserId(Collection<String> fileMd5List, String userId);

    void deleteByFileMd5AndUserId(String fileMd5, String userId);
}
