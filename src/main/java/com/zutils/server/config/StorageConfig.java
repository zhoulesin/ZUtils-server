package com.zutils.server.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Value("${app.storage.dir}")
    private String storageDir;

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Path.of(storageDir));
            log.info("Storage directory initialized: {}", storageDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize storage directory", e);
        }
    }
}
