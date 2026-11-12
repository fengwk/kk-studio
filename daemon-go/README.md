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

During this skeleton phase the executable supports `--help` and `--version`; `--config` and `--check-config` return explicit not-implemented errors until the runtime partition implements their behavior.

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
