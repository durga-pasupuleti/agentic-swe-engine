$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$tokenPointer = [IntPtr]::Zero
$secureToken = $null
Remove-Item Env:GITHUB_PERSONAL_ACCESS_TOKEN -ErrorAction SilentlyContinue
Push-Location $projectRoot

try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Docker CLI was not found. Install Docker Desktop and restart PowerShell.'
    }
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        throw 'Maven was not found. Install Maven and restart PowerShell.'
    }
    $apiListener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    if ($apiListener) {
        throw 'Port 8080 is already in use. Stop the existing local API manually, then rerun this script.'
    }

    $secureToken = Read-Host 'Enter the new fine-grained GitHub token (input hidden)' -AsSecureString
    if ($secureToken.Length -eq 0) {
        throw 'A GitHub token is required to run repository scenarios.'
    }
    $tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
    $env:GITHUB_PERSONAL_ACCESS_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer)
    $tokenPointer = [IntPtr]::Zero
    $secureToken.Dispose()
    $secureToken = $null

    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Desktop is not ready. Start its Linux engine and try again.'
    }

    & docker pull ghcr.io/github/github-mcp-server:v1.13.0
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not pull the official GitHub MCP server image.'
    }

    & docker compose up -d sdlc-prompts-db sdlc-ollama-engine
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not start the local PostgreSQL and Ollama services.'
    }

    $databaseReady = $false
    $databaseDeadline = (Get-Date).AddMinutes(2)
    while ((Get-Date) -lt $databaseDeadline) {
        $health = & docker inspect --format='{{.State.Health.Status}}' sdlc-prompts-db 2>$null
        if ($LASTEXITCODE -eq 0 -and $health.Trim() -eq 'healthy') {
            $databaseReady = $true
            break
        }
        Start-Sleep -Seconds 2
    }
    if (-not $databaseReady) {
        throw 'PostgreSQL did not become healthy within two minutes.'
    }

    $ollamaTags = $null
    $ollamaDeadline = (Get-Date).AddMinutes(2)
    while ((Get-Date) -lt $ollamaDeadline) {
        try {
            $ollamaTags = Invoke-RestMethod -Uri 'http://localhost:11434/api/tags' -TimeoutSec 3
            break
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    if ($null -eq $ollamaTags) {
        throw 'Ollama did not become ready within two minutes.'
    }

    $installedModels = @($ollamaTags.models | ForEach-Object { $_.name })
    foreach ($model in @('qwen2.5-coder:7b', 'llama3.2')) {
        if ($installedModels -notcontains $model -and $installedModels -notcontains "$model`:latest") {
            Write-Host "Downloading local model $model (may take several GB)."
            & docker exec sdlc-ollama-engine ollama pull $model
            if ($LASTEXITCODE -ne 0) {
                throw "Could not download local model $model."
            }
        }
    }

    $env:SDLC_GITHUB_MCP_ENABLED = 'true'
    $env:SDLC_GITHUB_HOST = if ($env:SDLC_GITHUB_HOST) { $env:SDLC_GITHUB_HOST } else { 'https://github.com' }
    $env:SDLC_DOCKER_COMMAND = 'docker'
    $env:SDLC_PROMPT_STORAGE = 'postgres'
    $env:SDLC_PROMPT_DATABASE_URL = 'jdbc:postgresql://localhost:5433/agentic_prompts'
    $env:SDLC_PROMPT_DATABASE_USERNAME = 'sdlc_prompts'
    $env:SDLC_PROMPT_DATABASE_PASSWORD = if ($env:SDLC_PROMPT_DATABASE_PASSWORD) {
        $env:SDLC_PROMPT_DATABASE_PASSWORD
    } else {
        'local-development-only'
    }
    $env:SDLC_OLLAMA_BASE_URL = 'http://localhost:11434'

    Write-Host 'Running local verification. No model inference is used by the test suite.'
    & mvn --batch-mode clean verify
    if ($LASTEXITCODE -ne 0) {
        throw 'Maven verification failed; the engine was not started.'
    }

    Write-Host 'Starting the engine at http://localhost:8080. Press Ctrl+C to stop.'
    & mvn spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw 'The Spring Boot process exited with an error.'
    }
} finally {
    if ($tokenPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer)
    }
    if ($null -ne $secureToken) {
        $secureToken.Dispose()
    }
    Remove-Item Env:GITHUB_PERSONAL_ACCESS_TOKEN -ErrorAction SilentlyContinue
    Pop-Location
}
