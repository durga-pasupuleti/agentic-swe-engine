# Ambiguous Scenario: “Make Links Safer”

## Input

“Make links safer” is underspecified: it could mean phishing checks, stronger short codes, expiration, access control, or safer redirects.

## Controlled Handling

The requirement-analysis stage returns structured ambiguities, assumptions, acceptance criteria, and risks. The job pauses at `PAUSED_AT_REQUIREMENT_REVIEW` before implementation. The owner can submit clarified wording, which re-runs analysis, or explicitly acknowledge listed ambiguities when approving the plan. Only after plan approval can the owner choose execution mode.

## Validation

The model is instructed not to invent policy requirements. Output remains on a feature branch, Actions validates the exact commit, and PR creation remains separately approval-gated. Clarification/acceptance is recorded in the actor-attributed audit trail; ambiguity is not silently treated as a specification.