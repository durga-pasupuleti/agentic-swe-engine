package com.swe.sdlc.model;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

@Service
public class AuditTrailStore {
    private static final String GENESIS_HASH = "0".repeat(64);

    private final Path logPath;
    private final ObjectMapper objectMapper;
    private final Map<String, String> chainHeads = new HashMap<>();

    public AuditTrailStore(ObjectMapper objectMapper,
            @Value("${sdlc.audit.log-file:./data/audit-events.jsonl}") String logFile) {
        this(Path.of(logFile), objectMapper);
    }

    AuditTrailStore(Path logPath, ObjectMapper objectMapper) {
        this.logPath = logPath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        loadAndVerifyExistingLog();
    }

    public synchronized void append(String jobAlias, SdlcState.AuditEvent event) {
        String previousHash = chainHeads.getOrDefault(jobAlias, GENESIS_HASH);
        String timestamp = event.timestamp().toString();
        String hash = hash(jobAlias, timestamp, event.actor(), event.stage(), event.event(),
                event.summary(), previousHash);
        AuditRecord record = new AuditRecord(jobAlias, timestamp, event.actor(), event.stage(),
                event.event(), event.summary(), previousHash, hash);
        try {
            Path parent = logPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(logPath, objectMapper.writeValueAsString(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            chainHeads.put(jobAlias, hash);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not persist the audit event", exception);
        }
    }

    public synchronized boolean verifyIntegrity() {
        if (!Files.exists(logPath)) {
            return true;
        }
        Map<String, String> verifiedHeads = new HashMap<>();
        try {
            for (String line : Files.readAllLines(logPath, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                AuditRecord record = objectMapper.readValue(line, AuditRecord.class);
                if (record == null || record.jobAlias() == null || record.timestamp() == null
                        || record.actor() == null || record.stage() == null || record.event() == null
                        || record.summary() == null || record.previousHash() == null || record.hash() == null) {
                    return false;
                }
                String expectedPrevious = verifiedHeads.getOrDefault(record.jobAlias(), GENESIS_HASH);
                if (!expectedPrevious.equals(record.previousHash())) {
                    return false;
                }
                String expectedHash = hash(record.jobAlias(), record.timestamp(), record.actor(),
                        record.stage(), record.event(), record.summary(), record.previousHash());
                if (!expectedHash.equals(record.hash())) {
                    return false;
                }
                verifiedHeads.put(record.jobAlias(), record.hash());
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private void loadAndVerifyExistingLog() {
        if (!verifyIntegrity()) {
            throw new IllegalStateException("Existing audit log failed hash-chain verification");
        }
        if (!Files.exists(logPath)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(logPath, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    AuditRecord record = objectMapper.readValue(line, AuditRecord.class);
                    chainHeads.put(record.jobAlias(), record.hash());
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read the existing audit log", exception);
        }
    }

    private static String hash(String jobAlias, String timestamp, String actor, String stage,
            String event, String summary, String previousHash) {
        String canonical = String.join("\n", jobAlias, timestamp, actor, stage, event, summary, previousHash);
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record AuditRecord(String jobAlias, String timestamp, String actor, String stage,
            String event, String summary, String previousHash, String hash) {
    }
}
