# Agentic SWE Engine

Spring Boot workflow engine using Spring AI for Ollama and the official GitHub MCP server for repository and Actions operations.

See [RUNBOOK.md](RUNBOOK.md) for setup, environment configuration, API examples, and troubleshooting.
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and the [scenario walkthroughs](docs/scenarios/greenfield.md) for the DAG design and evaluation scenarios.

## Requirements

- Java 21
- Maven 3.6.3 or newer
- An Ollama server reachable by the application
- Docker with the Linux engine running
- A GitHub App installed on the target repositories, with repository Contents read/write, Pull requests read/write, and Actions read permissions
- The GitHub App ID, installation ID, and private key file available to the application deployment

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

Spring AI launches GitHub's official MCP server in Docker over stdio. Set `GITHUB_APP_ID`, `GITHUB_APP_INSTALLATION_ID`, and `GITHUB_APP_PRIVATE_KEY_FILE` in the deployment environment; the private key is bind-mounted read-only into the container and is never committed. `SDLC_GITHUB_HOST` defaults to `https://github.com` and can be set for GitHub Enterprise.


Create a job with `POST /api/v3/sdlc/jobs`, an `X-User-Identity` header, and a JSON body containing `requirement` and `repositoryId`. `modelAlias` is optional and defaults to `code`. Discovery creates a remote feature branch and reads bounded repository context through GitHub MCP. After mode selection, Spring AI proposes file replacements; the workflow validates the file set and commits it through `push_files`. GitHub Actions verifies the branch. Failed checks are compensated by restoring the original files and retried up to three times. After checks pass, the job owner must approve PR creation through `POST /api/v3/sdlc/jobs/{jobAlias}/pull-request`.