# Shared test server

- Use the existing project-root `run/` for server tests. Read its local
  `README.md` before operating it. CLI, IDE and agents share this instance.
- Start with `./gradlew runServer --console=plain` (Windows:
  `.\gradlew.bat runServer --console=plain`) or Loom's generated server IDE
  configuration. Both must use the same working directory and project classes.
- Check for an existing instance before starting another. Reuse a running
  instance only after confirming it is this project's server.
- Stop gracefully with `stop` and wait for process exit. Do not kill the process
  as a routine shutdown or reset the shared world/configuration for a test.
- Reset or delete test data only within the user's requested scope. Do not
  create backups unless the user explicitly requests them.
- Keep runtime files, credentials and player identities out of Git.
  Do not force-add ignored files or place the project's mod jar in `run/mods/`.
- Document and restore temporary test changes. Fake-player checks do not replace
  real-client rendering and network lifecycle acceptance.
