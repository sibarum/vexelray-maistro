@rem A stand-in for claude.exe as fake-claude.cmd, but signed out until `auth login` signs it in. The first `auth login`
@rem a Maistro runs waits until it is ended, as if the person never finished in the browser; every one after signs in
@rem at once. Both print the sign-in page's address as the real CLI does. See FakeClaude.java.
@if "%1"=="auth" if "%2"=="login" (
  java "%~dp0FakeClaude.java" --auth-login "%~dp0..\..\..\target\fake-claude-login.txt"
  exit /b
)
@if "%1"=="auth" (
  java "%~dp0FakeClaude.java" --auth-status-after-login "%~dp0..\..\..\target\fake-claude-login.txt"
  exit /b
)
@java "%~dp0FakeClaude.java" "%~dp0replay-map-turn.jsonl" "%~dp0..\..\..\target\fake-claude-stdin.txt"
