@rem A stand-in for claude.exe whose sign-in has lapsed: `auth status` says signed in, and every message is answered
@rem as the real CLI answers one it cannot take, "Not logged in" (replay-not-logged-in.jsonl, recorded). See FakeClaude.java.
@if "%1"=="auth" (
  echo {"loggedIn": true, "authMethod": "fake"}
  exit /b 0
)
@java "%~dp0FakeClaude.java" "%~dp0replay-not-logged-in.jsonl" "%~dp0..\..\..\target\fake-claude-stdin.txt"
