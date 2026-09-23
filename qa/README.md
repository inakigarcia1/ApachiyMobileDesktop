# Agent QA — Smallville subtitle sync

Sidecar + Cursor skill for unattended desktop debugging of Spanish addon subs (embedded SRT reference → ffsubsync).

## Fire the loop

1. Open the monorepo `D:\Proyectos\Apachiy-Repos\Apachiy-DesktopMobile`. Add `D:\Proyectos\Apachiy-Repos\Apachiy` as a second root only if the loop needs to patch the API / community-subs.
2. New Agent chat, **Auto-run** (shell + Cua MCP). Approve Cua if the host asks once.
3. Model: **Grok 4.6**.
4. Paste the contents of `qa/PROMPT.md`.

The human should not pick episodes, type passwords, or start Docker. The loop agent does that.

## Oracle files

Written when desktop is launched with `.\scripts\run-desktop-qa.ps1`:

- `qa/runs/current/app-state.json` — gate/auth/autoLogin heartbeat
- `qa/runs/current/player-state.json`
- `qa/runs/current/sync-score.json`
- `qa/runs/current/reference.srt` / `applied.srt`
- `qa/runs/current/command.json` (you write, player/app consume)

## Scripts

- `scripts/ensure-local-stack.ps1` — Supabase + API if down
- `scripts/bootstrap-subsync-loop.ps1` — stack + QA users/passwords/profiles (+ desktop unless `-SkipDesktop`)
- `scripts/run-desktop-qa.ps1` — desktop + sidecar
- `scripts/write-agent-command.ps1` — seek/play/pause/play_episode/open_meta
