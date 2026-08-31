package com.luky.nexusmind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "smtp_settings")
public class SmtpSettings {
    @Id
    private Long id = 1L;
    @Column(nullable = false)
    private String host;
    @Column(nullable = false)
    private int port = 465;
    @Column(nullable = false)
    private String username;
    @Column(name = "encrypted_password", nullable = false, length = 1000)
    private String encryptedPassword;
    @Column(name = "from_address", nullable = false, length = 320)
    private String fromAddress;
    @Column(name = "ssl_enabled", nullable = false)
    private boolean sslEnabled = true;
    @Column(nullable = false)
    private boolean enabled = true;
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
