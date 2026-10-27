@rem A stand-in for claude.exe as fake-claude.cmd, but signed out: `auth status` says so the first time a Maistro asks,
@rem and signed in every time after, as if `claude auth login` had been run in between. See FakeClaude.java.
@if "%1"=="auth" (
  java "%~dp0FakeClaude.java" --auth-status-signed-out-once "%~dp0..\..\..\target\fake-claude-auth.txt"
  exit /b
)
@java "%~dp0FakeClaude.java" "%~dp0replay-map-turn.jsonl" "%~dp0..\..\..\target\fake-claude-stdin.txt"
