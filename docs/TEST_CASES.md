# Test Cases

## Automated Tests

The most recent `mvn --batch-mode clean verify` run passed **14 tests across 7 test classes**.

| Test class | Cases covered |
| --- | --- |
| `OllamaModelServicePromptTest` | Brownfield prompts retain the requested domain and repository stack; requirement-analysis prompt includes structured acceptance criteria, ambiguities, and assumptions; no-op workstreams accept an empty file list while malformed change sets are rejected. |
| `ChangeSetPolicyTest` | Identical parallel outputs merge and conflicting writes fail; reviewed tracked files and new paths are allowed while unreviewed tracked paths are rejected. |
| `OllamaModelCatalogTest` | The `fast` alias is selected as fallback for `code`; no fallback is returned if only the preferred model is configured. |
| `SdlcWorkflowReviewTest` | Owner approval opens the execution-mode gate; clarification is retained and queues re-analysis; unresolved ambiguities require explicit acknowledgment. |
| `SdlcWorkflowVerificationTest` | Successful resumed Actions verification opens the human-approval gate for the exact commit; incomplete verification remains pending and resumable. |
| `AuditTrailStoreTest` | Audit hash chain survives reopening and continued appends; modified event content is detected as tampering. |
| `SdlcWorkflowGraphTest` | Planning and parallel workstream layers are ordered correctly; synchronization depends on implementation, tests, and documentation. |

## Local Smoke Checks

These checks were run locally, separately from the JUnit suite:

- `docker compose config --quiet` passed.
- PostgreSQL reported healthy and accepted connections on `localhost:5433`.
- The app seeded all six active prompt templates into PostgreSQL.
- Ollama listed `qwen2.5-coder:7b` and `llama3.2` as installed local models.
- Spring Boot started on port 8080 with GitHub MCP disabled, and `GET /api/v3/sdlc/metrics` returned a successful response.

## Not Covered Yet

There has not yet been a live GitHub MCP run using a valid fine-grained PAT. Repository discovery, actual Ollama generation through a job, GitHub Actions polling, compensating rollback against GitHub, and PR creation therefore remain integration scenarios to run against a disposable repository. The metrics smoke check did not create a workflow job.