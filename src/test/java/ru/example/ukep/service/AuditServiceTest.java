package ru.example.ukep.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock AuditEventWriter writer;
    @Captor ArgumentCaptor<AuditEntry> captor;

    private AuditService audit;

    @BeforeEach
    void setUp() {
        audit = new AuditService(writer);
    }

    @Test
    @DisplayName("loginSuccess формирует LOGIN_SUCCESS/SUCCESS")
    void loginSuccess() {
        audit.loginSuccess("user@example.com", "ROLE_USER");
        verify(writer).write(captor.capture());
        AuditEntry e = captor.getValue();
        assertThat(e.type()).isEqualTo("LOGIN_SUCCESS");
        assertThat(e.result()).isEqualTo("SUCCESS");
        assertThat(e.actorEmail()).isEqualTo("user@example.com");
        assertThat(e.actorRole()).isEqualTo("ROLE_USER");
    }

    @Test
    @DisplayName("loginFail формирует LOGIN_FAIL с причиной")
    void loginFail() {
        audit.loginFail("bad@example.com", "token expired");
        verify(writer).write(captor.capture());
        AuditEntry e = captor.getValue();
        assertThat(e.type()).isEqualTo("LOGIN_FAIL");
        assertThat(e.result()).isEqualTo("FAIL");
        assertThat(e.details()).isEqualTo("token expired");
    }

    @Test
    @DisplayName("signSuccess формирует SIGN_SUCCESS с подписантом")
    void signSuccess() {
        audit.signSuccess("user@example.com", 42L, "CN=Ivanov");
        verify(writer).write(captor.capture());
        AuditEntry e = captor.getValue();
        assertThat(e.type()).isEqualTo("SIGN_SUCCESS");
        assertThat(e.targetType()).isEqualTo("DOCUMENT");
        assertThat(e.targetId()).isEqualTo("42");
        assertThat(e.targetInfo()).isEqualTo("CN=Ivanov");
    }

    @Test
    @DisplayName("emailFail формирует EMAIL_FAIL с причиной")
    void emailFail() {
        audit.emailFail("to@example.com", "Test", "530 Must STARTTLS");
        verify(writer).write(captor.capture());
        AuditEntry e = captor.getValue();
        assertThat(e.type()).isEqualTo("EMAIL_FAIL");
        assertThat(e.result()).isEqualTo("FAIL");
        assertThat(e.details()).isEqualTo("530 Must STARTTLS");
    }

    @Test
    @DisplayName("folderCreate формирует FOLDER_CREATE/SUCCESS")
    void folderCreate() {
        audit.folderCreate("client@example.com", 7L, "Contracts");
        verify(writer).write(captor.capture());
        AuditEntry e = captor.getValue();
        assertThat(e.type()).isEqualTo("FOLDER_CREATE");
        assertThat(e.result()).isEqualTo("SUCCESS");
        assertThat(e.targetType()).isEqualTo("FOLDER");
        assertThat(e.targetId()).isEqualTo("7");
        assertThat(e.targetInfo()).isEqualTo("Contracts");
    }

    @Test
    @DisplayName("folderRename формирует FOLDER_RENAME/SUCCESS")
    void folderRename() {
        audit.folderRename("admin", 7L, "Archive");
        verify(writer).write(captor.capture());
        AuditEntry e = captor.getValue();
        assertThat(e.type()).isEqualTo("FOLDER_RENAME");
        assertThat(e.targetInfo()).isEqualTo("Archive");
    }

    @Test
    @DisplayName("folderDelete без подписанных — SUCCESS, details null")
    void folderDeleteNoSigned() {
        audit.folderDelete("client@example.com", 7L, "Empty", 0L);
        verify(writer).write(captor.capture());
        AuditEntry e = captor.getValue();
        assertThat(e.type()).isEqualTo("FOLDER_DELETE");
        assertThat(e.result()).isEqualTo("SUCCESS");
        assertThat(e.details()).isNull();
    }

    @Test
    @DisplayName("folderDelete с подписанными — WARN + details с количеством")
    void folderDeleteWithSignedDowngradesToWarn() {
        audit.folderDelete("admin", 7L, "Contracts", 3L);
        verify(writer).write(captor.capture());
        AuditEntry e = captor.getValue();
        assertThat(e.type()).isEqualTo("FOLDER_DELETE");
        assertThat(e.result()).isEqualTo("WARN");
        assertThat(e.details()).contains("3");
    }

    @Test
    @DisplayName("keysDownload формирует KEYS_DOWNLOAD/WARN")
    void keysDownload() {
        audit.keysDownload("admin", "afina-keys.zip");
        verify(writer).write(captor.capture());
        AuditEntry e = captor.getValue();
        assertThat(e.type()).isEqualTo("KEYS_DOWNLOAD");
        assertThat(e.result()).isEqualTo("WARN");
        assertThat(e.targetType()).isEqualTo("KEYS");
    }

    @Test
    @DisplayName("documentDelete с signed=true пишет в details")
    void documentDeleteSigned() {
        audit.documentDelete("admin", 5L, "signed.pdf", true);
        verify(writer).write(captor.capture());
        AuditEntry e = captor.getValue();
        assertThat(e.type()).isEqualTo("DOC_DELETE");
        assertThat(e.details()).isEqualTo("Подписанный документ");
    }
}
