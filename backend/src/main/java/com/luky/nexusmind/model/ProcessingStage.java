package com.luky.nexusmind.model;

public enum ProcessingStage {
    QUEUED,
    PARSING,
    CHUNKING,
    VECTORIZING,
    INDEXING,
    COMPLETED,
    FAILED
}
