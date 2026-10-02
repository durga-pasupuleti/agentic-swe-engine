# Agentic SWE Engine

Spring Boot workflow engine using Spring AI for Ollama and the official GitHub MCP server for repository and Actions operations.

Development acknowledgment: GitHub Copilot was used for code assistance and documentation.

See [RUNBOOK.md](RUNBOOK.md) for setup, environment configuration, API examples, and troubleshooting.
For a step-by-step Windows setup with local Ollama and GitHub App configuration, see [docs/LOCAL_DEPLOYMENT.md](docs/LOCAL_DEPLOYMENT.md).
Importable Postman scenarios for greenfield, brownfield, tests, docs, ambiguity, and workflow gates are in [docs/postman/Agentic-SWE-Engine.postman_collection.json](docs/postman/Agentic-SWE-Engine.postman_collection.json).
The automated test matrix and local smoke checks are documented in [docs/TEST_CASES.md](docs/TEST_CASES.md).
Prompts are stored as editable classpath resources by default; optional versioned PostgreSQL storage is documented in the runbook.
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and the [scenario walkthroughs](docs/scenarios/greenfield.md) for the DAG design and evaluation scenarios.

## Requirements

- Java 21
- Maven 3.6.3 or newer
- An Ollama server reachable by the application
- Docker with the Linux engine running
- A fine-grained GitHub Personal Access Token scoped to the target repositories, with Contents read/write, Pull requests read/write, and Actions read permissions
- `GITHUB_PERSONAL_ACCESS_TOKEN` set in the local process environment

Pull the configured models before running the service:

```powershell
ollama pull qwen2.5-coder:7b
ollama pull llama3.2
```

## Run

For the guided local setup, run the PowerShell launcher. It prompts for the PAT without echoing it, starts local dependencies, runs verification, and starts the API:

```powershell
.\scripts\run-local.ps1
```

For a manually configured environment, set `GITHUB_PERSONAL_ACCESS_TOKEN` and the local service variables before using:

```powershell
mvn spring-boot:run
```

The Ollama URL can be overridden with `SDLC_OLLAMA_BASE_URL`. Model aliases are configured in `src/main/resources/application.yml`: `code` selects `qwen2.5-coder:7b`, and `fast` selects `llama3.2`. Override them with `SDLC_OLLAMA_MODEL_CODE` and `SDLC_OLLAMA_MODEL_FAST`.

Spring AI launches GitHub's official MCP server in Docker over stdio. The MCP container receives `GITHUB_PERSONAL_ACCESS_TOKEN` from the local process environment; the token is not stored in project files. `SDLC_GITHUB_HOST` defaults to `https://github.com` and can be set for GitHub Enterprise.


Create a job with `POST /api/v3/sdlc/jobs`, an `X-User-Identity` header, and a JSON body containing `requirement` and `repositoryId`. `modelAlias` is optional and defaults to `code`. Discovery creates a remote feature branch and reads bounded repository context through GitHub MCP. After mode selection, Spring AI proposes file replacements; the workflow validates the file set and commits it through `push_files`. GitHub Actions verifies the branch. Failed checks are compensated by restoring the original files and retried up to three times. After checks pass, the job owner must approve PR creation through `POST /api/v3/sdlc/jobs/{jobAlias}/pull-request`.