@rem A stand-in for claude.exe: answers every message with one recorded turn, and keeps what it is sent in
@rem target/fake-claude-stdin.txt. See FakeClaude.java. Asked `auth status`, it is signed in.
@if "%1"=="auth" (
  echo {"loggedIn": true, "authMethod": "fake"}
  exit /b 0
)
@java "%~dp0FakeClaude.java" "%~dp0replay-map-turn.jsonl" "%~dp0..\..\..\target\fake-claude-stdin.txt"
