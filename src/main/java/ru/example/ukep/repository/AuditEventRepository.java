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

    /**
     * Фильтр по типу, актору, результату, диапазону дат.
     * Все параметры nullable.
     */
    @Query("select e from AuditEvent e where " +
           "(:type is null or e.eventType = :type) " +
           "and (:actor is null or lower(e.actorEmail) like :actor) " +
           "and (:result is null or e.result = :result) " +
           "and (:from is null or e.eventTime >= :from) " +
           "and (:to is null or e.eventTime <= :to) " +
           "order by e.eventTime desc")
    List<AuditEvent> search(@Param("type") String type,
                            @Param("actor") String actorPattern,
                            @Param("result") String result,
                            @Param("from") Instant from,
                            @Param("to") Instant to,
                            Pageable pageable);

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
