package ru.example.ukep.controller;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import ru.example.ukep.entity.Document;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.service.DocumentService;
import ru.example.ukep.service.SignatureVerifier;
import ru.example.ukep.service.VerificationReportPdfService;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class PublicVerificationControllerTest {

    private PublicVerificationController newController(DocumentRepository repo,
                                                       SignatureVerifier verifier) {
        return new PublicVerificationController(
                repo,
                Mockito.mock(DocumentService.class),
                verifier,
                Mockito.mock(VerificationReportPdfService.class));
    }

    private PublicVerificationController newController() {
        return newController(Mockito.mock(DocumentRepository.class),
                Mockito.mock(SignatureVerifier.class));
    }

    @Test
    void verifyByUuid_invalidFormat_returns400() {
        var resp = newController().verifyByUuid("not-a-uuid");
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void verifyByUuid_notFound_returns404() {
        DocumentRepository repo = Mockito.mock(DocumentRepository.class);
        when(repo.findByPublicUuid(any())).thenReturn(Optional.empty());

        var resp = newController(repo, Mockito.mock(SignatureVerifier.class))
                .verifyByUuid(UUID.randomUUID().toString());
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void verifyByUuid_valid_returns200WithData() {
        Document doc = new Document();
        doc.setId(5L);
        doc.setOriginalName("test.pdf");
        doc.setSigned(true);
        doc.setUploadedAt(java.time.Instant.now());
        doc.setSignatures(new ArrayList<>());

        DocumentRepository repo = Mockito.mock(DocumentRepository.class);
        when(repo.findByPublicUuid(any())).thenReturn(Optional.of(doc));

        var resp = newController(repo, Mockito.mock(SignatureVerifier.class))
                .verifyByUuid(UUID.randomUUID().toString());
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals(true, resp.getBody().get("valid"));
        assertEquals("test.pdf", resp.getBody().get("documentName"));
    }

    @Test
    void verifySig_emptyBody_returns400() {
        var resp = newController().verifySig(Map.of());
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void verifySig_mismatch_returns200WithValidFalse() {
        SignatureVerifier verifier = Mockito.mock(SignatureVerifier.class);
        // Новый verifyDetailed — бросает исключение
        when(verifier.verifyDetailed(any(byte[].class), anyString()))
                .thenThrow(new RuntimeException("Не удалось проверить"));

        var resp = newController(Mockito.mock(DocumentRepository.class), verifier)
                .verifySig(Map.of(
                        "contentBase64", "aGVsbG8=",
                        "signatureBase64", "dGVzdA=="));
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(false, resp.getBody().get("valid"));
    }

    @Test
    void verifySig_validResponse() {
        SignatureVerifier verifier = Mockito.mock(SignatureVerifier.class);
        when(verifier.verifyDetailed(any(byte[].class), anyString()))
                .thenReturn(Map.of(
                        "valid", true,
                        "signerSubject", "Иванов И.И.",
                        "checks", java.util.List.of()
                ));

        var resp = newController(Mockito.mock(DocumentRepository.class), verifier)
                .verifySig(Map.of(
                        "contentBase64", "aGVsbG8=",
                        "signatureBase64", "dGVzdA=="));
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(true, resp.getBody().get("valid"));
    }
}
