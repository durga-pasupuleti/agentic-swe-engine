package com.swe.sdlc.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class GitHubMcpManager {
    private static final Set<String> TOOLS = Set.of(
            "create_branch", "get_repository_tree", "get_file_contents", "push_files", "actions_list",
            "create_pull_request", "delete_file");
    private static final int MAX_FILES = 12;
    private static final int MAX_CONTEXT_CHARS = 80_000;
    private static final int MAX_FILE_CHARS = 40_000;

    private final List<McpSyncClient> clients;
    private final ObjectMapper mapper;

    public GitHubMcpManager(List<McpSyncClient> clients, ObjectMapper mapper) {
        this.clients = List.copyOf(clients);
        this.mapper = mapper;
    }

    public void createBranch(String owner, String repo, String branch) {
        call("create_branch", Map.of("owner", owner, "repo", repo, "branch", branch));
    }

    public RepositoryContext loadContext(String owner, String repo, String branch, String requirement) {
        JsonNode defaultTree = json(call("get_repository_tree", Map.of("owner", owner, "repo", repo, "recursive", true)));
        String baseBranch = defaultTree.path("tree_sha").asText();
        if (baseBranch.isBlank()) {
            throw new GitHubMcpException("GitHub MCP did not report the repository default branch");
        }
        JsonNode treeResponse = json(call("get_repository_tree", Map.of(
                "owner", owner, "repo", repo, "tree_sha", "refs/heads/" + branch, "recursive", true)));
        JsonNode tree = treeResponse.path("tree");
        if (!tree.isArray()) {
            throw new GitHubMcpException("GitHub MCP returned an invalid repository tree");
        }
        List<JsonNode> files = new ArrayList<>();
        boolean specs = false;
        for (JsonNode entry : tree) {
            String path = entry.path("path").asText();
            if (!"blob".equals(entry.path("type").asText()) || isSensitive(path)) {
                continue;
            }
            specs |= path.matches("(?i).*(^|/)(specs?|requirements?)(/|\\.|$).*");
            if (isSource(path)) {
                files.add(entry);
            }
        }
        List<String> terms = List.of(requirement.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+"));
        files.sort(Comparator.comparingInt((JsonNode node) -> score(node.path("path").asText(), terms)).reversed());

        Map<String, String> originalFiles = new HashMap<>();
        StringBuilder context = new StringBuilder();
        for (JsonNode file : files) {
            String path = file.path("path").asText();
            if (originalFiles.size() == MAX_FILES || context.length() >= MAX_CONTEXT_CHARS
                    || file.path("size").asLong(0) > MAX_FILE_CHARS) {
                continue;
            }
            String content = readFile(owner, repo, branch, path);
            if (content.length() > MAX_FILE_CHARS || content.contains("\u0000")
                    || context.length() + content.length() > MAX_CONTEXT_CHARS) {
                continue;
            }
            originalFiles.put(path, content);
            context.append("--- ").append(path).append(" ---\n").append(redact(content)).append('\n');
        }
        if (originalFiles.isEmpty()) {
            context.append("No suitable source files exist yet; treat this as a greenfield repository.\n");
        }
        return new RepositoryContext(baseBranch, treeResponse.path("count").asLong(tree.size()), specs,
                context.toString(), Map.copyOf(originalFiles));
    }

    public String pushChanges(String owner, String repo, String branch, List<Map<String, String>> changes,
            String message) {
        validateChanges(changes);
        List<Map<String, Object>> files = changes.stream()
                .map(change -> Map.<String, Object>of("path", change.get("path"), "content", change.get("content")))
                .toList();
        JsonNode response = json(call("push_files", Map.of("owner", owner, "repo", repo, "branch", branch,
            "files", files, "message", message)));
        String commitSha = response.path("object").path("sha").asText();
        if (commitSha.isBlank()) {
            throw new GitHubMcpException("GitHub MCP did not return the pushed commit SHA");
        }
        return commitSha;
    }

    public void rollback(String owner, String repo, String branch, Map<String, String> originalFiles,
            List<String> changedPaths, String message) {
        List<Map<String, String>> restore = changedPaths.stream()
                .filter(originalFiles::containsKey)
                .map(path -> Map.of("path", path, "content", originalFiles.get(path)))
                .toList();
        if (!restore.isEmpty()) {
            pushChanges(owner, repo, branch, restore, message);
        }
        for (String path : changedPaths) {
            if (!originalFiles.containsKey(path)) {
                call("delete_file", Map.of(
                        "owner", owner,
                        "repo", repo,
                        "branch", branch,
                        "path", path,
                        "message", message));
            }
        }
    }

    public Verification verifyActions(String owner, String repo, String branch, String expectedHeadSha) {
        for (int attempt = 0; attempt < 12; attempt++) {
            JsonNode response = json(call("actions_list", Map.of(
                    "owner", owner, "repo", repo, "method", "list_workflow_runs", "perPage", 100)));
            JsonNode runs = response.path("workflow_runs");
            if (runs.isArray()) {
                for (JsonNode run : runs) {
                        if (!branch.equals(run.path("head_branch").asText())
                            || !expectedHeadSha.equalsIgnoreCase(run.path("head_sha").asText())) {
                        continue;
                    }
                    if (!"completed".equals(run.path("status").asText())) {
                        break;
                    }
                    String conclusion = run.path("conclusion").asText();
                    return new Verification("success".equals(conclusion), false,
                            "GitHub Actions conclusion: " + conclusion);
                }
            }
            pauseBeforePoll();
        }
        return new Verification(false, true, "GitHub Actions has not completed verification for this branch yet");
    }

    public PullRequest createPullRequest(
            String owner, String repo, String head, String base, String title, String body) {
        JsonNode response = json(call("create_pull_request", Map.of(
                "owner", owner,
                "repo", repo,
                "head", head,
                "base", base,
                "title", title,
                "body", body,
                "draft", false)));
        long number = response.path("number").asLong();
        String url = response.path("html_url").asText(response.path("url").asText());
        if (number <= 0 || url.isBlank()) {
            throw new GitHubMcpException("GitHub MCP returned an invalid pull request response");
        }
        return new PullRequest(number, url);
    }

    private String readFile(String owner, String repo, String branch, String path) {
        McpSchema.CallToolResult result = call("get_file_contents", Map.of(
                "owner", owner, "repo", repo, "path", path, "ref", "refs/heads/" + branch));
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.EmbeddedResource embedded
                    && embedded.resource() instanceof McpSchema.TextResourceContents text) {
                return text.text();
            }
        }
        throw new GitHubMcpException("GitHub MCP did not return file contents for " + path);
    }

    private McpSchema.CallToolResult call(String name, Map<String, Object> arguments) {
        if (!TOOLS.contains(name) || clients.size() != 1) {
            throw new GitHubMcpException("GitHub MCP client is unavailable or tool is not allowed");
        }
        McpSyncClient client = clients.getFirst();
        if (!client.isInitialized()) {
            client.initialize();
        }
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder(name)
                .arguments(arguments).build());
        if (Boolean.TRUE.equals(result.isError())) {
            String error = result.content().stream()
                    .filter(McpSchema.TextContent.class::isInstance)
                    .map(McpSchema.TextContent.class::cast)
                    .map(McpSchema.TextContent::text)
                    .findFirst().orElse("GitHub MCP tool failed");
            throw new GitHubMcpException(error);
        }
        return result;
    }

    private JsonNode json(McpSchema.CallToolResult result) {
        String value = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst().orElseThrow(() -> new GitHubMcpException("Empty GitHub MCP response"));
        try {
            return mapper.readTree(value);
        } catch (RuntimeException exception) {
            throw new GitHubMcpException("Invalid JSON from GitHub MCP");
        }
    }

    private static void validateChanges(List<Map<String, String>> changes) {
        if (changes == null || changes.isEmpty() || changes.size() > MAX_FILES) {
            throw new GitHubMcpException("No changes or file-change limit exceeded");
        }
        Set<String> seen = new HashSet<>();
        for (Map<String, String> change : changes) {
            String path = change.get("path");
            String content = change.get("content");
            if (path == null || content == null || !seen.add(path)
                    || path.isBlank() || path.startsWith("/") || path.matches("^[A-Za-z]:.*")
                    || List.of(path.split("/", -1)).contains("..") || List.of(path.split("/", -1)).contains(".git")
                    || isSensitive(path)
                    || content.length() > MAX_FILE_CHARS) {
                throw new GitHubMcpException("Change set contains a prohibited path or oversized content");
            }
        }
    }

    private static boolean isSource(String path) {
        return path.matches("(?i).*(\\.java|\\.kt|\\.py|\\.ts|\\.tsx|\\.js|\\.jsx|\\.go|\\.rs|\\.cs|\\.xml|\\.yml|\\.yaml|\\.json|\\.md)$");
    }

    private static boolean isSensitive(String path) {
        String normalized = path.replace('\\', '/');
        return normalized.toLowerCase(Locale.ROOT).startsWith(".github/workflows/")
                || normalized.matches("(?i).*(^|/)(\\.env[^/]*|\\.npmrc|\\.netrc|secrets?[^/]*|credentials?[^/]*|[^/]*(token|password|private[_-]?key)[^/]*)(/|$).*")
                || normalized.matches("(?i).+\\.(pem|key|p12|pfx)$");
    }

    private static int score(String path, List<String> terms) {
        String normalized = path.toLowerCase(Locale.ROOT);
        return terms.stream().filter(term -> term.length() > 2 && normalized.contains(term))
                .mapToInt(String::length).sum();
    }

    private static String redact(String content) {
        return content.replaceAll(
                "(?i)(\\b(?:password|secret|token|api[_-]?key|authorization|private[_-]?key)\\b\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;#}]+)",
                "$1[REDACTED]");
    }

    private static void pauseBeforePoll() {
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitHubMcpException("Interrupted while waiting for GitHub Actions");
        }
    }

        public record RepositoryContext(String baseBranch, long estimatedRepoSizeFiles, boolean specsAvailableInStorage,
            String text, Map<String, String> originalFiles) {
    }

    public record Verification(boolean passed, boolean pending, String message) {
    }

        public record PullRequest(long number, String url) {
        }

    public static class GitHubMcpException extends RuntimeException {
        public GitHubMcpException(String message) {
            super(message);
        }
    }
}