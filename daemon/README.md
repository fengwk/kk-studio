# Daemon

The Environment Daemon runs file, process, LSP and managed IDE capabilities on the host. Java integration tests and E2E fixtures build its binary directly; the IDE extension is JavaScript under `ide-extension/`. Protocol and runtime contracts are shared with Studio through generated protocol code. Capability schemas are copied from the Java classpath resources; the Java consistency test requires the copies to match byte for byte.

## Development

Requirements: Go 1.27.4, `/usr/bin/protoc`, and `protoc-gen-go` v1.36.12 on `PATH`.

From the repository root, generate the control protocol from the platform's single source schema:

```sh
daemon/scripts/generate-protocol.sh
daemon/scripts/check-generated.sh
```

Run the local checks used by CI:

```sh
cd daemon
test -z "$(gofmt -l $(find . -name '*.go'))"
go vet ./...
go run honnef.co/go/tools/cmd/staticcheck@v0.8.2 ./...
CGO_ENABLED=0 go test ./...
```

The standard-library protobuf representation is also tested against all 28 binary fixtures under `platform/daemon-server/src/main/protocol/golden/`. Linux/macOS CI additionally runs `go test -race ./...`. The six cross-build targets are Linux amd64/arm64, macOS amd64/arm64, and Windows amd64/arm64. The GitHub Actions workflow `.github/workflows/daemon-ci.yml` runs these checks on the supported platform matrix.

The daemon executable retains CLI parsing, `--help`, `--version`, read-only `--check-config`, and internal helper commands. The `--config` production path assembles native supervision, the directory lock, LSP and IDE services, control transport, runtime capabilities, resource transfer, skill installation, and update handling. `internal/runtime` owns SETTINGS/ENVIRONMENT_CONFIG readiness, heartbeats, capability invocation, cancellation/replay, result encoding, and host facts; configuration candidates publish LSP generations before the new snapshot becomes active. Bash resolution uses the daemon process `PATH`, and each command still runs through `bash -lc`. The daemon samples the configured Bash login environment at startup and on every configuration application; LSP executable discovery, `read` support checks, and language-server launch use that environment, so commands and LSP see the same `PATH`. Restart the daemon after editing shell login profiles to refresh the LSP side.

### TypeScript LSP profile

The verified TypeScript profile is limited to Linux/amd64 and pins `typescript-language-server@4.3.4` with `typescript@5.8.3` in `internal/lsp/typescript/package-lock.json`. `lsp.TypeScriptLibrary.Install` uses the caller-provided private library directory and `npm ci --ignore-scripts`; it stages beside the version directory, validates installed package versions and the executable, then atomically publishes the immutable profile under a kernel-backed library lock. Its npm cache is confined to removable staging data; it does not modify global npm/HOME/XDG state. The install needs npm and Node.js on `PATH`; the package declares a Node.js 18 minimum, while the real-server handshake in this slice was verified with Node.js 22.23.4. The standalone `--install-lsp` and `--lsp-config` commands do not read the daemon configuration: each samples the default Bash login environment of the invoking user, and npm and Node.js must be visible there.

`lsp.TypeScriptServerConfig` resolves the installed profile to the exact `node <profile>/node_modules/typescript-language-server/lib/cli.mjs --stdio` command and its verified TS/JS extensions and root markers. Callers add that declaration only to the appropriate Environment's `daemonConfig.lsp`; the existing LSP facade/pool owns runtime processes, keeping Environment and canonical workspace roots isolated. Installing materials alone does not enable a language. The automated package install, real definition query and verified exit are opt-in: `KK_STUDIO_TEST_TS_LSP=1 go test ./internal/lsp -run '^TestTypeScriptProfile_RealInstallQueryAndExit$'` (downloads pinned npm packages into a temporary directory).

No other language or platform is declared by this profile.

### Managed Explorer refresh

Successful built-in `write` and `edit` operations notify LSP and queue a coalesced Explorer refresh for each connected view whose workspace contains the resolved file path. The bridge sends only the fixed `refreshExplorer` operation. The extension calls `kk-studio.refreshExplorer` on managed Workbench builds that provide it; this refresh does not reveal the Explorer or save or reload documents. On builds without that command, automatic refresh is unavailable and the manual Explorer refresh still works. Bash and other external writes do not emit this notification.

### Skill installation

The `skill.install` capability accepts arguments exactly `{"transferId":"<lowercase UUID>","package":"<name>","version":"<version>","skill":"<skill name>"}`. `package`, `version`, and `skill` must each be one safe path segment: nonempty, not `.` or `..`, no path separator, and no control character. The runtime requests a `DownloadTicket` through `skill.ResourceDownloader.RequestTicket(ctx, transferId)`; the ticket contains the declared size, SHA-256, and opaque transport data. The installer reuses `<dataDir>/skills/<package>/<version>/<sha256-prefix-12>/` when it already exists, without downloading the archive. Otherwise it calls `Download(ctx, ticket)`, verifies archive size and SHA-256, extracts to a temporary directory in the same parent, validates the requested skill, and renames the staging directory to the content-addressed target. If another installer publishes that target first, the loser removes its staging directory and reuses the existing valid target. The downloader aborts as soon as it reads beyond the ticket size, then verifies exact size and SHA-256; it does not retry or impose an additional archive-size cap. Extraction rejects absolute/traversal paths, backslashes, and symlink entries. Extraction is limited to 10,000 archive entries and 512 MiB expanded bytes. Require `<content-addressed dir>/<skill>/SKILL.md`; return details `{"skill_dir":"<absolute installed skill directory>"}` and one English sentence. Failures return explicit English errors and must not report success.

## Package boundaries

| Package | Responsibility and boundary |
|---|---|
| `cmd/kk-studio-daemon` | Executable entry point and CLI dispatch only. |
| `internal/config` | Startup configuration checks, environment snapshot validation, and daemon release version. |
| `internal/protocol` | Generated protobuf messages; schema is `control.proto`, never edit generated Go directly. |
| `internal/transport` | Network control channel, IDE data routing, and resource clients; implements runtime connection/resource ports and consumes IDE's view resolver. |
| `internal/runtime` | Session/capability orchestration and contracts consumed by feature packages and implemented by transport. |
| `internal/coding` | Filesystem, search, process execution, and output capabilities. |
| `internal/lsp` | Language-server discovery, fixed TypeScript profile materials, process sessions, and capabilities. |
| `internal/ide` | Managed IDE/view lifecycle; implements the transport view-resolution boundary. |
| `internal/native` | Platform process-tree supervision and local OS resources consumed by coding, LSP, and IDE. |
| `internal/skill` | Skill package installation through resource download. |
| `internal/update` | Online update operation and handoff. |
| `internal/h2xconnect` | Go 1.27 Extended CONNECT enablement; remove after Go 1.28 exposes public configuration. |
