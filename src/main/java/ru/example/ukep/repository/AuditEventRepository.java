package ru.example.ukep.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.example.ukep.entity.AuditEvent;

import java.time.Instant;
import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findAllByOrderByEventTimeDesc(Pageable pageable);

    long countByEventTypeAndResult(String eventType, String result);

    long countByEventTypeAndEventTimeAfter(String eventType, Instant after);
    long countByEventTypeAndResultAndEventTimeAfter(String eventType, String result, Instant after);

    @Query("select e from AuditEvent e where e.eventType = :type " +
           "and e.result = :result order by e.eventTime desc")
    List<AuditEvent> findRecentByTypeAndResult(@Param("type") String type,
                                                @Param("result") String result,
                                                Pageable pageable);

    /** Количество событий старше cutoff (для UI). */
    long countByEventTimeBefore(Instant cutoff);

    /** Удаление старых событий (retention policy). */
    long deleteByEventTimeBefore(Instant cutoff);

    @Query("select distinct e.eventType from AuditEvent e order by e.eventType")
    List<String> findDistinctEventTypes();
}
