package ru.example.ukep.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
public class User {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // email: в БД хранится в email_enc (AES-GCM), прозрачно шифруется через @Convert
    @Column(name = "email_enc", length = 500)
    @Convert(converter = ru.example.ukep.security.PiiStringConverter.class)
    private String email;

    @Column(name = "email_hash", length = 64)
    private String emailHash;

    @Column(nullable = false)
    private String fullName;

    @Column(name = "phone_enc", length = 500)
    @Convert(converter = ru.example.ukep.security.PiiStringConverter.class)
    private String phone;

    @Column(name = "phone_hash", length = 64)
    private String phoneHash;

    @Column(name = "snils_enc", columnDefinition = "TEXT")
    @Convert(converter = ru.example.ukep.security.PiiStringConverter.class)
    private String snils;

    @Column(name = "snils_hash", length = 64)
    private String snilsHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.ROLE_USER;

    @Column(nullable = false)
    private boolean enabled = true;

    private String loginToken;
    private Instant loginTokenExpires;
    private Instant loginTokenUsedAt;
    private Instant lastLoginAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Document> documents = new ArrayList<>();

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getEmailHash() { return emailHash; }
    public String getPhoneHash() { return phoneHash; }
    public String getSnils() { return snils; }
    public String getSnilsHash() { return snilsHash; }
    public String getFullName() { return fullName; }
    public String getPhone() { return phone; }
    public Role getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public String getLoginToken() { return loginToken; }
    public Instant getLoginTokenExpires() { return loginTokenExpires; }
    public Instant getLoginTokenUsedAt() { return loginTokenUsedAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
    public List<Document> getDocuments() { return documents; }

    public void setId(Long id) { this.id = id; }
    public void setEmail(String email) { this.email = email; }
    public void setEmailHash(String emailHash) { this.emailHash = emailHash; }
    public void setPhoneHash(String phoneHash) { this.phoneHash = phoneHash; }
    public void setSnils(String snils) { this.snils = snils; }
    public void setSnilsHash(String snilsHash) { this.snilsHash = snilsHash; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setRole(Role role) { this.role = role; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setLoginToken(String loginToken) { this.loginToken = loginToken; }
    public void setLoginTokenExpires(Instant loginTokenExpires) { this.loginTokenExpires = loginTokenExpires; }
    public void setLoginTokenUsedAt(Instant loginTokenUsedAt) { this.loginTokenUsedAt = loginTokenUsedAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setDocuments(List<Document> documents) { this.documents = documents; }
}
