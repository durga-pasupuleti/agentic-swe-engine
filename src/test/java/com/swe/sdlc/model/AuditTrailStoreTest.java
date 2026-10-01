package com.swe.sdlc.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditTrailStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void verifiesAppendOnlyHashChainAcrossRestartAndDetectsTampering() throws Exception {
        Path logPath = temporaryDirectory.resolve("audit-events.jsonl");
        ObjectMapper mapper = new ObjectMapper();
        AuditTrailStore store = new AuditTrailStore(logPath, mapper);
        store.append("job-1", new SdlcState.AuditEvent(
                Instant.parse("2026-10-01T12:00:00Z"), "user-1", "GOVERNANCE", "APPROVED", "Plan approved"));
        store.append("job-1", new SdlcState.AuditEvent(
                Instant.parse("2026-10-01T12:00:01Z"), "system", "WORKFLOW", "RUNNING", "Started"));

        AuditTrailStore reopened = new AuditTrailStore(logPath, mapper);
        assertTrue(reopened.verifyIntegrity());
        reopened.append("job-1", new SdlcState.AuditEvent(
                Instant.parse("2026-10-01T12:00:02Z"), "system", "WORKFLOW", "COMPLETED", "Done"));
        assertTrue(reopened.verifyIntegrity());

        List<String> lines = Files.readAllLines(logPath);
        lines.set(0, lines.get(0).replace("Plan approved", "Plan changed"));
        Files.write(logPath, lines);
        assertFalse(reopened.verifyIntegrity());
    }
}
