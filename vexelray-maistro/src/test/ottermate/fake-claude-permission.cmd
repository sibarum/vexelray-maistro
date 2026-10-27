@rem A stand-in for claude.exe whose every turn asks to run Bash and waits for the answer. See FakeClaude.java.
@rem Asked `auth status`, it is signed in.
@if "%1"=="auth" (
  echo {"loggedIn": true, "authMethod": "fake"}
  exit /b 0
)
@java "%~dp0FakeClaude.java" "%~dp0replay-permission-turn.jsonl" "%~dp0..\..\..\target\fake-claude-stdin.txt"
