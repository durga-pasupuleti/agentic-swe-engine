package com.swe.sdlc.config;

import com.swe.sdlc.ai.OllamaModelService;
import com.swe.sdlc.mcp.GitHubMcpManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChangeSetPolicyTest {
    @Test
    void mergesIdenticalParallelOutputsAndRejectsConflicts() {
        var same = new OllamaModelService.FileChange("src/Main.java", "same");
        assertEquals(List.of(same), ChangeSetPolicy.merge(List.of(same, same)));

        assertThrows(IllegalStateException.class, () -> ChangeSetPolicy.merge(List.of(
                same, new OllamaModelService.FileChange("src/Main.java", "different"))));
    }

    @Test
    void allowsReviewedOrNewFilesButRejectsUnreviewedTrackedFiles() {
        var context = new GitHubMcpManager.RepositoryContext("main", 2, false, "", Map.of(
                "src/Main.java", "old"), Set.of("src/Main.java", "src/Secret.java"));

        ChangeSetPolicy.validateRepositoryPaths(List.of(
                new OllamaModelService.FileChange("src/Main.java", "updated"),
                new OllamaModelService.FileChange("src/New.java", "new")), context);
        assertThrows(IllegalStateException.class, () -> ChangeSetPolicy.validateRepositoryPaths(List.of(
                new OllamaModelService.FileChange("src/Secret.java", "overwritten")), context));
    }
}