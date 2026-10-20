package ru.example.ukep.service;

import org.springframework.stereotype.Service;

/**
 * Фасад для записи аудит-событий.
 *
 * Сама запись — в {@link AuditEventWriter} (@Transactional REQUIRES_NEW),
 * чтобы аудит-события коммитились даже при откате бизнес-транзакции.
 * Вынесено в отдельный бин, потому что Spring AOP не перехватывает
 * self-invocation, и Sonar (java:S2229) справедливо ругается.
 */
@Service
public class AuditService {

    private static final String OK = "SUCCESS";
    private static final String FAIL = "FAIL";
    private static final String WARN = "WARN";
    private static final String T_USER = "USER";
    private static final String T_DOC = "DOCUMENT";
    private static final String T_EMAIL = "EMAIL";
    private static final String T_KEYS = "KEYS";
    private static final String T_FOLDER = "FOLDER";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final AuditEventWriter writer;

    public AuditService(AuditEventWriter writer) {
        this.writer = writer;
    }

    @SuppressWarnings("java:S107")  // 8 параметров — рефакторинг в builder отложен
    private void write(String type, String result, String actorEmail, String actorRole,
                       String targetType, String targetId, String targetInfo, String details) {
        writer.write(new AuditEntry(type, result, actorEmail, actorRole,
                targetType, targetId, targetInfo, details));
    }

    public void loginSuccess(String email, String role) {
        write("LOGIN_SUCCESS", OK, email, role, null, null, null, null);
    }

    public void loginFail(String email, String reason) {
        write("LOGIN_FAIL", FAIL, email, null, null, null, null, reason);
    }

    public void adminLoginSuccess(String cn) {
        write("ADMIN_LOGIN_SUCCESS", OK, cn, ROLE_ADMIN, null, null, null, null);
    }

    public void adminLoginFail(String cn, String reason) {
        write("ADMIN_LOGIN_FAIL", FAIL, cn, ROLE_ADMIN, null, null, null, reason);
    }

    public void userCreate(String actor, Long userId, String email) {
        write("USER_CREATE", OK, actor, null, T_USER, String.valueOf(userId), email, null);
    }

    public void userUpdate(String actor, Long userId, String email) {
        write("USER_UPDATE", OK, actor, null, T_USER, String.valueOf(userId), email, null);
    }

    public void userDelete(String actor, Long userId, String email) {
        write("USER_DELETE", OK, actor, null, T_USER, String.valueOf(userId), email, null);
    }

    public void documentUpload(String actor, Long docId, String name) {
        write("DOC_UPLOAD", OK, actor, null, T_DOC, String.valueOf(docId), name, null);
    }

    public void documentDelete(String actor, Long docId, String name, boolean signed) {
        write("DOC_DELETE", OK, actor, null, T_DOC, String.valueOf(docId), name,
                signed ? "Подписанный документ" : null);
    }

    public void signSuccess(String actor, Long docId, String signerSubject) {
        write("SIGN_SUCCESS", OK, actor, null, T_DOC, String.valueOf(docId), signerSubject, null);
    }

    public void signFail(String actor, Long docId, String reason) {
        write("SIGN_FAIL", FAIL, actor, null, T_DOC, String.valueOf(docId), null, reason);
    }

    public void signatureDeleteAttempt(String actor, Long docId) {
        write("SIGNATURE_DELETE_ATTEMPT", WARN, actor, null, T_DOC, String.valueOf(docId), null,
                "Попытка удаления подписи заблокирована");
    }

    public void rateLimit(String ip, String endpoint) {
        write("RATE_LIMIT", WARN, null, null, "ENDPOINT", endpoint, null, "IP: " + ip);
    }

    public void backupCreate(String actor, String fileName) {
        write("BACKUP_CREATE", OK, actor, null, "BACKUP", fileName, null, null);
    }

    public void backupRestore(String actor, String fileName) {
        write("BACKUP_RESTORE", WARN, actor, null, "BACKUP", fileName, null, "Заменены все данные");
    }

    public void appRestart(String actor) {
        write("APP_RESTART", WARN, actor, null, "SYSTEM", null, null, null);
    }

    public void settingsUpdate(String actor, String keys) {
        write("SETTINGS_UPDATE", OK, actor, null, "SETTINGS", null, keys, null);
    }

    // ============ Email ============

    public void emailSent(String to, String subject) {
        write("EMAIL_SENT", OK, to, null, T_EMAIL, null, subject, null);
    }

    public void emailSent(String to, String subject, String details) {
        write("EMAIL_SENT", OK, to, null, T_EMAIL, null, subject, details);
    }

    public void emailFail(String to, String subject, String reason) {
        write("EMAIL_FAIL", FAIL, to, null, T_EMAIL, null, subject, reason);
    }

    // ============ Keys ============

    // ============ Folders ============

    public void folderCreate(String actor, Long folderId, String name) {
        write("FOLDER_CREATE", OK, actor, null,
                T_FOLDER, String.valueOf(folderId), name, null);
    }

    public void folderRename(String actor, Long folderId, String newName) {
        write("FOLDER_RENAME", OK, actor, null,
                T_FOLDER, String.valueOf(folderId), newName, null);
    }

    public void folderDelete(String actor, Long folderId, String name, long signedCount) {
        String details = signedCount > 0
                ? "Удалён раздел с " + signedCount + " подписанными документами"
                : null;
        write("FOLDER_DELETE", signedCount > 0 ? WARN : OK, actor, null,
                T_FOLDER, String.valueOf(folderId), name, details);
    }

    // ============ Доверенности (POA) ============

    private static final String T_POA = "POA";

    public void poaUpload(String actor, Long id, String fio, String validUntil) {
        write("POA_UPLOAD", OK, actor, null,
                T_POA, String.valueOf(id), fio,
                "Действительна до " + validUntil);
    }

    public void poaToggle(String actor, Long id, boolean active) {
        write("POA_TOGGLE", OK, actor, null,
                T_POA, String.valueOf(id), null,
                active ? "Активирована" : "Деактивирована");
    }

    public void poaDelete(String actor, Long id, String docNumber) {
        write("POA_DELETE", WARN, actor, null,
                T_POA, String.valueOf(id), docNumber, null);
    }

    public void documentUploadSignature(String actor, Long docId, String signerSubject) {
        write("DOC_UPLOAD_SIG", OK, actor, null,
                T_DOC, String.valueOf(docId), signerSubject, "Подпись загружена вручную");
    }

    /** Универсальный метод для произвольного события (визуализация, произвольные действия). */
    public void event(String type, String result, String actor,
                      String targetType, String targetId, String targetInfo, String details) {
        write(type, result, actor, null, targetType, targetId, targetInfo, details);
    }

    public void keysDownload(String actor, String fileName) {
        write("KEYS_DOWNLOAD", WARN, actor, ROLE_ADMIN,
                T_KEYS, "file.key+pii.key", fileName,
                "Скачан архив с ключами шифрования");
    }
}
