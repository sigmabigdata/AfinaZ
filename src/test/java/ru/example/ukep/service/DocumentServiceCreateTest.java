package ru.example.ukep.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import ru.example.ukep.entity.DocumentFolder;
import ru.example.ukep.entity.User;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.repository.DocumentSignatureRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class DocumentServiceCreateTest {

    @Test
    void createDocument_emptyBytes_throws() throws Exception {
        Path tmp = Files.createTempDirectory("afina-test");
        try {
            DocumentService svc = new DocumentService(
                    Mockito.mock(DocumentRepository.class),
                    Mockito.mock(DocumentSignatureRepository.class),
                    Mockito.mock(FileEncryptor.class),
                    tmp.toString());
            var user = new User();
            user.setId(1L);
            var folder = new DocumentFolder();
            folder.setId(1L);
            folder.setOwner(user);

            assertThrows(IllegalArgumentException.class,
                    () -> svc.createDocument(new byte[0], "x.pdf", "application/pdf", user, folder));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void createDocument_folderBelongsToAnotherUser_throws() throws Exception {
        Path tmp = Files.createTempDirectory("afina-test");
        try {
            DocumentService svc = new DocumentService(
                    Mockito.mock(DocumentRepository.class),
                    Mockito.mock(DocumentSignatureRepository.class),
                    Mockito.mock(FileEncryptor.class),
                    tmp.toString());

            User owner = new User();
            owner.setId(1L);
            User other = new User();
            other.setId(2L);
            DocumentFolder folder = new DocumentFolder();
            folder.setOwner(other);

            assertThrows(IllegalArgumentException.class,
                    () -> svc.createDocument(new byte[]{1, 2, 3}, "x.pdf",
                            "application/pdf", owner, folder));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
