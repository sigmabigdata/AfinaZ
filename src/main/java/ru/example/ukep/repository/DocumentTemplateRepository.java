package ru.example.ukep.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.ukep.entity.DocumentTemplate;

import java.util.List;

public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplate, Long> {

    List<DocumentTemplate> findAllByOrderBySortOrderAscNameAsc();

    List<DocumentTemplate> findAllByActiveTrueOrderBySortOrderAscNameAsc();
}
