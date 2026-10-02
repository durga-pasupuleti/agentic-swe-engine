# Agentic SWE Engine

Local-first Spring Boot workflow engine. Ollama performs requirement analysis and code generation locally; GitHub's official MCP server, launched as a Docker child process, accesses repositories and Actions.

Development acknowledgment: GitHub Copilot was used for code assistance and documentation.

## Quick Start

Requirements: Java 21, Maven 3.6.3+, Docker Desktop with the Linux engine, and Windows PowerShell.

From the repository root, run the guided launcher:

```powershell
.\scripts\run-local.ps1
```

The launcher securely prompts for a fine-grained PAT, starts local Ollama and PostgreSQL, ensures the configured local models are installed, pulls the official GitHub MCP image, runs `mvn clean verify`, and starts the API on `http://localhost:8080`. It refuses to start if port 8080 is already occupied; stop the existing engine with Ctrl+C first. Never pass the PAT as a command-line argument or put it in a file committed to Git.

To use GitHub repository operations, create a **fine-grained PAT** limited to the target repository. Grant **Contents: Read and write**, **Pull requests: Read and write**, **Actions: Read-only**, and **Metadata: Read-only**. Organization-owned repositories may require organization approval or SSO authorization. If GitHub reports `403 Resource not accessible by personal access token` while creating a branch, check the repository selection and Contents write permission, then restart the engine so the Docker MCP child receives the updated token.

The engine runs locally; model calls go to local Ollama. GitHub API calls are remote by design. Prompts use classpath files by default or optional versioned PostgreSQL storage.

## Create A Job

The target must be an **existing** `owner/repository` that the token can access. The engine creates a feature branch; it does not create a new GitHub repository. The target repository must also have GitHub Actions enabled with a workflow triggered by `agentic/**` branches.

The helper sends one of these preset requirements:

```powershell
.\scripts\curl\sdlc-api.ps1 -Action New -Repository "owner/repository" -Scenario Greenfield -Identity "local-user"
```

Valid `-Scenario` values are `Greenfield`, `Enhancement`, `Refactor`, `BugFix`, `TestsOnly`, `DocsOnly`, and `Ambiguous`. Replace `owner/repository` with a real repository; literal placeholders will result in GitHub 404s. The presets are examples. For a repository-specific requirement, call `POST /api/v3/sdlc/jobs` with your own requirement text:

```powershell
$payload = @{
	repositoryId = "owner/repository"
	modelAlias = "code"
	requirement = "Add per-link expiration. Expired links return HTTP 410. Preserve existing endpoints and add tests for future, expired, and missing links."
} | ConvertTo-Json -Compress

Invoke-RestMethod -Method Post `
	-Uri "http://localhost:8080/api/v3/sdlc/jobs" `
	-Headers @{ "X-User-Identity" = "local-user" } `
	-ContentType "application/json" -Body $payload
```

The identity header is only an ownership label in this local prototype; it is **not authentication**. Do not expose the service publicly without trusted authentication middleware.

## Review And Continue

Save the returned `jobAlias`, then inspect its status and plan:

```powershell
$jobAlias = "job-id-from-create-response"
$headers = @{ "X-User-Identity" = "local-user" }
Invoke-RestMethod -Uri "http://localhost:8080/api/v3/sdlc/jobs/$jobAlias/status" -Headers $headers
```

When status is `PAUSED_AT_REQUIREMENT_REVIEW`, review `normalizedRequirement`, `acceptanceCriteria`, `ambiguities`, `assumptions`, `identifiedRisks`, `architecturePlan`, and `taskDecomposition`. For an ambiguous requirement, clarify it first:

```powershell
$review = @{ decision = "CLARIFY"; requirement = "State the exact behavior and acceptance criteria." } | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post `
	-Uri "http://localhost:8080/api/v3/sdlc/jobs/$jobAlias/requirement-review" `
	-Headers $headers -ContentType "application/json" -Body $review
```

After reviewing the regenerated plan, approve it. Set `acceptAmbiguities` to true only if you explicitly accept remaining ambiguities:

```powershell
$review = @{ decision = "APPROVE"; acceptAmbiguities = $false } | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post `
	-Uri "http://localhost:8080/api/v3/sdlc/jobs/$jobAlias/requirement-review" `
	-Headers $headers -ContentType "application/json" -Body $review
```

When the job reaches `PAUSED_AT_ENTRY_GATE`, select a mode:

```powershell
$mode = @{ mode = "DIRECT_CODE" } | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post `
	-Uri "http://localhost:8080/api/v3/sdlc/jobs/$jobAlias/select-mode" `
	-Headers $headers -ContentType "application/json" -Body $mode
```

The engine writes only to the feature branch, validates changes with Actions for the exact commit, and waits at `AWAITING_APPROVAL`. Review the branch and run before allowing PR creation:

```powershell
$decision = @{ approved = $true } | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post `
	-Uri "http://localhost:8080/api/v3/sdlc/jobs/$jobAlias/pull-request" `
	-Headers $headers -ContentType "application/json" -Body $decision
```

For `VERIFICATION_PENDING`, call `POST /api/v3/sdlc/jobs/{jobAlias}/verification`. Reliability metrics are at `GET /api/v3/sdlc/metrics`.

Alternatively, import [the Postman collection](docs/postman/Agentic-SWE-Engine.postman_collection.json). The curl wrapper's actions and parameters are documented in [the local deployment guide](docs/LOCAL_DEPLOYMENT.md).

## Test

```powershell
mvn --batch-mode clean verify
```

See [docs/TEST_CASES.md](docs/TEST_CASES.md) for the automated test matrix and local smoke-test results. Live GitHub integration tests require a valid, repository-scoped PAT and an Actions workflow on the target.

## More Documentation

- [Local deployment guide](docs/LOCAL_DEPLOYMENT.md)
- [Runbook](RUNBOOK.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Scenario walkthroughs](docs/scenarios/greenfield.md)
- [Postman collection](docs/postman/Agentic-SWE-Engine.postman_collection.json)