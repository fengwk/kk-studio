# Go Daemon

Go rewrite of the Environment Daemon, currently an engineering skeleton alongside the TypeScript implementation. Migration behavior and acceptance contracts are specified in `.workspace/rebuild-1.0/daemon-go/`; those specifications remain authoritative.

## Development

Requirements: Go 1.27.4, `/usr/bin/protoc`, and `protoc-gen-go` v1.36.12 on `PATH`.

From the repository root, generate the control protocol from the platform's single source schema:

```sh
daemon-go/scripts/generate-protocol.sh
daemon-go/scripts/check-generated.sh
```

Run the local checks used by CI:

```sh
cd daemon-go
test -z "$(gofmt -l $(find . -name '*.go'))"
go vet ./...
go run honnef.co/go/tools/cmd/staticcheck@v0.8.2 ./...
CGO_ENABLED=0 go test ./...
```

The standard-library protobuf representation is also tested against all 28 binary fixtures under `platform/daemon-server/src/main/protocol/golden/`. Linux/macOS CI additionally runs `go test -race ./...`. The six cross-build targets are Linux amd64/arm64, macOS amd64/arm64, and Windows amd64/arm64. The GitHub Actions workflow `.github/workflows/daemon-go-ci.yml` runs these checks on the supported platform matrix.

The runtime partition implements CLI parsing, `--help`, `--version`, read-only `--check-config`, configuration validation, and an injected `RuntimeRunner` boundary for service execution. `internal/runtime` currently provides an injected single-connection session with SETTINGS/ENVIRONMENT_CONFIG readiness, heartbeats, capability invocation, cancellation/replay, result encoding, and host facts. Reconnection, full configuration candidate publication, managed updates, and final service assembly remain integration work.

### Runtime-partition behavior change

**RUN-N-004 — `skill.install` contract.** Arguments are exactly `{"transferId":"<lowercase UUID>","package":"<name>","version":"<version>","skill":"<skill name>"}`. `package`, `version`, and `skill` must each be one safe path segment: nonempty, not `.` or `..`, no path separator, and no control character. The runtime requests a `DownloadTicket` through `skill.ResourceDownloader.RequestTicket(ctx, transferId)`; the ticket contains the declared size, SHA-256, and opaque transport data. The installer reuses `<dataDir>/skills/<package>/<version>/<sha256-prefix-12>/` when it already exists, without downloading the archive. Otherwise it calls `Download(ctx, ticket)`, verifies archive size and SHA-256, extracts to a temporary directory in the same parent, validates the requested skill, and renames the staging directory to the content-addressed target. If another installer publishes that target first, the loser removes its staging directory and reuses the existing valid target. Extraction rejects absolute/traversal paths, backslashes, and symlink entries. Limits are 10,000 archive entries, 128 MiB compressed bytes, and 512 MiB expanded bytes. Require `<content-addressed dir>/<skill>/SKILL.md`; return details `{"skill_dir":"<absolute installed skill directory>"}` and one English sentence. Failures return explicit English errors and must not report success.

## Package boundaries

| Package | Responsibility and boundary |
|---|---|
| `cmd/kk-studio-daemon` | Executable entry point and CLI dispatch only. |
| `internal/config` | Configuration boundary and daemon release version. |
| `internal/protocol` | Generated protobuf messages; schema is `control.proto`, never edit generated Go directly. |
| `internal/transport` | Network control channel, IDE data routing, and resource clients; implements runtime connection/resource ports and consumes IDE's view resolver. |
| `internal/runtime` | Session/capability orchestration and contracts consumed by feature packages and implemented by transport. |
| `internal/coding` | Filesystem, search, process execution, and output capabilities. |
| `internal/lsp` | Language-server discovery, process sessions, and capabilities. |
| `internal/ide` | Managed IDE/view lifecycle; implements the transport view-resolution boundary. |
| `internal/native` | Platform process-tree supervision and local OS resources consumed by coding, LSP, and IDE. |
| `internal/skill` | Skill package installation through resource download. |
| `internal/update` | Online update operation and handoff. |
| `internal/h2xconnect` | Go 1.27 Extended CONNECT enablement; remove after Go 1.28 exposes public configuration. |
