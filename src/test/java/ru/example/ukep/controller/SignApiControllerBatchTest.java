package ru.example.ukep.controller;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import ru.example.ukep.entity.Role;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.UserRepository;
import ru.example.ukep.security.PiiEncryptor;
import ru.example.ukep.service.*;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class SignApiControllerBatchTest {

    @Test
    void acceptBatch_emptyList_returns400() {
        var c = newControllerWithUser();
        var resp = c.acceptUserBatch(List.of(), mockUser());
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void acceptBatch_nullList_returns400() {
        var c = newControllerWithUser();
        var resp = c.acceptUserBatch(null, mockUser());
        assertEquals(400, resp.getStatusCode().value());
    }

    private SignApiController newControllerWithUser() {
        UserRepository ur = Mockito.mock(UserRepository.class);
        PiiEncryptor pii = Mockito.mock(PiiEncryptor.class);
        when(pii.hash(anyString())).thenReturn("hash");

        User u = new User();
        u.setId(1L);
        u.setRole(Role.ROLE_USER);
        when(ur.findByEmailHash("hash")).thenReturn(Optional.of(u));

        return new SignApiController(
                Mockito.mock(DocumentService.class),
                Mockito.mock(SignatureVerifier.class),
                Mockito.mock(AuditService.class),
                ur,
                Mockito.mock(DocumentRepository.class),
                pii,
                Mockito.mock(SignatureVisualizationService.class));
    }

    private org.springframework.security.core.userdetails.UserDetails mockUser() {
        return org.springframework.security.core.userdetails.User
                .withUsername("test").password("{noop}x").authorities("ROLE_USER").build();
    }
}
