# Agentic SWE Engine

Spring Boot service skeleton with Spring AI integration for Ollama model responses.

## Requirements

- Java 21
- Maven 3.6.3 or newer
- An Ollama server reachable by the application

Pull the configured models before running the service:

```powershell
ollama pull qwen2.5-coder:7b
ollama pull llama3.2
```

## Run

```powershell
mvn spring-boot:run
```

The Ollama URL can be overridden with `SDLC_OLLAMA_BASE_URL`. Model aliases are configured in `src/main/resources/application.yml`: `code` selects `qwen2.5-coder:7b`, and `fast` selects `llama3.2`. Override them with `SDLC_OLLAMA_MODEL_CODE` and `SDLC_OLLAMA_MODEL_FAST`.

Create a job with `POST /api/v3/sdlc/jobs`, `X-User-Identity` and `X-VCS-Token` headers, and a JSON body containing `requirement` and `repositoryId`. `modelAlias` is optional and defaults to `code`. After selecting `DIRECT_CODE`, Spring AI generates a response with the chosen allowlisted model. Repository changes, MCP operations, and build validation are not implemented yet.