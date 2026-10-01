package com.swe.sdlc.ai;

import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

interface PromptTemplateRepository {
    String load(String templateName);
}

final class ClasspathPromptTemplateRepository implements PromptTemplateRepository {
    @Override
    public String load(String templateName) {
        try (var input = new ClassPathResource("prompts/" + templateName).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load prompt template " + templateName, exception);
        }
    }
}

final class PostgresPromptTemplateRepository implements PromptTemplateRepository {
    private static final List<String> DEFAULT_TEMPLATES = List.of(
            "requirement-analysis.prompt", "architecture.prompt", "task-decomposition.prompt",
            "implementation.prompt", "tests.prompt", "documentation.prompt");

    private final String url;
    private final String username;
    private final String password;

    PostgresPromptTemplateRepository(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
        initializeAndSeed();
    }

    @Override
    public String load(String templateName) {
        String sql = "SELECT content FROM prompt_template WHERE template_name = ? AND active = TRUE";
        try (Connection connection = DriverManager.getConnection(url, username, password);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, templateName);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return result.getString("content");
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not load active prompt template " + templateName, exception);
        }
        throw new IllegalStateException("No active prompt template named " + templateName);
    }

    private void initializeAndSeed() {
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS prompt_template ("
                        + "template_name VARCHAR(100) NOT NULL, version INTEGER NOT NULL, content TEXT NOT NULL, "
                        + "active BOOLEAN NOT NULL DEFAULT FALSE, created_by VARCHAR(200) NOT NULL, "
                        + "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                        + "PRIMARY KEY (template_name, version))");
                statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS uq_prompt_template_active "
                        + "ON prompt_template (template_name) WHERE active = TRUE");
            }
            for (String templateName : DEFAULT_TEMPLATES) {
                seedIfMissing(connection, templateName);
            }
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            throw new IllegalStateException("Could not initialize PostgreSQL prompt storage", exception);
        }
    }

    private void seedIfMissing(Connection connection, String templateName) throws SQLException {
        try (PreparedStatement active = connection.prepareStatement(
                "SELECT 1 FROM prompt_template WHERE template_name = ? AND active = TRUE")) {
            active.setString(1, templateName);
            try (ResultSet result = active.executeQuery()) {
                if (result.next()) {
                    return;
                }
            }
        }

        int nextVersion;
        try (PreparedStatement maximum = connection.prepareStatement(
                "SELECT COALESCE(MAX(version), 0) + 1 AS next_version FROM prompt_template WHERE template_name = ?")) {
            maximum.setString(1, templateName);
            try (ResultSet result = maximum.executeQuery()) {
                result.next();
                nextVersion = result.getInt("next_version");
            }
        }
        String content = new ClasspathPromptTemplateRepository().load(templateName);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO prompt_template (template_name, version, content, active, created_by) "
                        + "VALUES (?, ?, ?, TRUE, 'classpath-bootstrap')")) {
            insert.setString(1, templateName);
            insert.setInt(2, nextVersion);
            insert.setString(3, content);
            insert.executeUpdate();
        }
    }
}