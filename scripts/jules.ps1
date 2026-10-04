# Bridge to Jules (Google's coding agent). Two channels:
#   1. Jules REST API v1alpha  — needs an API key from https://jules.google.com/settings
#      (set $env:JULES_API_KEY, or put JULES_API_KEY=... in scripts/.env, which is git-ignored)
#   2. GitHub PR comments      — via `gh`, as the repo owner (Jules only obeys the user who started the task)
#
# Usage:
#   .\scripts\jules.ps1 sources                                  # repos Jules can work on
#   .\scripts\jules.ps1 sessions                                 # recent sessions + state
#   .\scripts\jules.ps1 session  <id>                            # one session (state, url, PR outputs)
#   .\scripts\jules.ps1 new "<prompt>" [-Branch main] [-Title t] [-AutoPr] [-NeedApproval]
#   .\scripts\jules.ps1 say      <id> "<message>"                # send a message into a session
#   .\scripts\jules.ps1 activity <id> [-Follow]                  # plan / progress / messages / patches
#   .\scripts\jules.ps1 approve  <id>                            # approve a pending plan
#   .\scripts\jules.ps1 pr       <pr#> "<comment>"               # talk to Jules on one of its PRs
param(
    [Parameter(Position = 0, Mandatory)][ValidateSet('sources', 'sessions', 'session', 'new', 'say', 'activity', 'approve', 'pr')]
    [string]$Command,
    [Parameter(Position = 1)][string]$Arg1,
    [Parameter(Position = 2)][string]$Arg2,
    [string]$Branch = 'main',
    [string]$Title,
    [string]$Repo = 'engineerlogger-spec/grid',
    [switch]$AutoPr,
    [switch]$NeedApproval,
    [switch]$Follow
)
$ErrorActionPreference = 'Stop'
$Base = 'https://jules.googleapis.com/v1alpha'

function Get-Setting([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ($value) { return $value }
    $envFile = Join-Path $PSScriptRoot '.env'
    if (Test-Path $envFile) {
        $line = Get-Content $envFile | Where-Object { $_ -match "^\s*$Name\s*=" } | Select-Object -First 1
        if ($line) { return ($line -split '=', 2)[1].Trim() }
    }
}

# Jules documents an API key (x-goog-api-key). An OAuth token (e.g. `gcloud auth print-access-token`)
# is accepted as a fallback, since the endpoint rejects unrecognised keys with "expected OAuth2".
function Get-AuthHeaders {
    $key = Get-Setting 'JULES_API_KEY'
    if ($key) { return @{ 'x-goog-api-key' = $key } }
    $token = Get-Setting 'JULES_ACCESS_TOKEN'
    if ($token) { return @{ Authorization = "Bearer $token" } }
    throw 'No Jules credentials. Create an API key at https://jules.google.com/settings and set $env:JULES_API_KEY (or JULES_API_KEY=... in scripts/.env). Alternatively set JULES_ACCESS_TOKEN to an OAuth access token.'
}

function Invoke-Jules([string]$Method, [string]$Path, $Body) {
    $params = @{ Method = $Method; Uri = "$Base/$Path"; Headers = (Get-AuthHeaders) }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Depth 10); $params.ContentType = 'application/json' }
    Invoke-RestMethod @params
}

function Get-SessionId([string]$IdOrName) {
    if (-not $IdOrName) { throw 'Session id required' }
    $IdOrName -replace '^sessions/', ''
}

function Get-SourceName {
    # Sources are named like sources/github/<owner>/<repo>; resolve the one matching -Repo.
    $all = (Invoke-Jules GET 'sources?pageSize=100').sources
    $match = $all | Where-Object { $_.name -like "*/$Repo" } | Select-Object -First 1
    if (-not $match) { throw "Jules has no access to $Repo. Connect it in the Jules web app first. Sources: $($all.name -join ', ')" }
    $match.name
}

function Format-Activity($a) {
    $when = ([datetime]$a.createTime).ToLocalTime().ToString('HH:mm')
    switch ($true) {
        { $a.agentMessaged }    { "[$when] Jules: $($a.agentMessaged.agentMessage)"; break }
        { $a.userMessaged }     { "[$when] You:   $($a.userMessaged.userMessage)"; break }
        { $a.planGenerated }    { "[$when] PLAN:"; $a.planGenerated.plan.steps | ForEach-Object { "         $($_.index + 1). $($_.title)" }; break }
        { $a.planApproved }     { "[$when] plan approved"; break }
        { $a.progressUpdated }  { "[$when] … $($a.progressUpdated.title)"; break }
        { $a.sessionCompleted } { "[$when] ✔ session completed"; break }
        { $a.sessionFailed }    { "[$when] ✘ session failed: $($a.sessionFailed.reason)"; break }
        default                 { "[$when] $($a.description)" }
    }
    foreach ($art in @($a.artifacts)) {
        if ($art.changeSet.gitPatch) { "         patch: $($art.changeSet.gitPatch.suggestedCommitMessage)" }
        if ($art.bashOutput) { "         $ $($art.bashOutput.command)  (exit $($art.bashOutput.exitCode))" }
    }
}

switch ($Command) {
    'sources' {
        (Invoke-Jules GET 'sources?pageSize=100').sources | ForEach-Object { $_.name }
    }
    'sessions' {
        (Invoke-Jules GET 'sessions?pageSize=20').sessions |
            Select-Object id, state, title, @{ n = 'updated'; e = { ([datetime]$_.updateTime).ToLocalTime() } } | Format-Table -AutoSize
    }
    'session' {
        $s = Invoke-Jules GET "sessions/$(Get-SessionId $Arg1)"
        [pscustomobject]@{ Id = $s.id; State = $s.state; Title = $s.title; Url = $s.url; PRs = ($s.outputs.pullRequest.url -join ', ') } | Format-List
    }
    'new' {
        if (-not $Arg1) { throw 'Prompt required: .\scripts\jules.ps1 new "<prompt>"' }
        $body = @{
            prompt              = $Arg1
            sourceContext       = @{ source = (Get-SourceName); githubRepoContext = @{ startingBranch = $Branch } }
            requirePlanApproval = [bool]$NeedApproval
        }
        if ($Title) { $body.title = $Title }
        if ($AutoPr) { $body.automationMode = 'AUTO_CREATE_PR' }
        $s = Invoke-Jules POST 'sessions' $body
        "Created session $($s.id): $($s.url)"
    }
    'say' {
        if (-not $Arg2) { throw 'Usage: say <sessionId> "<message>"' }
        Invoke-Jules POST "sessions/$(Get-SessionId $Arg1):sendMessage" @{ prompt = $Arg2 } | Out-Null
        'Sent. Jules replies as a new activity — run: activity <id> -Follow'
    }
    'approve' {
        Invoke-Jules POST "sessions/$(Get-SessionId $Arg1):approvePlan" @{} | Out-Null
        'Plan approved.'
    }
    'activity' {
        $id = Get-SessionId $Arg1
        $seen = @{}
        do {
            $acts = (Invoke-Jules GET "sessions/$id/activities?pageSize=100").activities | Sort-Object createTime
            foreach ($a in $acts) { if (-not $seen[$a.id]) { $seen[$a.id] = $true; Format-Activity $a } }
            if (-not $Follow) { break }
            $state = (Invoke-Jules GET "sessions/$id").state
            if ($state -in 'COMPLETED', 'FAILED') { "Session $state."; break }
            if ($state -in 'AWAITING_PLAN_APPROVAL', 'AWAITING_USER_FEEDBACK') { "Jules is waiting on you ($state)."; break }
            Start-Sleep -Seconds 20
        } while ($true)
    }
    'pr' {
        if (-not $Arg2) { throw 'Usage: pr <prNumber> "<comment>"' }
        $text = if ($Arg2 -match '@jules') { $Arg2 } else { "@jules $Arg2" }
        gh pr comment $Arg1 --repo $Repo --body $text
    }
}
