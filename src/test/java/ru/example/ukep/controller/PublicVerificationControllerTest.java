package ru.example.ukep.controller;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import ru.example.ukep.entity.Document;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.service.DocumentService;
import ru.example.ukep.service.SignatureVerifier;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class PublicVerificationControllerTest {

    @Test
    void verifyByUuid_invalidFormat_returns400() {
        DocumentRepository repo = Mockito.mock(DocumentRepository.class);
        PublicVerificationController c = new PublicVerificationController(
                repo, Mockito.mock(DocumentService.class), Mockito.mock(SignatureVerifier.class));

        var resp = c.verifyByUuid("not-a-uuid");
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void verifyByUuid_notFound_returns404() {
        DocumentRepository repo = Mockito.mock(DocumentRepository.class);
        when(repo.findByPublicUuid(any())).thenReturn(Optional.empty());

        PublicVerificationController c = new PublicVerificationController(
                repo, Mockito.mock(DocumentService.class), Mockito.mock(SignatureVerifier.class));

        var resp = c.verifyByUuid(UUID.randomUUID().toString());
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

        PublicVerificationController c = new PublicVerificationController(
                repo, Mockito.mock(DocumentService.class), Mockito.mock(SignatureVerifier.class));

        var resp = c.verifyByUuid(UUID.randomUUID().toString());
        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals(true, resp.getBody().get("valid"));
        assertEquals("test.pdf", resp.getBody().get("documentName"));
    }

    @Test
    void verifySig_emptyBody_returns400() {
        PublicVerificationController c = new PublicVerificationController(
                Mockito.mock(DocumentRepository.class),
                Mockito.mock(DocumentService.class),
                Mockito.mock(SignatureVerifier.class));

        var resp = c.verifySig(Map.of());
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void verifySig_mismatch_returns200WithValidFalse() {
        SignatureVerifier verifier = Mockito.mock(SignatureVerifier.class);
        try {
            when(verifier.verifyDetachedBasic(any(byte[].class), anyString()))
                    .thenThrow(new SignatureVerifier.SignatureMismatchException("mismatch"));
        } catch (Exception e) { /* not expected */ }

        PublicVerificationController c = new PublicVerificationController(
                Mockito.mock(DocumentRepository.class),
                Mockito.mock(DocumentService.class),
                verifier);

        var resp = c.verifySig(Map.of(
                "contentBase64", "aGVsbG8=",
                "signatureBase64", "dGVzdA=="));
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(false, resp.getBody().get("valid"));
    }
}
