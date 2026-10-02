# Local Deployment Guide

This guide runs the engine and model inference on your Windows computer. GitHub repository operations still use GitHub's API through the official GitHub MCP server. Do not paste a personal access token or other secret into chat, source files, or a public issue.

## 1. Install prerequisites

Install and start:

- Java 21
- Maven 3.6.3 or newer
- Docker Desktop, configured to use the Linux engine
- Git, if you plan to clone the project
- A GitHub account with permission to create a fine-grained personal access token for the target repository

The first Docker image and model downloads are several gigabytes. Keep Docker Desktop running while using the engine.

## 2. Download the project

Either download the project ZIP from its GitHub page and extract it, or clone it:

```powershell
git clone <project-repository-url>
cd <downloaded-folder>\agentic-swe-engine
```

Run the remaining commands from the `agentic-swe-engine` directory (the directory containing `pom.xml` and `compose.yaml`).

## 3. Create a fine-grained GitHub token

1. Open GitHub **Settings > Developer settings > Personal access tokens > Fine-grained tokens** and choose **Generate new token**. Organization-owned repositories may require an organization owner to approve the token.
2. Set an expiration appropriate for your local test. Select **Only select repositories** and choose the repository you will test.
3. Grant repository permissions:
  - **Contents: Read and write**
  - **Pull requests: Read and write**
  - **Actions: Read-only**
  - **Metadata: Read-only** (usually selected automatically)
4. Generate the token. Copy it once into a password manager or directly into the secure PowerShell prompt in step 6. Do not save it in this project.

For GitHub Enterprise, set the registered HTTPS host in `SDLC_GITHUB_HOST` in step 6.

## 4. Start local Ollama

Open PowerShell in the project directory:

```powershell
docker info
docker compose up -d sdlc-ollama-engine
docker exec sdlc-ollama-engine ollama pull qwen2.5-coder:7b
docker exec sdlc-ollama-engine ollama pull llama3.2
```

Wait for both model downloads to finish. These models run locally; the engine does not send prompts to a hosted model provider. Set the URL used by the host Java process:

```powershell
$env:SDLC_OLLAMA_BASE_URL = "http://localhost:11434"
```

## 5. Optional PostgreSQL prompt storage

Classpath prompt files work without a database. To store active, versioned prompt text in local PostgreSQL instead, start the included database and select the backend before starting Spring Boot:

```powershell
docker compose up -d sdlc-prompts-db
$env:SDLC_PROMPT_STORAGE = "postgres"
$env:SDLC_PROMPT_DATABASE_URL = "jdbc:postgresql://localhost:5433/agentic_prompts"
$env:SDLC_PROMPT_DATABASE_USERNAME = "sdlc_prompts"
$env:SDLC_PROMPT_DATABASE_PASSWORD = "local-development-only"
```

Flyway applies versioned database migrations at startup; the engine then seeds missing bootstrap prompt versions from the checked-in files. Subsequent prompt loads use the active database row. New prompt edits should be inserted as a new version and activated transactionally; previous versions remain available for rollback. The Compose database is bound to loopback port 5433 to avoid colliding with an existing local Postgres service. The sample password is for local development only; change it before using a shared database.

## 6. Enter the GitHub token locally

Enter the token through PowerShell's secure prompt in the same window that will run Spring Boot. The token is not echoed or written into project files.

```powershell
$secureToken = Read-Host "Fine-grained GitHub token" -AsSecureString
$tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
try {
  $env:GITHUB_PERSONAL_ACCESS_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer)
} finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer)
}
$env:SDLC_GITHUB_HOST = "https://github.com"
$env:SDLC_DOCKER_COMMAND = "docker"
```

Verify only that a token is set; do not print it:

```powershell
([string]::IsNullOrWhiteSpace($env:GITHUB_PERSONAL_ACCESS_TOKEN)) -eq $false
```

This should return `True`. The token exists in this PowerShell process and child processes so Docker can pass it to the MCP container. It is not persisted by the guide. Open a new PowerShell window and enter it again after closing this one.

## 7. Prepare the target repository

The engine pushes to an `agentic/...` feature branch and waits for GitHub Actions to validate the exact commit. The target repository must have Actions enabled and a workflow that runs on pushes to those branches. The engine intentionally does not create or modify workflow files.

For a Java/Maven greenfield repository, add a CI workflow to the default branch before submitting a job. Adapt the Java version or build command to the target project:

```yaml
name: CI
on:
  push:
    branches: ["agentic/**"]
  pull_request:
    branches: ["main"]
jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: maven
      - run: mvn --batch-mode verify
```

Save it as `.github/workflows/ci.yml` in the target repository and push it to the default branch. If no workflow starts for the generated branch, the job remains `VERIFICATION_PENDING` and no PR can be approved.

## 8. Build and run the engine

For a guided one-command launch, open PowerShell in the project directory and run:

```powershell
.\scripts\run-local.ps1
```

The script prompts for the PAT without echoing it, starts PostgreSQL and Ollama, pulls either configured local model if missing, runs `mvn clean verify`, then starts the API. The token is removed from the launcher process environment when Spring Boot exits. You can use this instead of manually repeating steps 4-6.

From the engine project directory, in the same PowerShell window where you set the environment variables:

```powershell
mvn --batch-mode clean verify
$env:SDLC_GITHUB_MCP_ENABLED = "false"
$env:SDLC_PROMPT_STORAGE = "postgres"
$env:SDLC_PROMPT_DATABASE_URL = "jdbc:postgresql://localhost:5433/agentic_prompts"
$env:SDLC_OLLAMA_BASE_URL = "http://localhost:11434"
mvn spring-boot:run
```

This local-only mode starts the API without GitHub access. Leave the terminal open. The application listens on `http://localhost:8080`; Docker Desktop, Ollama, Postgres, and the terminal running Spring Boot must remain available. For repository jobs, follow step 10 to set the PAT and restart with GitHub MCP enabled. Audit events are stored in `./data/audit-events.jsonl` by default; set `$env:SDLC_AUDIT_LOG_FILE` before launch to select another local path. Keep the file private and back it up if audit history matters; live job status resets when the process restarts.

## 9. Submit a generation job

Open a second PowerShell window. Set an identity label for local ownership checks; use the same value for every request in this job. This header is not authentication and must not be trusted from public internet clients.

```powershell
$identity = Read-Host "Local user label"
$headers = @{ "X-User-Identity" = $identity }
$repository = Read-Host "Target repository as owner/name"
$requirement = "Build a URL shortener with create and resolve APIs, collision-safe short codes, expiration, persistent storage, input validation, and tests."
$body = @{
  requirement = $requirement
  repositoryId = $repository
  modelAlias = "code"
} | ConvertTo-Json
$job = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v3/sdlc/jobs" `
  -Headers $headers -ContentType "application/json" -Body $body
$job
```

Poll the returned job alias until it reaches `PAUSED_AT_REQUIREMENT_REVIEW`:

```powershell
$jobAlias = $job.jobAlias
Invoke-RestMethod -Uri "http://localhost:8080/api/v3/sdlc/jobs/$jobAlias/status" -Headers $headers
```

Review `normalizedRequirement`, `acceptanceCriteria`, `ambiguities`, `assumptions`, `identifiedRisks`, `architecturePlan`, and `taskDecomposition`. For a well-defined request with no unresolved ambiguities, approve the plan:

```powershell
$review = @{ decision = "APPROVE"; acceptAmbiguities = $false } | ConvertTo-Json
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v3/sdlc/jobs/$jobAlias/requirement-review" `
  -Headers $headers -ContentType "application/json" -Body $review
```

For an ambiguous request, submit clarified wording instead. The engine re-runs analysis and pauses for review again:

```powershell
$review = @{
  decision = "CLARIFY"
  requirement = "<replace with the clarified requirement>"
} | ConvertTo-Json
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v3/sdlc/jobs/$jobAlias/requirement-review" `
  -Headers $headers -ContentType "application/json" -Body $review
```

Approve the revised plan with `decision = "APPROVE"`. If you intentionally accept remaining listed ambiguities, also set `acceptAmbiguities = $true`. The job then reaches `PAUSED_AT_ENTRY_GATE`.

Choose a mode to continue:

```powershell
$modeBody = @{ mode = "DIRECT_CODE" } | ConvertTo-Json
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v3/sdlc/jobs/$jobAlias/select-mode" `
  -Headers $headers -ContentType "application/json" -Body $modeBody
```

Continue polling the status URL until `AWAITING_APPROVAL`, `FAILED`, or `VERIFICATION_PENDING`. Review the generated branch and status before approving anything.

If the status is `VERIFICATION_PENDING`, resume polling GitHub Actions for the exact commit:

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v3/sdlc/jobs/$jobAlias/verification" `
  -Headers $headers
```

## 10. Use the Postman collection

Import `docs/postman/Agentic-SWE-Engine.postman_collection.json` into Postman. Set collection variables `baseUrl` to `http://localhost:8080`, `identity` to your local user label, and `repository` to the installed target repository as `owner/name`.

The collection contains greenfield URL-shortener, brownfield enhancement/refactor/bug-fix, tests-only, documentation-only, and ambiguous-requirement create requests. Each successful create stores its returned alias as `jobAlias`. Use the lifecycle requests to inspect the plan, clarify or approve it, choose execution mode, resume pending Actions checks, and approve or reject PR creation. For the ambiguous case, run **Clarify ambiguous requirement**, review the regenerated plan, then approve it.

The running local process was started with GitHub MCP disabled until credentials are configured. To run actual repository scenarios, stop that process with Ctrl+C. In its PowerShell window, set the token using step 6, then set:

```powershell
$env:SDLC_GITHUB_MCP_ENABLED = "true"
$env:SDLC_PROMPT_STORAGE = "postgres"
$env:SDLC_PROMPT_DATABASE_URL = "jdbc:postgresql://localhost:5433/agentic_prompts"
$env:SDLC_OLLAMA_BASE_URL = "http://localhost:11434"
mvn spring-boot:run
```

Do not paste the token into Postman or chat. Postman sends only the requirement and repository name; the PAT is passed from the local process to the MCP container.

## 11. Approve or reject pull request creation

Only approve after reviewing the job and confirming the GitHub Actions run passed. To create the PR:

```powershell
$approval = @{ approved = $true } | ConvertTo-Json
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v3/sdlc/jobs/$jobAlias/pull-request" `
  -Headers $headers -ContentType "application/json" -Body $approval
```

To reject PR creation, use `$false` instead. The PR URL and number appear in the job status after creation. Local reliability metrics are available at `http://localhost:8080/api/v3/sdlc/metrics` with the same identity header.

## Troubleshooting

- **Docker is unavailable:** start Docker Desktop and wait for `docker info` to show server information.
- **Ollama connection refused:** confirm the container is running with `docker ps` and that `SDLC_OLLAMA_BASE_URL` is `http://localhost:11434` in the Spring Boot terminal.
- **MCP fails at startup:** check that `GITHUB_PERSONAL_ACCESS_TOKEN` is set, Docker is available, and `docker pull ghcr.io/github/github-mcp-server:v1.13.0` succeeds.
- **GitHub denies access:** confirm the token is unexpired, authorized for the exact target repository, and has the permissions listed above.
- **Job stays verification-pending:** confirm GitHub Actions is enabled and a workflow on the default branch triggers for `agentic/**` pushes.
- **Do not deploy this local setup publicly:** the sample identity header is not authentication, jobs are stored in memory, and the service has no production identity/rate-limit controls.
