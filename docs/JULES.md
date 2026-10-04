# Working with Jules

Jules (Google's asynchronous coding agent, `google-labs-jules[bot]`) wrote the first draft of this app in PR #1. It's currently **off the project**. This page keeps the lines to it open in case it's brought back.

## Channel 1: PR comments (works today, no setup)

Jules reads review comments on PRs it opened, but **only from the GitHub user who started its task** (the repo owner). It adds 👀 when it has read a comment, then pushes commits to the same PR.

- Comment as the owner from a terminal that is logged into `gh`:
  ```powershell
  .\scripts\jules.ps1 pr 1 "Please rename the Room database to grid.db"
  ```
  `@jules` is prefixed automatically.
- For a full review, write markdown and post it:
  ```powershell
  gh pr review <n> --comment --body-file review.md
  ```
  Use `--comment`: GitHub won't let a PR's author "request changes" on their own PR.
- In *Reactive mode* (Jules settings → Pull Request), Jules only acts on comments that mention `@jules`.
- **Jules usually pushes follow-ups as new commits on the existing PR, not as new PRs.** Watch `gh pr view <n> --json commits,updatedAt`.

## Channel 2: the Jules REST API (`scripts/jules.ps1`)

Base URL `https://jules.googleapis.com/v1alpha`. Resources: `sources`, `sessions` (create, get, list, `:sendMessage`, `:approvePlan`), `sessions/*/activities`.

**One-time setup:** create an API key at <https://jules.google.com/settings> (max 3 keys), then either:
```powershell
[Environment]::SetEnvironmentVariable('JULES_API_KEY', '<key>', 'User')   # persistent
# or put JULES_API_KEY=<key> in scripts\.env (git-ignored)
```
A probe on 2026-10-04 with a dummy key returned *"API keys are not supported by this API. Expected OAuth2 access token"*. A real key may still work, as documented. If it doesn't, set `JULES_ACCESS_TOKEN` to an OAuth token for the same Google account, and the script sends it as `Authorization: Bearer`.

| Command | What it does |
|---|---|
| `.\scripts\jules.ps1 sources` | Repos Jules can access (e.g. `sources/github/engineerlogger-spec/grid`) |
| `.\scripts\jules.ps1 sessions` | Recent sessions with their state |
| `.\scripts\jules.ps1 new "<task>" -Branch main -AutoPr` | Start a task; `-AutoPr` makes Jules open a PR; `-NeedApproval` pauses for plan approval |
| `.\scripts\jules.ps1 activity <id> -Follow` | Stream plan, progress, messages and patches until Jules finishes or needs input |
| `.\scripts\jules.ps1 say <id> "<message>"` | Reply inside a session (feedback, answers) |
| `.\scripts\jules.ps1 approve <id>` | Approve a generated plan |
| `.\scripts\jules.ps1 session <id>` | State, web URL, PR links |

Session states: `QUEUED, PLANNING, AWAITING_PLAN_APPROVAL, AWAITING_USER_FEEDBACK, IN_PROGRESS, PAUSED, FAILED, COMPLETED`.

## Channel 3: Jules Tools CLI

Google ships a terminal client: `npm install -g @google/jules` (v0.1.42 at time of writing), then `jules login` (browser sign-in, owner only). It's useful for interactive use. The PowerShell bridge above is better for scripted use from this repo.

## Working agreement if Jules returns

- Jules works on feature branches and opens PRs. The lead developer (Claude) reviews, builds and tests every PR locally with `scripts\run.ps1` before it's merged.
- Give Jules **small, well-specified tasks** that reference the spec in `docs/superpowers/specs/`, and ask for tests with every change.
