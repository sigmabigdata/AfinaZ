package ru.example.ukep.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.ukep.entity.Document;
import ru.example.ukep.entity.DocumentVisualization;

import java.util.Optional;

public interface DocumentVisualizationRepository extends JpaRepository<DocumentVisualization, Long> {
    Optional<DocumentVisualization> findByDocument(Document document);
    void deleteByDocument(Document document);
}
