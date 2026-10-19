package ru.example.ukep.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.example.ukep.entity.DocumentFolder;
import ru.example.ukep.entity.User;

import java.util.List;
import java.util.Optional;

public interface DocumentFolderRepository extends JpaRepository<DocumentFolder, Long> {

    List<DocumentFolder> findAllByOwnerOrderByNameAsc(User owner);

    Optional<DocumentFolder> findByIdAndOwner(Long id, User owner);

    /** Проверка дубликата имени в рамках клиента (по hash). */
    boolean existsByOwnerAndNameHash(User owner, String nameHash);

    /** Поиск «папки по умолчанию» (для legacy-документов). */
    @Query("select f from DocumentFolder f where f.owner = :owner " +
           "and f.nameHash = :nameHash")
    Optional<DocumentFolder> findByOwnerAndNameHash(@Param("owner") User owner,
                                                     @Param("nameHash") String nameHash);

    /** Есть ли у клиента хоть одна папка. */
    boolean existsByOwner(User owner);
}
