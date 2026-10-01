# Runbook

## Architecture

The Spring Boot engine uses Spring AI for Ollama and Spring AI's synchronous MCP client for GitHub's official MCP server. GitHub's server runs as a Docker stdio child process and authenticates as a GitHub App installation. Repository changes go to a remote feature branch; GitHub Actions verifies the changes. The engine creates a pull request only after owner approval.

The separate deployment notes and secret-variable template are in `C:\Project\AgenticSI\mcp-server`.

## Prerequisites

- Java 21
- Maven 3.6.3 or newer
- Docker Desktop with the Linux engine running
- PostgreSQL is optional; classpath prompt files are the default
- Ollama, started locally or with the included `compose.yaml`
- A GitHub App installed only on repositories the service may modify
- A GitHub Actions workflow enabled for feature-branch pushes

Grant the App repository Contents read/write, Pull requests read/write, and Actions read permissions. Install it only on the repositories this service should access. Store its private key outside the repository and restrict file access.

The API trusts `X-User-Identity` to identify job ownership. Put the service behind an authenticated gateway or add a Spring Security identity integration that overwrites this header; do not accept a caller-supplied identity directly from the public internet.
Audit events are written to `./data/audit-events.jsonl` by default. Override the path with `SDLC_AUDIT_LOG_FILE`; keep the file on a protected local volume and include it in the deployment's retention/backup policy. The job registry itself remains in-memory.

## Configure GitHub App

Set these environment variables in the same shell that launches Spring Boot. Replace placeholders locally; never put the private key contents in source files or command-line arguments.

```powershell
$env:GITHUB_APP_ID = "<app-id>"
$env:GITHUB_APP_INSTALLATION_ID = "<installation-id>"
$env:GITHUB_APP_PRIVATE_KEY_FILE = "C:\secrets\github-app.pem"
$env:SDLC_GITHUB_HOST = "https://github.com"
$env:SDLC_DOCKER_COMMAND = "docker"
```

For GitHub Enterprise Server or `ghe.com`, use the registered HTTPS host in `SDLC_GITHUB_HOST`. The GitHub MCP server obtains and refreshes installation tokens; callers do not send VCS tokens.

Confirm Docker is running and the GitHub App key path exists:

```powershell
docker info
Test-Path $env:GITHUB_APP_PRIVATE_KEY_FILE
docker pull ghcr.io/github/github-mcp-server:v1.13.0
```

## Start Ollama

Option A: use the included Docker Compose service:

```powershell
docker compose up -d sdlc-ollama-engine
docker exec sdlc-ollama-engine ollama pull qwen2.5-coder:7b
docker exec sdlc-ollama-engine ollama pull llama3.2
$env:SDLC_OLLAMA_BASE_URL = "http://localhost:11434"
```

Option B: if Ollama is already running on the host:

```powershell
ollama pull qwen2.5-coder:7b
ollama pull llama3.2
$env:SDLC_OLLAMA_BASE_URL = "http://localhost:11434"
```

Aliases are configured in `src/main/resources/application.yml`: `code` uses `qwen2.5-coder:7b`, and `fast` uses `llama3.2`. Override them with `SDLC_OLLAMA_MODEL_CODE` and `SDLC_OLLAMA_MODEL_FAST`.

## Prompt Storage

Prompt files in `src/main/resources/prompts` are used by default. To use versioned PostgreSQL storage locally, start the database and configure the app in the same PowerShell session:

```powershell
docker compose up -d sdlc-prompts-db
$env:SDLC_PROMPT_STORAGE = "postgres"
$env:SDLC_PROMPT_DATABASE_URL = "jdbc:postgresql://localhost:5432/agentic_prompts"
$env:SDLC_PROMPT_DATABASE_USERNAME = "sdlc_prompts"
$env:SDLC_PROMPT_DATABASE_PASSWORD = "local-development-only"
```

On first startup, the engine creates `prompt_template` and seeds each prompt as active version 1 from the classpath files. PostgreSQL is the source of truth afterward; each request loads the active version. Inspect versions with:

```sql
SELECT template_name, version, active, created_by, created_at
FROM prompt_template
ORDER BY template_name, version;
```

To publish an edited prompt, insert it as a new inactive version, then activate it in one transaction by deactivating the old row and activating the new row. Keep one active version per template. The database port is bound to loopback in Compose. Change the local-only default password before using a shared or production database.

## Build And Run

From `C:\Project\AgenticSI\agentic-swe-engine`:

```powershell
mvn clean verify
mvn spring-boot:run
```

Keep the process running in this terminal. At startup, Spring AI launches the official MCP Docker image over stdio. Docker and the Linux container engine must remain available.

## Submit A Job

Create a job with `POST /api/v3/sdlc/jobs`. `repositoryId` must be `owner/repository`; `modelAlias` is optional and defaults to `code`.

```powershell
curl.exe -X POST "http://localhost:8080/api/v3/sdlc/jobs" `
  -H "Content-Type: application/json" `
  -H "X-User-Identity: <authenticated-user>" `
  --data-binary '{"requirement":"<requirement text>","repositoryId":"<owner>/<repository>","modelAlias":"code"}'
```

The response is `202 Accepted` and includes `jobAlias` and `checkStatusUrl`. Poll status with the same trusted identity:

```powershell
curl.exe "http://localhost:8080/api/v3/sdlc/jobs/<jobAlias>/status" `
  -H "X-User-Identity: <authenticated-user>"
```

Wait for `PAUSED_AT_REQUIREMENT_REVIEW` and inspect `normalizedRequirement`, `acceptanceCriteria`, `ambiguities`, `assumptions`, `identifiedRisks`, `architecturePlan`, and `taskDecomposition`. Approve a clear plan before choosing an execution mode:

```powershell
curl.exe -X POST "http://localhost:8080/api/v3/sdlc/jobs/<jobAlias>/requirement-review" `
  -H "Content-Type: application/json" `
  -H "X-User-Identity: <authenticated-user>" `
  --data-binary '{"decision":"APPROVE","acceptAmbiguities":false}'
```

To clarify an ambiguous requirement, send `{"decision":"CLARIFY","requirement":"<revised requirement>"}` to that endpoint. Review the regenerated plan and approve it before continuing. If you intentionally accept listed ambiguities, set `acceptAmbiguities` to `true`. The job then reaches `PAUSED_AT_ENTRY_GATE`; choose an execution mode:

```powershell
curl.exe -X POST "http://localhost:8080/api/v3/sdlc/jobs/<jobAlias>/select-mode" `
  -H "Content-Type: application/json" `
  -H "X-User-Identity: <authenticated-user>" `
  --data-binary '{"mode":"DIRECT_CODE"}'
```

Poll the status URL until `AWAITING_APPROVAL`, `FAILED`, or `VERIFICATION_PENDING`. Discovery creates a remote feature branch. Spring AI generates file replacements from bounded, redacted context; the engine allows only files included in that context or new paths absent from the repository tree, excludes sensitive paths and workflows, and commits through GitHub MCP `push_files`. Failed Actions checks trigger a compensating restore commit and bounded retries.

If the job reaches `VERIFICATION_PENDING`, resume exact-commit polling with the owner identity:

```powershell
curl.exe -X POST "http://localhost:8080/api/v3/sdlc/jobs/<jobAlias>/verification" `
  -H "X-User-Identity: <authenticated-user>"
```

After a successful Actions run, the job waits for owner approval. Approve PR creation with:

```powershell
curl.exe -X POST "http://localhost:8080/api/v3/sdlc/jobs/<jobAlias>/pull-request" `
  -H "Content-Type: application/json" `
  -H "X-User-Identity: <authenticated-user>" `
  --data-binary '{"approved":true}'
```

To reject PR creation, send `{"approved":false}` to the same endpoint. Approval creates a pull request from the generated branch to the repository's default branch. Status then includes `pullRequestNumber` and `pullRequestUrl`.

## Troubleshooting

- **MCP server fails at startup:** check Docker Desktop is running, the image can be pulled, and `GITHUB_APP_PRIVATE_KEY_FILE` exists.
- **GitHub returns 401/404:** verify App ID, installation ID, host, and that the App is installed on the target repository.
- **Write operation is denied:** ensure the installation has repository Contents write permission. Workflow files are intentionally excluded.
- **Job remains `VERIFICATION_PENDING`:** ensure GitHub Actions are enabled and a workflow runs on the `agentic/**` branch. The engine does not claim success without a completed run.
- **Ollama errors:** check `SDLC_OLLAMA_BASE_URL` and that the selected model is pulled.
- **Build or runtime unavailable:** Maven is required to build and verify the engine. Docker with its Linux engine, the GitHub App configuration, and a reachable Ollama service are required to run the engine and process jobs. GitHub Actions validates generated target-repository changes; it does not replace building the engine itself.

## Current Limits

- Repository context is capped at 12 files and 80,000 characters.
- Generated changes may update supplied files or add new files for greenfield repositories. Deletes, renames, sensitive paths, and GitHub workflow files are rejected.
- `SPEC_DRIVEN` and `DIRECT_CODE` currently share the same generation/commit/check loop; mode-specific planning remains future work.
- If GitHub Actions has not reported a completed run after polling, the job remains `VERIFICATION_PENDING` for operator review.