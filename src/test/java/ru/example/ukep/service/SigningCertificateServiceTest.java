package ru.example.ukep.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.DocumentSignature;
import ru.example.ukep.repository.DocumentRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

class SigningCertificateServiceTest {

    @BeforeAll
    static void checkFont() {
        Assumptions.assumeTrue(
                SigningCertificateService.findCyrillicFont() != null,
                "Нет TTF-шрифта с кириллицей — тест пропущен (установите DejaVuSans или Arial Unicode)");
    }

    @Test
    void generate_notFound_throws() {
        DocumentRepository repo = Mockito.mock(DocumentRepository.class);
        when(repo.findByIdWithSignatures(99L)).thenReturn(Optional.empty());

        SigningCertificateService svc = new SigningCertificateService(repo);
        ReflectionTestUtils.setField(svc, "baseUrl", "https://test.local");

        assertThrows(IllegalArgumentException.class, () -> svc.generate(99L));
    }

    @Test
    void generate_minimalDocument_producesPdf() throws Exception {
        Document doc = new Document();
        doc.setId(1L);
        doc.setOriginalName("test.pdf");
        doc.setSize(1024);
        doc.setFileSha256("abc123");
        doc.setUploadedAt(java.time.Instant.now());
        doc.setSignatures(new ArrayList<>());
        doc.setPublicUuid(java.util.UUID.randomUUID());

        DocumentRepository repo = Mockito.mock(DocumentRepository.class);
        when(repo.findByIdWithSignatures(anyLong())).thenReturn(Optional.of(doc));

        SigningCertificateService svc = new SigningCertificateService(repo);
        ReflectionTestUtils.setField(svc, "baseUrl", "https://test.local");

        byte[] pdf = svc.generate(1L);
        assertNotNull(pdf);
        assertTrue(pdf.length > 1000, "PDF-справка должна быть > 1 КБ");
        assertEquals('%', pdf[0]);
        assertEquals('P', pdf[1]);
        assertEquals('D', pdf[2]);
        assertEquals('F', pdf[3]);
    }

    @Test
    void generate_withSignatures_pdfLarger() throws Exception {
        Document doc = new Document();
        doc.setId(1L);
        doc.setOriginalName("test.pdf");
        doc.setSize(1024);
        doc.setFileSha256("abc");
        doc.setUploadedAt(java.time.Instant.now());

        DocumentSignature sig = new DocumentSignature();
        sig.setId(1L);
        sig.setDocument(doc);
        sig.setSignedAt(java.time.Instant.now());
        sig.setSignerSubject("Иванов И.И.");
        sig.setSignerSerial("ABC123");
        sig.setApplyPoa(true);
        doc.setSignatures(List.of(sig));
        doc.setPublicUuid(java.util.UUID.randomUUID());

        DocumentRepository repo = Mockito.mock(DocumentRepository.class);
        when(repo.findByIdWithSignatures(anyLong())).thenReturn(Optional.of(doc));

        SigningCertificateService svc = new SigningCertificateService(repo);
        ReflectionTestUtils.setField(svc, "baseUrl", "https://test.local");

        byte[] pdf = svc.generate(1L);
        assertTrue(pdf.length > 2000, "PDF с подписью должен быть крупнее");
    }
}
