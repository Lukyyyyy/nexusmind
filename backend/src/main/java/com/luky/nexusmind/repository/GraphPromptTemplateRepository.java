package com.luky.nexusmind.repository;

import com.luky.nexusmind.model.GraphPromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GraphPromptTemplateRepository extends JpaRepository<GraphPromptTemplate, Long> {
    List<GraphPromptTemplate> findAllByOrderByDefaultTemplateDescNameAsc();
    List<GraphPromptTemplate> findByEnabledTrueOrderByDefaultTemplateDescNameAsc();
    Optional<GraphPromptTemplate> findFirstByDefaultTemplateTrueAndEnabledTrue();
    long countByDefaultTemplateTrue();
}
