package com.luky.nexusmind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "graph_prompt_templates")
public class GraphPromptTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "document_type", nullable = false, length = 50)
    private String documentType;

    @Column(length = 300)
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String instructions;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "default_template", nullable = false)
    private boolean defaultTemplate;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
