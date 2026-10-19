package ru.example.ukep.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Раздел (папка) документов клиента.
 *
 * <p>Один уровень вложенности: документ → раздел → клиент.
 * Имя шифруется AES-GCM ({@link ru.example.ukep.security.PiiStringConverter}).
 * Дубликаты имён запрещены в рамках одного клиента (unique по owner+name_hash).</p>
 */
@Entity
@Table(name = "document_folders")
public class DocumentFolder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name_enc", columnDefinition = "TEXT")
    @Convert(converter = ru.example.ukep.security.PiiStringConverter.class)
    private String name;

    @Column(name = "name_hash", length = 64)
    private String nameHash;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    // --- getters ---
    public Long getId() { return id; }
    public String getName() { return name; }
    public String getNameHash() { return nameHash; }
    public Instant getCreatedAt() { return createdAt; }
    public User getOwner() { return owner; }

    // --- setters ---
    public void setId(Long id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setNameHash(String nameHash) { this.nameHash = nameHash; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setOwner(User owner) { this.owner = owner; }
}
