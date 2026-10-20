package ru.example.ukep.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "document_signatures")
public class DocumentSignature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String signatureBase64;

    @Column(nullable = false)
    private Instant signedAt = Instant.now();

    @Column(name = "signer_subject_enc", length = 1000)
    @Convert(converter = ru.example.ukep.security.PiiStringConverter.class)
    private String signerSubject;

    @Column(length = 100)
    private String signerSerial;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signer_user_id")
    private User signerUser;

    @Column(name = "apply_poa", nullable = false)
    private boolean applyPoa = false;

    public Long getId() { return id; }
    public Document getDocument() { return document; }
    public String getSignatureBase64() { return signatureBase64; }
    public Instant getSignedAt() { return signedAt; }
    public String getSignerSubject() { return signerSubject; }
    public String getSignerSerial() { return signerSerial; }
    public User getSignerUser() { return signerUser; }
    public boolean isApplyPoa() { return applyPoa; }

    public void setId(Long id) { this.id = id; }
    public void setDocument(Document document) { this.document = document; }
    public void setSignatureBase64(String signatureBase64) { this.signatureBase64 = signatureBase64; }
    public void setSignedAt(Instant signedAt) { this.signedAt = signedAt; }
    public void setSignerSubject(String signerSubject) { this.signerSubject = signerSubject; }
    public void setSignerSerial(String signerSerial) { this.signerSerial = signerSerial; }
    public void setSignerUser(User signerUser) { this.signerUser = signerUser; }
    public void setApplyPoa(boolean applyPoa) { this.applyPoa = applyPoa; }
}
