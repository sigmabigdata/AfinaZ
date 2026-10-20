package ru.example.ukep.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.example.ukep.entity.PowerOfAttorney;
import ru.example.ukep.repository.PowerOfAttorneyRepository;
import ru.example.ukep.security.PiiEncryptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class PowerOfAttorneyService {

    private static final Logger log = LoggerFactory.getLogger(PowerOfAttorneyService.class);

    private final PowerOfAttorneyRepository repo;
    private final PiiEncryptor pii;
    private final AuditService audit;
    private final SettingsService settings;
    private final EmailService emailService;

    public PowerOfAttorneyService(PowerOfAttorneyRepository repo,
                                  PiiEncryptor pii,
                                  AuditService audit,
                                  SettingsService settings,
                                  EmailService emailService) {
        this.repo = repo;
        this.pii = pii;
        this.audit = audit;
        this.settings = settings;
        this.emailService = emailService;
    }

    // ============ CRUD ============

    @Transactional
    public PowerOfAttorney upload(byte[] xmlBytes, String actorEmail) throws Exception {
        String sha256 = sha256(xmlBytes);
        if (repo.existsByFileSha256(sha256)) {
            throw new IllegalArgumentException("Эта доверенность уже загружена");
        }

        PowerOfAttorneyXmlParser.Parsed p = PowerOfAttorneyXmlParser.parse(xmlBytes);

        PowerOfAttorney poa = new PowerOfAttorney();
        poa.setFio(p.fio());
        poa.setFioHash(pii.hash(PowerOfAttorneyXmlParser.normalizeFio(p.fio())));
        poa.setSnils(p.snils());
        poa.setInn(p.inn());
        poa.setOrgName(p.orgName());
        poa.setOrgInn(p.orgInn());
        poa.setDocNumber(p.docNumber());
        poa.setDocGuid(p.docGuid());
        poa.setDocDate(p.docDate());
        poa.setValidFrom(p.validFrom());
        poa.setValidUntil(p.validUntil());
        poa.setNotaryName(p.notaryName());
        poa.setXmlContent(new String(xmlBytes, StandardCharsets.UTF_8));
        poa.setFileSha256(sha256);
        poa.setCreatedBy(actorEmail);
        poa.setActive(true);

        PowerOfAttorney saved = repo.save(poa);
        audit.poaUpload(actorEmail, saved.getId(), p.fio(), p.validUntil().toString());
        log.info("POA загружена: id={}, ФИО='{}', действ. до {}",
                saved.getId(), p.fio(), p.validUntil());
        return saved;
    }

    public List<PowerOfAttorney> listAll() {
        return repo.findAllByOrderByValidUntilAsc();
    }

    @Transactional
    public void setActive(Long id, boolean active, String actorEmail) {
        PowerOfAttorney poa = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Доверенность не найдена"));
        poa.setActive(active);
        repo.save(poa);
        audit.poaToggle(actorEmail, id, active);
    }

    @Transactional
    public void delete(Long id, String actorEmail) {
        PowerOfAttorney poa = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Доверенность не найдена"));
        repo.delete(poa);
        audit.poaDelete(actorEmail, id, poa.getDocNumber());
    }

    /** Найти действующую доверенность по ФИО (сопоставление с подписантом). */
    public Optional<PowerOfAttorney> findEffectiveByFio(String fio) {
        if (fio == null || fio.isBlank()) return Optional.empty();
        String hash = pii.hash(PowerOfAttorneyXmlParser.normalizeFio(fio));
        return repo.findFirstByFioHashAndActiveTrueAndValidUntilGreaterThanEqualOrderByValidUntilDesc(
                hash, LocalDate.now());
    }

    // ============ Оповещения о сроке действия ============

    /** Ежедневно в 09:00 по TZ контейнера. */
    @Scheduled(cron = "0 0 9 * * *")
    public void dailyExpiryCheck() {
        LocalDate today = LocalDate.now();
        LocalDate in7 = today.plusDays(7);
        LocalDate in30 = today.plusDays(30);

        List<PowerOfAttorney> expiring30 = repo.findAllByActiveTrueAndValidUntilBetween(today, in30);
        List<PowerOfAttorney> expiring7 = repo.findAllByActiveTrueAndValidUntilBetween(today, in7);

        for (PowerOfAttorney poa : expiring30) {
            if (!poa.isNotified30d()) {
                sendExpiryNotification(poa, 30);
                poa.setNotified30d(true);
                repo.save(poa);
            }
        }

        for (PowerOfAttorney poa : expiring7) {
            if (!poa.isNotified7d()) {
                sendExpiryNotification(poa, 7);
                poa.setNotified7d(true);
                repo.save(poa);
            }
        }

        // Истёкшие — тоже предупредим один раз
        LocalDate inPast = today.minusDays(1);
        List<PowerOfAttorney> expiredYesterday = repo.findAllByActiveTrueAndValidUntilBetween(inPast, inPast);
        for (PowerOfAttorney poa : expiredYesterday) {
            sendExpiryNotification(poa, 0);
        }
    }

    private void sendExpiryNotification(PowerOfAttorney poa, int days) {
        String to = settings.getOrDefault("monitor.mail_to", "");
        if (to.isBlank()) {
            log.warn("monitor.mail_to не задан — оповещение о доверенности id={} не отправлено", poa.getId());
            return;
        }
        long actualDays = ChronoUnit.DAYS.between(LocalDate.now(), poa.getValidUntil());
        String subject;
        if (actualDays < 0) {
            subject = "Афина · Доверенность ИСТЕКЛА: " + poa.getFio();
        } else {
            subject = "Афина · Доверенность истекает через " + actualDays + " дн.: " + poa.getFio();
        }
        String body = String.format("""
                Доверенность требует внимания.
                
                ФИО:            %s
                Номер:          %s от %s
                Действительна:  с %s по %s (осталось %d дн.)
                Организация:    %s
                Нотариус:       %s
                
                Откройте /admin/poa для управления.
                """,
                poa.getFio(),
                poa.getDocNumber(),
                poa.getDocDate(),
                poa.getValidFrom(),
                poa.getValidUntil(),
                actualDays,
                poa.getOrgName(),
                poa.getNotaryName());
        try {
            emailService.sendAlert(to, 1, subject + "\n\n" + body);
        } catch (Exception e) {
            log.warn("Не удалось отправить оповещение о POA id={}: {}", poa.getId(), e.getMessage());
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
