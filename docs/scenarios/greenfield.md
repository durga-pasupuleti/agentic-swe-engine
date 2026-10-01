# Greenfield Scenario: URL Shortener

## Input

Submit a requirement such as:

> Build a URL shortener service with create, resolve, and analytics APIs. Use collision-safe short codes, expiration support, input validation, persistent storage, and tests.

Target an initialized empty GitHub repository. The engine's analysis recognizes the absence of source files and classifies it as greenfield; architecture and task decomposition are shown before the entry gate.

## Decomposition

1. Define create/resolve/analytics API contracts and validation.
2. Design short-code generation, persistence model, expiry handling, and error mapping.
3. In parallel, generate Spring Boot implementation, unit/integration tests, and API/run documentation.
4. Join outputs, reject conflicting paths, and enforce repository/path/size policy.
5. Commit the new files to a feature branch and verify the exact commit with GitHub Actions.
6. Require owner approval before opening a PR.

## Validation

The target repository must have a workflow that runs on `agentic/**` branches. The engine reports Actions output and will not call a failed or pending run successful. New files are permitted for greenfield jobs, but workflow files, secrets, symlinks, binaries, and unsafe paths are rejected.