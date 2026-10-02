[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('New', 'Status', 'Review', 'Mode', 'Verify', 'PullRequest', 'Metrics')]
    [string]$Action,

    [string]$BaseUrl = 'http://localhost:8080',
    [string]$Identity,
    [string]$Repository,
    [ValidateSet('Greenfield', 'Enhancement', 'Refactor', 'BugFix', 'TestsOnly', 'DocsOnly', 'Ambiguous')]
    [string]$Scenario,
    [string]$JobAlias,
    [ValidateSet('APPROVE', 'CLARIFY')]
    [string]$Decision,
    [string]$Requirement,
    [switch]$AcceptAmbiguities,
    [ValidateSet('DIRECT_CODE', 'SPEC_DRIVEN')]
    [string]$Mode = 'DIRECT_CODE',
    [switch]$Approve
)

$ErrorActionPreference = 'Stop'

if (-not $Identity) {
    $Identity = $env:SDLC_USER_IDENTITY
}
if (-not $Identity) {
    $Identity = Read-Host 'Local user label (same value for all requests in this job)'
}
if ([string]::IsNullOrWhiteSpace($Identity)) {
    throw 'An X-User-Identity value is required.'
}

$base = $BaseUrl.TrimEnd('/')
$method = 'GET'
$body = $null

switch ($Action) {
    'New' {
        if (-not $Repository) {
            throw 'New requires -Repository owner/name.'
        }
        if (-not [string]::IsNullOrWhiteSpace($Requirement)) {
            if ($Scenario) {
                throw 'Use either -Requirement or -Scenario for New, not both.'
            }
            $requirementText = $Requirement
        } elseif ($Scenario) {
            $requirementText = switch ($Scenario) {
                'Greenfield' { 'Build a URL shortener with create and resolve APIs, collision-safe codes, configurable expiration, persistent storage, input validation, tests, and API documentation.' }
                'Enhancement' { 'In the existing service, add configurable URL expiration. Expired links must return HTTP 410. Preserve the current stack and API conventions.' }
                'Refactor' { 'Refactor URL resolution to isolate persistence behind the existing repository abstraction. Preserve behavior and add regression tests.' }
                'BugFix' { 'Fix URL short-code creation so concurrent requests cannot persist duplicate codes. Add a regression test for the race and verify the fix.' }
                'TestsOnly' { 'Improve unit and integration coverage for code collisions, expired links, invalid URLs, and not-found behavior. Do not change production behavior unless a test proves a defect.' }
                'DocsOnly' { 'Update the README and API documentation for create, resolve, expiration, error responses, configuration, and local development. Do not change production code or tests.' }
                'Ambiguous' { 'Make links safer.' }
            }
        } else {
            throw 'New requires either -Requirement "what to implement" or -Scenario.'
        }
        $method = 'POST'
        $path = '/api/v3/sdlc/jobs'
        $body = @{
            repositoryId = $Repository
            modelAlias = 'code'
            requirement = $requirementText
        } | ConvertTo-Json -Compress
    }
    'Status' {
        if (-not $JobAlias) { throw 'Status requires -JobAlias.' }
        $path = '/api/v3/sdlc/jobs/' + [uri]::EscapeDataString($JobAlias) + '/status'
    }
    'Review' {
        if (-not $JobAlias -or -not $Decision) { throw 'Review requires -JobAlias and -Decision.' }
        $reviewBody = @{
            decision = $Decision
            acceptAmbiguities = [bool]$AcceptAmbiguities
        }
        if ($Decision -eq 'CLARIFY') {
            if ([string]::IsNullOrWhiteSpace($Requirement)) {
                throw 'Review with -Decision CLARIFY requires -Requirement.'
            }
            $reviewBody.requirement = $Requirement
        }
        $method = 'POST'
        $path = '/api/v3/sdlc/jobs/' + [uri]::EscapeDataString($JobAlias) + '/requirement-review'
        $body = $reviewBody | ConvertTo-Json -Compress
    }
    'Mode' {
        if (-not $JobAlias) { throw 'Mode requires -JobAlias.' }
        $method = 'POST'
        $path = '/api/v3/sdlc/jobs/' + [uri]::EscapeDataString($JobAlias) + '/select-mode'
        $body = @{ mode = $Mode } | ConvertTo-Json -Compress
    }
    'Verify' {
        if (-not $JobAlias) { throw 'Verify requires -JobAlias.' }
        $method = 'POST'
        $path = '/api/v3/sdlc/jobs/' + [uri]::EscapeDataString($JobAlias) + '/verification'
    }
    'PullRequest' {
        if (-not $JobAlias) { throw 'PullRequest requires -JobAlias.' }
        $method = 'POST'
        $path = '/api/v3/sdlc/jobs/' + [uri]::EscapeDataString($JobAlias) + '/pull-request'
        $body = @{ approved = [bool]$Approve } | ConvertTo-Json -Compress
    }
    'Metrics' {
        $path = '/api/v3/sdlc/metrics'
    }
}

$curlArguments = @(
    '--silent', '--show-error', '--fail-with-body',
    '--request', $method,
    '--header', "X-User-Identity: $Identity",
    '--header', 'Accept: application/json'
)
if ($null -ne $body) {
    $curlArguments += @('--header', 'Content-Type: application/json', '--data-binary', '@-')
}
$curlArguments += ($base + $path)

if ($null -ne $body) {
    $previousOutputEncoding = [Console]::OutputEncoding
    try {
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
        $output = $body | & curl.exe @curlArguments
    } finally {
        [Console]::OutputEncoding = $previousOutputEncoding
    }
} else {
    $output = & curl.exe @curlArguments
}
if ($LASTEXITCODE -ne 0) {
    throw "curl.exe returned exit code $LASTEXITCODE. Response: $($output -join [Environment]::NewLine)"
}
$output -join [Environment]::NewLine
