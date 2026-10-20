package ru.example.ukep.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "documents")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_name_enc", columnDefinition = "TEXT")
    @Convert(converter = ru.example.ukep.security.PiiStringConverter.class)
    private String originalName;

    @Column(nullable = false)
    private String storedName;

    private String contentType;

    private long size;

    private String fileSha256;

    @Column(columnDefinition = "TEXT")
    private String signatureBase64;

    private boolean signed = false;

    private Instant signedAt;

    @Column(name = "signer_subject_enc", length = 1000)
    @Convert(converter = ru.example.ukep.security.PiiStringConverter.class)
    private String signerSubject;

    private String signerSerial;

    @Column(name = "encryption_iv", length = 32)
    private String encryptionIv;

    @Column(name = "key_version", length = 20)
    private String keyVersion;

    @Column(nullable = false)
    private boolean encrypted = false;

    @Column(name = "apply_poa", nullable = false)
    private boolean applyPoa = false;

    @Column(nullable = false)
    private Instant uploadedAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    private DocumentFolder folder;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("signedAt ASC")
    private List<DocumentSignature> signatures = new ArrayList<>();

    // --- getters ---
    public Long getId() { return id; }
    public String getOriginalName() { return originalName; }
    public String getStoredName() { return storedName; }
    public String getContentType() { return contentType; }
    public long getSize() { return size; }
    public String getFileSha256() { return fileSha256; }
    public String getSignatureBase64() { return signatureBase64; }
    public boolean isSigned() { return signed; }
    public Instant getSignedAt() { return signedAt; }
    public String getSignerSubject() { return signerSubject; }
    public String getSignerSerial() { return signerSerial; }
    public String getEncryptionIv() { return encryptionIv; }
    public String getKeyVersion() { return keyVersion; }
    public boolean isEncrypted() { return encrypted; }
    public boolean isApplyPoa() { return applyPoa; }
    public Instant getUploadedAt() { return uploadedAt; }
    public User getOwner() { return owner; }
    public DocumentFolder getFolder() { return folder; }
    public List<DocumentSignature> getSignatures() { return signatures; }

    /** Есть ли подпись указанного пользователя на этом документе. */
    public boolean hasSignatureByUser(Long userId) {
        if (userId == null || signatures == null) return false;
        return signatures.stream().anyMatch(s ->
                s.getSignerUser() != null
                && s.getSignerUser().getId().equals(userId));
    }

    // --- setters ---
    public void setId(Long id) { this.id = id; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public void setStoredName(String storedName) { this.storedName = storedName; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public void setSize(long size) { this.size = size; }
    public void setFileSha256(String fileSha256) { this.fileSha256 = fileSha256; }
    public void setSignatureBase64(String signatureBase64) { this.signatureBase64 = signatureBase64; }
    public void setSigned(boolean signed) { this.signed = signed; }
    public void setSignedAt(Instant signedAt) { this.signedAt = signedAt; }
    public void setSignerSubject(String signerSubject) { this.signerSubject = signerSubject; }
    public void setSignerSerial(String signerSerial) { this.signerSerial = signerSerial; }
    public void setEncryptionIv(String encryptionIv) { this.encryptionIv = encryptionIv; }
    public void setKeyVersion(String keyVersion) { this.keyVersion = keyVersion; }
    public void setEncrypted(boolean encrypted) { this.encrypted = encrypted; }
    public void setApplyPoa(boolean applyPoa) { this.applyPoa = applyPoa; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }
    public void setOwner(User owner) { this.owner = owner; }
    public void setFolder(DocumentFolder folder) { this.folder = folder; }
    public void setSignatures(List<DocumentSignature> signatures) { this.signatures = signatures; }
}