package com.luky.nexusmind.model;

import jakarta.persistence.*;

import lombok.Data;

@Data
@Entity
@Table(name = "graph_extraction_run")
public class GraphExtractionRun {
    @Id private Long fileId;

    @Column(nullable = false)
    private String token;

    @Lob
    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String snapshot;
}
