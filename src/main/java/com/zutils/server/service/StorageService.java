package com.zutils.server.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class StorageService {

    private final Path storageDir;
    private final String cdnBaseUrl;

    public StorageService(
            @Value("${app.storage.dir}") String storageDir,
            @Value("${app.storage.cdn-base-url}") String cdnBaseUrl) {
        this.storageDir = Path.of(storageDir);
        this.cdnBaseUrl = cdnBaseUrl;
    }

    public String store(MultipartFile file, String pluginId, String version) {
        String filename = pluginId + "_v" + version + ".dex";
        Path targetPath = this.storageDir.resolve(filename);
        try {
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file: " + filename, e);
        }
        return cdnBaseUrl + "/" + filename;
    }

    public String store(byte[] data, String filename) {
        Path targetPath = this.storageDir.resolve(filename);
        try {
            Files.write(targetPath, data);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file: " + filename, e);
        }
        return cdnBaseUrl + "/" + filename;
    }

    public String computeChecksum(MultipartFile file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(file.getBytes());
            String hex = HexFormat.of().formatHex(md.digest());
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException("Failed to compute checksum", e);
        }
    }

    public Resource loadAsResource(String filename) {
        try {
            Path file = storageDir.resolve(filename);
            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() || resource.isReadable()) {
                return resource;
            }
            throw new RuntimeException("File not found: " + filename);
        } catch (MalformedURLException e) {
            throw new RuntimeException("Failed to load file: " + filename, e);
        }
    }

    public long getFileSize(String filename) {
        try {
            return Files.size(storageDir.resolve(filename));
        } catch (IOException e) {
            return 0;
        }
    }
}
