Run build, test, git, package-manager, move/copy, and external CLI commands.

Shell: ${shell}

Usage:
- Use `bash` for commands, not as the default way to read, search, or edit repository files.
- Every call must include `workdir`. It must be an expanded absolute directory on the target daemon's file system; no call inherits a previous directory, session default, environment root, cwd, or home directory.
- Use Unix paths such as `/srv/project` on Unix targets and drive-rooted or UNC paths such as `C:/src/project` or `//server/share/project` on Windows targets.
- Commands run in a shell environment intended to be close to the user's terminal.
- Long-running commands (e.g. builds, tests, large migrations, `mvn`, `gradle`, `docker build`) must explicitly pass a larger `timeout_seconds` if they may exceed the default.
- Before a command creates repository files or directories, confirm the target parent location with the file tools.
- Quote file paths that contain spaces.
- When commands are independent, prefer separate parallel tool calls. When one shell step depends on a previous step, chain them with `&&`; use `;` only when failure of earlier steps does not matter.
- Use a temporary directory outside the repository for downloads, generated artifacts, temporary clones, and other non-target side effects unless the user explicitly wants files created in the project.

Examples:
- `bash({ command: "npm test", workdir: "/srv/project/packages/web" })`
- `bash({ command: "mvn -q test", workdir: "/srv/project/services/java", timeout_seconds: 900 })`
- `bash({ command: "git status --short", workdir: "/srv/project" })`
- `bash({ command: "mkdir -p build && cp \"source file.txt\" build/", workdir: "/srv/project/packages/app" })`
- `bash({ command: "mv src/old.ts src/archive/old.ts", workdir: "C:/src/project/services/api" })`
- `bash({ command: "cp \"source file.txt\" \"target file.txt\"", workdir: "/tmp/anydir" })`
