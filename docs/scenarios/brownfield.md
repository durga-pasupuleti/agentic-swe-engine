# Brownfield Scenario: Existing Service Enhancement

## Input

Example: “Add per-link expiry and return a stable 410 response for expired links.” Target an existing URL-shortener repository.

## Decomposition

1. Read a bounded set of relevant tracked source, config, and test files through GitHub MCP.
2. Identify the route, persistence, and test modules affected; record assumptions and dependencies.
3. In parallel, generate production changes, regression tests, and documentation updates.
4. Synchronize outputs and stop on path conflicts or policy violations.
5. Commit to an isolated remote feature branch, run GitHub Actions, and retry with failure context up to three attempts.
6. Restore only changed files after failed verification; request human approval before PR creation.

## Validation

The context snapshot and exact commit SHA are retained with the job. Actions must pass for that SHA. The default branch is never written directly by the engine.