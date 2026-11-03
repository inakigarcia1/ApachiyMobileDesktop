---
name: apachiy-subsync-loop
description: Autonomous desktop QA loop for Apachiy Spanish subtitle sync via embedded SRT + ffsubsync. Use when debugging subtitle sync, Smallville playback, FF Subsync, addon reference POSTs, or running the agent-QA sidecar on Apachiy Desktop.
---

# Apachiy subtitle sync loop (desktop)

Run this skill to the letter. Do not ask the user which episode to play. Do not judge sync from screenshots. Do not type passwords into chat.

## Goal

Play **new Smallville episodes** on Apachiy Desktop until **3 consecutive PASS** (ffsubsync ran and score is good), or a **30 minute budget** expires. Then stop cleanly and report.

PASS = pipeline `applied` + `syncScorePass=true` in `player-state.json` (or `sync-score.json`). Overlay screenshots are evidence only.

## Hard limits

- Budget: **30 minutes** from `qa/runs/<runId>/loop-state.json` `startedAtEpochMs`.
- Never kill Gradle, Docker, or the player while a seek/sample/compile is in flight.
- Before starting a **new** episode: if elapsed ≥ 22 min, do not start it. Finish current samples if any, write `report.md`, stop.
- If a code fix is compiling when the clock hits 30: wait for that build to finish or fail, leave desktop runnable, then stop. Do not start playback after that.
- Do not `docker compose down`.

## One-time setup (every session)

1. Read `qa/apachiy-subsync-loop/secrets.local.json` (never print passwords in the report).
2. From `Apachiy-DesktopMobile`: `.\scripts\bootstrap-subsync-loop.ps1 -SkipDesktop` then, if `qa/runs/current/app-state.json` or `heartbeat.json` is missing / older than 20s, start desktop **in the background**:
   `.\scripts\run-desktop-qa.ps1`
   First Gradle launch can take several minutes. Wait for `qa/runs/current/app-state.json` with a fresh `updatedAtEpochMs`.
3. Oracle dir: `qa/runs/current/` (`app-state.json`, `player-state.json`, `sync-score.json`, `reference.srt`, `applied.srt`, `events.ndjson`, `command.json`, `command-ack.json`).
4. Init `qa/runs/<runId>/loop-state.json` if missing (runId = `yyyyMMdd-HHmm`) with `startedAtEpochMs`, `consecutivePass: 0`, `usedEpisodeIds: []`.

DB (full access, no host password):

```
docker exec -i apachiy-supabase-db psql -U supabase_admin -d postgres
```

Host: `localhost:54322`. Use it freely for local users, cache, membership (`apachiy."Users"`), profiles.

## Login (do not use Cua first)

Auto-login is built into the QA sidecar (`APACHIY_AGENT_QA_ACCOUNTS_JSON` from `secrets.local.json`). Wait until `qa/runs/current/app-state.json` shows `auth=authenticated` and `gate=Main` (or `autoLogin=ok`).

Only if after ~45s `autoLogin=failed` and `gate=Auth`: use Cua on the Apachiy window. Fields **Email**, **Password**, button **Sign In**. Accounts in `secrets.local.json` `accounts[]` order. Compose Desktop is a Skia canvas — UIA may be empty; then `get_window_state` screenshot + `delivery_mode: foreground` click/type. If a profile picker appears, click the first profile without PIN.

Cua session label: `apachiy-qa`. Call `start_session` once. Prefer `element_index`; video canvas uses `x,y` then `foreground` only after `background_unavailable`.

## Episode policy

- Inventory: `qa/apachiy-subsync-loop/smallville-episodes.json` (`type`, `imdbId`, season/episode).
- Each iteration: first unused `episodeId`. Never replay an episode in the same run (subtitle cache key is url|hash|size|file).
- **Open playback with the sidecar, not Search/Cua:**

```
.\scripts\write-agent-command.ps1 -Action play_episode -MetaType series -MetaId tt0279600 -Season <n> -Episode <n>
```

Wait for `command-ack.json` result `ok` (or `queued` then `ok`). Then wait for `player-state.json` heartbeat.

- SKIP (`embedded_spanish`, `no_usable_reference`, `missing_ids`): mark used, do **not** increment PASS, do **not** change code, next episode.
- FAIL: reset `consecutivePass` to 0, hypothesize, smallest code fix, rebuild via `run-desktop-qa.ps1`, **next unused episode**.

## After Play — oracle, not Cua

Wait until `pipeline` is `applied`, `skipped`, or `failed`, or 180s timeout (`waiting_addon` can take minutes; wait up to 240s for that state).

Commands (do not click the timeline):

```
.\scripts\write-agent-command.ps1 -Action play
.\scripts\write-agent-command.ps1 -Action seek -PositionMs <ms>
```

Read `command-ack.json` then a fresh `player-state.json`.

Sample 5 points at 12%, 28%, 45%, 62%, 80% of `durationMs` (skip if duration is 0). After each seek, wait until `|positionMs - target| < 2500` or 8s.

### Verdict

- **PASS**: `pipeline=applied` AND `syncScorePass=true` AND at least 3/5 samples have `cueCoversClock=true`. Increment `consecutivePass`.
- **FAIL-pipeline**: `failed` / timeout / `no_spanish_addon`. Classify from `skipReason` + `events.ndjson`.
- **FAIL-sync**: applied but `syncScorePass=false` or overlay never covers clock. Look at extractor, cache key, delay, community-subs/ffsubsync.
- **SKIP**: `embedded_spanish` or unusable reference.

If `consecutivePass==3`: stop with **SUCCESS**. First line of the final message:

`SUCCESS: 3 capítulos de Smallville con ffsubsync OK`

## Code changes

Only after a FAIL. Keep diffs small. Prefer DesktopMobile player/extractor first; API/community-subs only if evidence is there. After rebuild, always a **new** episode.

## 30 min stop (graceful)

Write `qa/runs/<runId>/report.md` with: elapsed, episodes tried, PASS/FAIL/SKIP, last pipeline, last sync-score, files changed, leftover Gradle/desktop state.

First line if budget hit:

`STOPPED: presupuesto 30 min — estado coherente, sin corte a medias`

Leave Apachiy Desktop running if it is already up.

## Cua rules

- Prefer the sidecar (`play_episode` / seek / play) over GUI.
- Do not drive apps other than Apachiy.
- Do not type secrets into chat. Do not take over the user's browser/email.

## Notify

The final chat message is the notification. Be explicit. Do not wait for the user.
