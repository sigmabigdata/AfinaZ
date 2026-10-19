package ru.example.ukep.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ru.example.ukep.entity.Document;
import ru.example.ukep.repository.DocumentRepository;
import ru.example.ukep.service.FileEncryptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

@Component
@Profile("migrate")
public class MigrateEncryption implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrateEncryption.class);

    private final DocumentRepository repo;
    private final FileEncryptor encryptor;
    private final Path storageRoot;
    private final ConfigurableApplicationContext ctx;

    public MigrateEncryption(DocumentRepository repo,
                             FileEncryptor encryptor,
                             ConfigurableApplicationContext ctx,
                             @Value("${app.storage-path}") String storagePath) {
        this.repo = repo;
        this.encryptor = encryptor;
        this.ctx = ctx;
        this.storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
    }

    /** Шифрует один документ: читает plaintext, пишет .enc, обновляет БД. */
    private void migrateOne(Document doc) throws java.io.IOException {
        Path p = storageRoot.resolve(doc.getStoredName());
        byte[] plain = Files.readAllBytes(p);
        FileEncryptor.Encrypted enc = encryptor.encrypt(plain);

        Path encPath = storageRoot.resolve(doc.getStoredName() + ".enc");
        Files.write(encPath, enc.bytes(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        doc.setStoredName(doc.getStoredName() + ".enc");
        doc.setEncryptionIv(enc.ivBase64());
        doc.setKeyVersion("v1");
        doc.setEncrypted(true);
        repo.save(doc);

        Files.delete(p);
        log.info("Зашифрован документ id={} ({} байт)", doc.getId(), plain.length);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<Document> all = repo.findAll();
        int migrated = 0;
        int skipped = 0;
        int failed = 0;

        for (Document doc : all) {
            if (doc.isEncrypted()) {
                skipped++;
            } else if (!Files.isRegularFile(storageRoot.resolve(doc.getStoredName()))) {
                log.warn("Файл отсутствует: {} (doc id={})",
                        storageRoot.resolve(doc.getStoredName()), doc.getId());
                failed++;
            } else {
                try {
                    migrateOne(doc);
                    migrated++;
                } catch (Exception e) {
                    log.error("Ошибка миграции документа id={}", doc.getId(), e);
                    failed++;
                }
            }
        }

        log.info("=== Миграция: зашифровано={}, пропущено={}, ошибок={} ===",
                migrated, skipped, failed);

        int finalFailed = failed;
        int code = SpringApplication.exit(ctx, () -> finalFailed > 0 ? 1 : 0);
        log.info("Выход с кодом {}", code);
        System.exit(code);
    }
}
