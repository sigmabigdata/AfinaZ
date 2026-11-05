package ru.example.ukep.dto;

public class SignRequest {
    private Long documentId;
    private java.util.List<Long> documentIds;   // для batch-подписания
    private String signatureBase64;

    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public java.util.List<Long> getDocumentIds() { return documentIds; }
    public void setDocumentIds(java.util.List<Long> ids) { this.documentIds = ids; }
    public String getSignatureBase64() { return signatureBase64; }
    public void setSignatureBase64(String signatureBase64) { this.signatureBase64 = signatureBase64; }
}
