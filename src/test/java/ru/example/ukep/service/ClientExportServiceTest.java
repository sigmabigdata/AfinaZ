package ru.example.ukep.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.Role;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.DocumentVisualizationRepository;
import ru.example.ukep.repository.UserRepository;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

class ClientExportServiceTest {

    @Test
    void exportClient_notFound_throws() {
        UserRepository ur = Mockito.mock(UserRepository.class);
        when(ur.findById(99L)).thenReturn(Optional.empty());

        ClientExportService svc = new ClientExportService(
                ur,
                Mockito.mock(DocumentRepository.class),
                Mockito.mock(DocumentVisualizationRepository.class),
                Mockito.mock(DocumentService.class));

        assertThrows(IllegalArgumentException.class, () -> svc.exportClient(99L));
    }

    @Test
    void exportClient_emptyUser_containsReadmeAndMetadata() throws Exception {
        User user = new User();
        user.setId(1L);
        user.setFullName("Иванов И.И.");
        user.setEmail("test@test.ru");
        user.setRole(Role.ROLE_USER);
        user.setEnabled(true);
        user.setCreatedAt(Instant.now());

        UserRepository ur = Mockito.mock(UserRepository.class);
        when(ur.findById(1L)).thenReturn(Optional.of(user));

        DocumentRepository dr = Mockito.mock(DocumentRepository.class);
        when(dr.findAllByOwnerOrderByUploadedAtDesc(user)).thenReturn(List.of());

        ClientExportService svc = new ClientExportService(
                ur, dr, Mockito.mock(DocumentVisualizationRepository.class),
                Mockito.mock(DocumentService.class));

        byte[] zip = svc.exportClient(1L);
        assertNotNull(zip);
        assertTrue(zip.length > 100);

        // Проверяем структуру ZIP
        boolean hasReadme = false, hasMetadata = false;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("README.txt")) hasReadme = true;
                if (entry.getName().equals("metadata.json")) hasMetadata = true;
            }
        }
        assertTrue(hasReadme, "ZIP должен содержать README.txt");
        assertTrue(hasMetadata, "ZIP должен содержать metadata.json");
    }
}
