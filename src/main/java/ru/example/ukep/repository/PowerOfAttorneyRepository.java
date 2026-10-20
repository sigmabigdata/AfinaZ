package ru.example.ukep.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.example.ukep.entity.PowerOfAttorney;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PowerOfAttorneyRepository extends JpaRepository<PowerOfAttorney, Long> {

    List<PowerOfAttorney> findAllByOrderByValidUntilAsc();

    /** Поиск действующей доверенности по hash ФИО (для визуализации). */
    Optional<PowerOfAttorney> findFirstByFioHashAndActiveTrueAndValidUntilGreaterThanEqualOrderByValidUntilDesc(
            String fioHash, LocalDate today);

    /** Все, чей срок истекает между from и to (для оповещения). */
    List<PowerOfAttorney> findAllByActiveTrueAndValidUntilBetween(LocalDate from, LocalDate to);

    boolean existsByFileSha256(String fileSha256);
}
