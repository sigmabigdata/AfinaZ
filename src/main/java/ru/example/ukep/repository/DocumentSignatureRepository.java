package ru.example.ukep.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.ukep.entity.DocumentSignature;

import java.util.List;

public interface DocumentSignatureRepository extends JpaRepository<DocumentSignature, Long> {

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "update DocumentSignature s set s.applyPoa = :apply where s.id = :id")
    int updateApplyPoa(@org.springframework.data.repository.query.Param("id") Long id,
                       @org.springframework.data.repository.query.Param("apply") boolean apply);
    List<DocumentSignature> findAllByDocumentIdOrderBySignedAtAsc(Long documentId);
    long countByDocumentId(Long documentId);
    boolean existsByDocumentIdAndSignerUserId(Long documentId, Long signerUserId);
}
