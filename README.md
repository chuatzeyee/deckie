# Deckie

A native Android app for keyboard phones (built for the Unihertz Titan 2) that
lets you watch and drive [Claude Code](https://claude.com/claude-code) sessions
running in tmux on your own server, over SSH.

If you run several Claude Code workflows, one tmux session each, Deckie gives
you one screen to switch between them and tells you which one needs you.

## Features

- **Session rail + live terminal.** Your chosen tmux sessions on the left, the
  selected session's live terminal on the right.
- **Status at a glance.** Orange when Claude is waiting for you (a question or
  a finished turn), bright green while working, dim green when idle.
- **One-tap answers.** Claude's numbered questions become buttons. Before
  sending, Deckie re-reads the screen and refuses if the question changed.
- **Keyboard first.** Sym+J / Sym+K next/previous session, Sym+letter jumps to
  a session, Sym+M opens the session menu. An on-screen row adds Esc, Ctrl,
  Tab and arrows; swipe to scroll tmux history.
- **Session management.** Create (optionally starting `claude`), rename and
  kill sessions.
- **Background notifications** when a session starts waiting or finishes.
- **Nothing installed on the server.** Deckie only runs `tmux` commands over
  SSH. Nothing is hardcoded: you enter host, port, user and key in the app.

## Security model

- SSH with an Ed25519 key generated on the phone (or imported). Private keys
  are encrypted at rest with an Android Keystore key and excluded from backup.
- Trust on first use: the server's host-key fingerprint is shown and must be
  accepted; a changed key is refused until you explicitly re-trust it.
- Only outbound SSH. Prefer a private network (e.g. a VPN/tailnet) over
  exposing SSH publicly.

## Build

Requirements: JDK 17+, Android SDK (compileSdk 37), Gradle wrapper included.

```sh
cd android
echo "sdk.dir=/path/to/android/sdk" > local.properties
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First run

1. Enter a name, address, port and user, then **Generate new key**.
2. Add the shown public key line to `~/.ssh/authorized_keys` on the server.
3. **Test connection**, compare and trust the host-key fingerprint
   (`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on the server).
4. Pick the tmux sessions to show.

## Credits

- Claude question detection and safe-answer logic ported from
  [deckaway](https://github.com/matacoder/deckaway) (MIT) — see `android/NOTICE`.
- Terminal: [ConnectBot termlib](https://github.com/connectbot) (Apache-2.0).
- SSH: [sshj](https://github.com/hierynomus/sshj) (Apache-2.0) and BouncyCastle.
