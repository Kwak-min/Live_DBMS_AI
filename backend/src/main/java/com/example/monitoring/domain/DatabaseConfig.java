package com.example.monitoring.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "database_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DatabaseConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private Integer port;

    private Integer usernameKeyVersion;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Column(columnDefinition = "bytea")
    private byte[] usernameNonce;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Column(columnDefinition = "bytea")
    private byte[] usernameCiphertext;

    private Integer passwordKeyVersion;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Column(columnDefinition = "bytea")
    private byte[] passwordNonce;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Column(columnDefinition = "bytea")
    private byte[] passwordCiphertext;

    @Column(length = 100)
    private String databaseName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TargetDbStatus status = TargetDbStatus.UNKNOWN;

    @Column(nullable = false)
    @Builder.Default
    private Integer collectionIntervalSeconds = 5;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(nullable = false)
    @Builder.Default
    private Long configVersion = 1L;

    private Instant deletedAt;

    private Instant lastCheckedAt;

    private Instant lastSuccessAt;

    private String lastErrorMessage;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (configVersion == null) {
            configVersion = 1L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public void storeEncryptedUsername(Integer keyVersion, byte[] nonce, byte[] ciphertext) {
        this.usernameKeyVersion = keyVersion;
        this.usernameNonce = nonce.clone();
        this.usernameCiphertext = ciphertext.clone();
    }

    public void storeEncryptedPassword(Integer keyVersion, byte[] nonce, byte[] ciphertext) {
        this.passwordKeyVersion = keyVersion;
        this.passwordNonce = nonce.clone();
        this.passwordCiphertext = ciphertext.clone();
    }

    public byte[] getUsernameNonce() {
        return cloneOrNull(usernameNonce);
    }

    public byte[] getUsernameCiphertext() {
        return cloneOrNull(usernameCiphertext);
    }

    public byte[] getPasswordNonce() {
        return cloneOrNull(passwordNonce);
    }

    public byte[] getPasswordCiphertext() {
        return cloneOrNull(passwordCiphertext);
    }

    private byte[] cloneOrNull(byte[] value) {
        return value == null ? null : value.clone();
    }
}
