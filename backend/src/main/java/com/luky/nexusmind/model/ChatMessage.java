package com.luky.nexusmind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "chat_messages", indexes = {
        @Index(name = "idx_chat_message_session_created", columnList = "session_id,created_at")
})
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ChatSession session;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, length = 20)
    private String status;

    /** JSON array containing safe, user-visible Agent execution summaries. */
    @Lob
    @Column(name = "agent_trace", columnDefinition = "TEXT")
    private String agentTrace;

    /** 从开始处理请求到首个回答内容输出的耗时（毫秒）。 */
    @Column(name = "thinking_duration_ms")
    private Long thinkingDurationMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
