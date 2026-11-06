package ru.example.ukep.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import ru.example.ukep.repository.DocumentSignatureRepository;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CrlOnDemandServiceTest {

    private CrlOnDemandService newService() {
        return new CrlOnDemandService(
                Mockito.mock(DocumentSignatureRepository.class),
                Mockito.mock(CrlDownloader.class),
                Mockito.mock(CrlService.class),
                Mockito.mock(AuditService.class));
    }

    @Test
    void extractCerts_null_returnsEmpty() throws Exception {
        var svc = newService();
        assertTrue(svc.extractCerts(null).isEmpty());
        assertTrue(svc.extractCerts("").isEmpty());
    }

    @Test
    void extractCerts_garbageBase64_returnsEmptyOrThrows() {
        var svc = newService();
        try {
            var result = svc.extractCerts("aGVsbG8=");
            assertTrue(result.isEmpty(), "Garbage → пустой набор, не падение");
        } catch (Exception e) {
            // допустимо: битый CMS
        }
    }

    @Test
    void ensureForSignature_empty_returnsZero() {
        var svc = newService();
        assertEquals(0, svc.ensureForSignature(null, 1L, "admin"));
        assertEquals(0, svc.ensureForSignature("", 1L, "admin"));
    }

    @Test
    void refreshAll_emptyDb_returnsZero() {
        var repo = Mockito.mock(DocumentSignatureRepository.class);
        Mockito.when(repo.findAll()).thenReturn(java.util.List.of());

        var downloader = Mockito.mock(CrlDownloader.class);
        Mockito.when(downloader.ensureCrlsFor(Mockito.anySet(), Mockito.eq(true)))
                .thenReturn(0);

        var svc = new CrlOnDemandService(repo, downloader,
                Mockito.mock(CrlService.class), Mockito.mock(AuditService.class));

        var result = svc.refreshAll("admin");
        assertEquals(0, result.totalSignatures());
        assertEquals(0, result.crlDownloaded());
    }
}
