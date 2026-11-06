package ru.example.ukep.controller;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.userdetails.UserDetails;
import ru.example.ukep.entity.DocumentTemplate;
import ru.example.ukep.entity.Role;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.security.PiiEncryptor;
import ru.example.ukep.service.*;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class TemplateApiControllerTest {

    @Test
    void list_noAuth_returns401() {
        var c = newController(mockUserRepository(), mockPii(),
                Mockito.mock(DocumentTemplateService.class));
        var resp = c.list(null);
        assertEquals(401, resp.getStatusCode().value());
    }

    @Test
    void list_authenticated_returnsActiveTemplates() {
        DocumentTemplateService ts = Mockito.mock(DocumentTemplateService.class);
        DocumentTemplate t = new DocumentTemplate();
        t.setId(1L);
        t.setName("Заявление");
        when(ts.listActive()).thenReturn(List.of(t));
        when(ts.parseFields(any())).thenReturn(List.of());

        var c = newController(mockUserRepository(), mockPii(), ts);
        var resp = c.list(mockUser());
        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    void generate_noAuth_returns401() {
        var c = newController(mockUserRepository(), mockPii(),
                Mockito.mock(DocumentTemplateService.class));
        var req = new TemplateApiController.GenerateRequest();
        var resp = c.generate(1L, req, null);
        assertEquals(401, resp.getStatusCode().value());
    }

    @Test
    void generate_inactiveTemplate_returns400() {
        DocumentTemplateService ts = Mockito.mock(DocumentTemplateService.class);
        DocumentTemplate t = new DocumentTemplate();
        t.setId(1L);
        t.setActive(false);
        when(ts.getById(1L)).thenReturn(t);

        var c = newController(mockUserRepository(), mockPii(), ts);
        var req = new TemplateApiController.GenerateRequest();
        req.folderId = 5L;  // указываем, но неважно — упадём раньше

        var resp = c.generate(1L, req, mockUser());
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void generate_nullFolderId_returns400() {
        DocumentTemplateService ts = Mockito.mock(DocumentTemplateService.class);
        DocumentTemplate t = new DocumentTemplate();
        t.setId(1L);
        t.setActive(true);
        when(ts.getById(1L)).thenReturn(t);

        var c = newController(mockUserRepository(), mockPii(), ts);
        var req = new TemplateApiController.GenerateRequest();
        // folderId = null

        var resp = c.generate(1L, req, mockUser());
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void generate_templateNotFound_returns400() {
        DocumentTemplateService ts = Mockito.mock(DocumentTemplateService.class);
        when(ts.getById(eq(99L)))
                .thenThrow(new IllegalArgumentException("Шаблон не найден"));

        var c = newController(mockUserRepository(), mockPii(), ts);
        var req = new TemplateApiController.GenerateRequest();
        req.folderId = 5L;

        var resp = c.generate(99L, req, mockUser());
        assertEquals(400, resp.getStatusCode().value());
    }

    // ─────────── helpers ───────────

    private TemplateApiController newController(UserRepository ur, PiiEncryptor pii,
                                                  DocumentTemplateService ts) {
        return new TemplateApiController(
                ts,
                Mockito.mock(DocumentService.class),
                Mockito.mock(FolderService.class),
                ur, pii,
                Mockito.mock(AuditService.class));
    }

    private UserRepository mockUserRepository() {
        UserRepository ur = Mockito.mock(UserRepository.class);
        User u = new User();
        u.setId(1L);
        u.setRole(Role.ROLE_USER);
        when(ur.findByEmailHash(anyString())).thenReturn(Optional.of(u));
        return ur;
    }

    private PiiEncryptor mockPii() {
        PiiEncryptor pii = Mockito.mock(PiiEncryptor.class);
        when(pii.hash(anyString())).thenReturn("hash");
        return pii;
    }

    private UserDetails mockUser() {
        return org.springframework.security.core.userdetails.User
                .withUsername("test").password("{noop}x").authorities("ROLE_USER").build();
    }
}
