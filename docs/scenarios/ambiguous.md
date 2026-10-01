# Ambiguous Scenario: “Make Links Safer”

## Input

“Make links safer” is underspecified: it could mean phishing checks, stronger short codes, expiration, access control, or safer redirects.

## Controlled Handling

The requirement-analysis stage lists ambiguity, assumptions, and candidate interpretations; architecture and task decomposition preserve that decision context. The entry gate exposes the analysis before execution mode selection. The human should not select a mode until the intended safety meaning is clear. Refine the requirement and submit a new job if the analysis does not match intent.

## Validation

The model is instructed not to invent policy requirements. Output remains on a feature branch, Actions validates the exact commit, and PR creation remains separately approval-gated. Ambiguity is visible in analysis/audit output rather than silently treated as a specification.