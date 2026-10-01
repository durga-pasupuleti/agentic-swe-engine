package com.swe.sdlc.config;

import com.swe.sdlc.ai.OllamaModelService;
import com.swe.sdlc.mcp.GitHubMcpManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ChangeSetPolicy {
    private static final int MAX_FILES = 12;

    private ChangeSetPolicy() {
    }

    static List<OllamaModelService.FileChange> merge(List<OllamaModelService.FileChange> changes) {
        Map<String, OllamaModelService.FileChange> merged = new LinkedHashMap<>();
        for (OllamaModelService.FileChange change : changes) {
            OllamaModelService.FileChange existing = merged.putIfAbsent(change.path(), change);
            if (existing != null && !existing.content().equals(change.content())) {
                throw new IllegalStateException("Parallel stages produced conflicting changes for " + change.path());
            }
        }
        if (merged.isEmpty() || merged.size() > MAX_FILES) {
            throw new IllegalStateException("Merged workflow output is empty or exceeds the file limit");
        }
        return List.copyOf(merged.values());
    }

    static void validateRepositoryPaths(List<OllamaModelService.FileChange> changes,
            GitHubMcpManager.RepositoryContext repositoryContext) {
        for (OllamaModelService.FileChange change : changes) {
            if (repositoryContext.trackedPaths().contains(change.path())
                    && !repositoryContext.originalFiles().containsKey(change.path())) {
                throw new IllegalStateException("Refusing to overwrite unreviewed repository file: " + change.path());
            }
        }
    }
}