[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Repository,

    [string]$Identity,
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ModelAlias = 'code',
    [ValidateRange(1, 120)]
    [int]$TimeoutMinutes = 30,
    [ValidateRange(1, 60)]
    [int]$PollIntervalSeconds = 4
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Identity)) {
    $Identity = $env:SDLC_USER_IDENTITY
}
if ([string]::IsNullOrWhiteSpace($Identity)) {
    $Identity = 'local-user'
}
if ([string]::IsNullOrWhiteSpace($Repository) -or $Repository -notmatch '^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$') {
    throw 'Repository must use owner/repository format.'
}

$base = $BaseUrl.TrimEnd('/')
$headers = @{ 'X-User-Identity' = $Identity; Accept = 'application/json' }
$scenarios = @(
    [pscustomobject]@{
        Name = 'New capability'
        Requirement = 'Add configurable custom aliases for long URLs in this service. Preserve existing create and redirect behavior, define validation and collision handling, and add tests and API documentation.'
    },
    [pscustomobject]@{
        Name = 'Existing behavior change'
        Requirement = 'In the existing service, make expiration handling deterministic at the exact expiry instant. Preserve the current API contract and add regression tests using the existing clock and repository patterns.'
    },
    [pscustomobject]@{
        Name = 'Ambiguous request'
        Requirement = 'Make URL management safer.'
    }
)

Write-Host "Checking engine at $base ..."
$null = Invoke-RestMethod -Method Get -Uri "$base/api/v3/sdlc/metrics" -Headers $headers

$results = [System.Collections.Generic.List[object]]::new()
foreach ($scenario in $scenarios) {
    Write-Host "`nSubmitting: $($scenario.Name)"
    $payload = @{
        repositoryId = $Repository
        modelAlias = $ModelAlias
        requirement = $scenario.Requirement
    } | ConvertTo-Json -Compress

    $accepted = Invoke-RestMethod -Method Post -Uri "$base/api/v3/sdlc/jobs" `
        -Headers $headers -ContentType 'application/json' -Body $payload
    $result = [pscustomobject]@{
        Name = $scenario.Name
        Requirement = $scenario.Requirement
        JobAlias = $accepted.jobAlias
        StatusUrl = $accepted.checkStatusUrl
        Status = 'QUEUED'
        StatusData = $null
        Error = ''
        LastProgress = ''
    }
    $results.Add($result)
    Write-Host "Accepted $($result.JobAlias)"

    $deadline = [DateTimeOffset]::UtcNow.AddMinutes($TimeoutMinutes)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $status = Invoke-RestMethod -Method Get -Uri ($base + $result.StatusUrl) -Headers $headers
            $result.StatusData = $status
            $result.Status = $status.status
            $latestEvent = @($status.auditTrail | Select-Object -Last 1)
            $stageProgress = if ($latestEvent.Count -gt 0) {
                "$($latestEvent[0].stage): $($latestEvent[0].event)"
            } else {
                'Waiting for workflow events'
            }
            $progressText = "$($result.Name) [$($result.JobAlias)]: $($result.Status) | $stageProgress"
            if ($progressText -ne $result.LastProgress) {
                Write-Host $progressText
                $result.LastProgress = $progressText
            }

            if ($result.Status -in @('PAUSED_AT_REQUIREMENT_REVIEW', 'FAILED', 'COMPLETED', 'REJECTED')) {
                break
            }
        } catch {
            $result.Error = $_.Exception.Message
            $result.Status = 'STATUS_ERROR'
            Write-Warning "$($result.Name) [$($result.JobAlias)]: $($result.Error)"
            break
        }
        Start-Sleep -Seconds $PollIntervalSeconds
    }

    if ($result.Status -eq 'QUEUED' -or $result.Status -notin @(
            'PAUSED_AT_REQUIREMENT_REVIEW', 'FAILED', 'COMPLETED', 'REJECTED', 'STATUS_ERROR')) {
        $result.Status = 'TIMEOUT'
        Write-Warning "$($result.Name) [$($result.JobAlias)] did not reach a review gate within $TimeoutMinutes minutes."
    }
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$reportDirectory = Join-Path $projectRoot 'data\demo-runs'
New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
$reportPath = Join-Path $reportDirectory ("review-demo-{0}.md" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
$report = [System.Collections.Generic.List[string]]::new()
$report.Add('# Agentic SWE Engine Reviewer Demo')
$report.Add('')
$report.Add("- Repository: ``$Repository``")
$report.Add("- Started: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')")
$report.Add("- Model alias: ``$ModelAlias``")
$report.Add('- Stop point: requirement review; implementation and pull-request approvals were not submitted.')

foreach ($result in $results) {
    $report.Add('')
    $report.Add("## $($result.Name)")
    $report.Add('')
    $report.Add("- Job: ``$($result.JobAlias)``")
    $report.Add("- Status: ``$($result.Status)``")
    $report.Add("- Requirement: $($result.Requirement)")
    if ($result.Error) {
        $report.Add("- Error: $($result.Error)")
    }

    $status = $result.StatusData
    if ($null -eq $status) {
        continue
    }
    $report.Add("- Change classification: ``$($status.changeClassification)``")
    $report.Add("- Repository fit: ``$($status.repositoryFit)``")
    $report.Add("- Repository fit reason: $($status.repositoryFitReason)")
    $report.Add("- Branch: ``$($status.branch)``")
    $report.Add('')
    $report.Add('### Normalized requirement')
    $report.Add('')
    $report.Add([string]$status.normalizedRequirement)
    foreach ($section in @(
            @{ Title = 'Acceptance criteria'; Values = @($status.acceptanceCriteria) },
            @{ Title = 'Ambiguities'; Values = @($status.ambiguities) },
            @{ Title = 'Assumptions'; Values = @($status.assumptions) },
            @{ Title = 'Risks'; Values = @($status.identifiedRisks) })) {
        $report.Add('')
        $report.Add("### $($section.Title)")
        $report.Add('')
        if ($section.Values.Count -eq 0) {
            $report.Add('- None reported')
        } else {
            foreach ($value in $section.Values) {
                $report.Add("- $value")
            }
        }
    }
    $report.Add('')
    $report.Add('### Architecture plan')
    $report.Add('')
    $report.Add([string]$status.architecturePlan)
    $report.Add('')
    $report.Add('### Task decomposition')
    $report.Add('')
    $report.Add([string]$status.taskDecomposition)
    if ($status.compilerLogs) {
        $report.Add('')
        $report.Add('### Engine message')
        $report.Add('')
        $report.Add([string]$status.compilerLogs)
    }
}

Set-Content -LiteralPath $reportPath -Value $report -Encoding UTF8
Write-Host "`nDemo complete. Reviewer report: $reportPath"
Write-Host 'Jobs stop at requirement review; inspect the plans before approving any job.'