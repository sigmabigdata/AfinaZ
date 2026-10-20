package ru.example.ukep.entity;

import jakarta.persistence.*;
import ru.example.ukep.security.PiiStringConverter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Доверенность (ЭМЧД), загруженная администратором.
 * Используется для визуализации подписи документа.
 */
@Entity
@Table(name = "powers_of_attorney")
public class PowerOfAttorney {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fio_enc", columnDefinition = "TEXT")
    @Convert(converter = PiiStringConverter.class)
    private String fio;

    @Column(name = "fio_hash", length = 64)
    private String fioHash;

    @Column(name = "snils_enc", columnDefinition = "TEXT")
    @Convert(converter = PiiStringConverter.class)
    private String snils;

    @Column(name = "inn_enc", columnDefinition = "TEXT")
    @Convert(converter = PiiStringConverter.class)
    private String inn;

    @Column(name = "org_name", length = 500)
    private String orgName;

    @Column(name = "org_inn", length = 20)
    private String orgInn;

    @Column(name = "doc_number", length = 100)
    private String docNumber;

    @Column(name = "doc_guid", length = 100)
    private String docGuid;

    /** GUID доверенности (НомДовер) — используется в штампе визуализации. */
    @Column(name = "stamp_number", length = 100)
    private String stampNumber;

    @Column(name = "doc_date")
    private LocalDate docDate;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_until", nullable = false)
    private LocalDate validUntil;

    @Column(name = "notary_name", length = 300)
    private String notaryName;

    @Column(name = "xml_enc", columnDefinition = "TEXT")
    @Convert(converter = PiiStringConverter.class)
    private String xmlContent;

    @Column(name = "file_sha256", length = 64)
    private String fileSha256;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "notified_30d", nullable = false)
    private boolean notified30d = false;

    @Column(name = "notified_7d", nullable = false)
    private boolean notified7d = false;

    // --- методы статуса ---

    /** Доверенность истекла на сегодня. */
    public boolean isExpired() {
        return validUntil != null && validUntil.isBefore(LocalDate.now());
    }

    /** Действует (active + не истекла). */
    public boolean isEffective() {
        return active && !isExpired();
    }

    /** Истекает в течение N дней (но не истекла). */
    public boolean isExpiringWithin(int days) {
        if (isExpired() || validUntil == null) return false;
        return validUntil.isBefore(LocalDate.now().plusDays(days));
    }

    /** Истекает в течение 30 дней — без параметров, для SpEL/Thymeleaf. */
    public boolean isExpiringSoon() {
        return isExpiringWithin(30);
    }

    /** Истекает в течение 7 дней — без параметров, для SpEL/Thymeleaf. */
    public boolean isExpiringCritical() {
        return isExpiringWithin(7);
    }

    // --- getters/setters ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFio() { return fio; }
    public void setFio(String fio) { this.fio = fio; }
    public String getFioHash() { return fioHash; }
    public void setFioHash(String fioHash) { this.fioHash = fioHash; }
    public String getSnils() { return snils; }
    public void setSnils(String snils) { this.snils = snils; }
    public String getInn() { return inn; }
    public void setInn(String inn) { this.inn = inn; }
    public String getOrgName() { return orgName; }
    public void setOrgName(String orgName) { this.orgName = orgName; }
    public String getOrgInn() { return orgInn; }
    public void setOrgInn(String orgInn) { this.orgInn = orgInn; }
    public String getDocNumber() { return docNumber; }
    public void setDocNumber(String docNumber) { this.docNumber = docNumber; }
    public String getDocGuid() { return docGuid; }
    public void setDocGuid(String docGuid) { this.docGuid = docGuid; }
    public String getStampNumber() { return stampNumber; }
    public void setStampNumber(String stampNumber) { this.stampNumber = stampNumber; }
    public LocalDate getDocDate() { return docDate; }
    public void setDocDate(LocalDate docDate) { this.docDate = docDate; }
    public LocalDate getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDate validFrom) { this.validFrom = validFrom; }
    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }
    public String getNotaryName() { return notaryName; }
    public void setNotaryName(String notaryName) { this.notaryName = notaryName; }
    public String getXmlContent() { return xmlContent; }
    public void setXmlContent(String xmlContent) { this.xmlContent = xmlContent; }
    public String getFileSha256() { return fileSha256; }
    public void setFileSha256(String fileSha256) { this.fileSha256 = fileSha256; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isNotified30d() { return notified30d; }
    public void setNotified30d(boolean notified30d) { this.notified30d = notified30d; }
    public boolean isNotified7d() { return notified7d; }
    public void setNotified7d(boolean notified7d) { this.notified7d = notified7d; }
}
