package ru.example.ukep.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.example.ukep.entity.Role;
import ru.example.ukep.entity.User;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // === Поиск по hash (email/phone шифруются, hash для exact-поиска) ===

    Optional<User> findByEmailHash(String emailHash);
    Optional<User> findByPhoneHash(String phoneHash);
    Optional<User> findByFullNameIgnoreCaseAndSnilsHash(String fullName, String snilsHash);
    boolean existsByEmailHash(String emailHash);
    Optional<User> findByLoginToken(String loginToken);

    List<User> findAllByOrderByCreatedAtDesc();
    long countByEnabledTrue();
    long countByEnabledFalse();

    /**
     * Поиск для админки.
     * fullName — plaintext (fuzzy-поиск).
     * email/phone — exact через hash (передаётся готовый hash в параметре emailHash).
     */
    @Query("select u from User u where " +
           "(:enabled is null or u.enabled = :enabled) " +
           "and (:role is null or u.role = :role) " +
           "and (:q is null " +
           "     or lower(u.fullName) like :q " +
           "     or u.emailHash = :emailHash" +
           ") " +
           "order by u.createdAt desc")
    List<User> searchForAdmin(@Param("enabled") Boolean enabled,
                              @Param("role") Role role,
                              @Param("q") String q,
                              @Param("emailHash") String emailHash);
}
