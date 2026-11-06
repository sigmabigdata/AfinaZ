package ru.example.ukep.controller;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.userdetails.UserDetails;
import ru.example.ukep.entity.Role;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.security.PiiEncryptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class SearchControllerTest {

    @Test
    void search_noAuth_returns401() {
        var c = new SearchController(
                Mockito.mock(UserRepository.class),
                Mockito.mock(DocumentRepository.class),
                Mockito.mock(PiiEncryptor.class));
        var resp = c.search("test", 10, null);
        assertEquals(401, resp.getStatusCode().value());
    }

    @Test
    void search_emptyQuery_returnsStaticSections() {
        UserRepository ur = Mockito.mock(UserRepository.class);
        PiiEncryptor pii = Mockito.mock(PiiEncryptor.class);
        when(pii.hash(any())).thenReturn("hash");

        User admin = new User();
        admin.setId(1L);
        admin.setRole(Role.ROLE_ADMIN);
        when(ur.findByEmailHash("hash")).thenReturn(Optional.of(admin));

        var c = new SearchController(ur,
                Mockito.mock(DocumentRepository.class), pii);

        UserDetails ud = org.springframework.security.core.userdetails.User
                .withUsername("admin").password("{noop}x").authorities("ROLE_ADMIN").build();

        var resp = c.search("", 10, ud);
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody().get("pages"));
        assertTrue(((List<?>) resp.getBody().get("pages")).size() > 0);
        assertTrue(((List<?>) resp.getBody().get("actions")).size() > 0);
    }
}
