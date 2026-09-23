Usá el skill `apachiy-subsync-loop`.

Objetivo: debuguear de forma autónoma la sync de subtítulos españoles en Apachiy Desktop. En cada iteración que requiera corrección, reproducí un episodio **nuevo** de Smallville (inventario `qa/apachiy-subsync-loop/smallville-episodes.json`, IMDb `tt0279600`) para disparar SRT embebido → POST reference → ffsubsync.

No me preguntes episodios ni cuentas. Login automático con `qa/apachiy-subsync-loop/secrets.local.json`. Abrí cada capítulo con:

`.\scripts\write-agent-command.ps1 -Action play_episode -MetaType series -MetaId tt0279600 -Season N -Episode N`

Repo: `Apachiy-DesktopMobile` (monorepo desktop+mobile). La API está en `Apachiy` si hace falta un fix de community-subs.

Si el stack Docker (Supabase + API) no está arriba, `.\scripts\bootstrap-subsync-loop.ps1 -SkipDesktop` y después `.\scripts\run-desktop-qa.ps1` en background. Tenés acceso completo a Postgres via `docker exec -i apachiy-supabase-db psql -U supabase_admin -d postgres`.

Juez: `qa/runs/current/player-state.json` y `sync-score.json`, no visión. Seek/play con `.\scripts\write-agent-command.ps1`. Cua solo si el auto-login falla (`app-state.json` gate=Auth).

Parar cuando haya **3 PASS seguidos** (pipeline applied + syncScorePass) o a los **30 minutos sin cortar a medias**. Avisame en el chat con SUCCESS o STOPPED.
