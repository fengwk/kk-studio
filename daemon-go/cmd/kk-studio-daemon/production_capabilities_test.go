package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/capability"
	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/lsp"
	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	daemonruntime "github.com/fengwk/kk-studio/daemon/internal/runtime"
	"google.golang.org/protobuf/proto"
)

const productionCapabilityTimeout = 10 * time.Second

// TestProductionCapabilitiesConfirmExecutionExit drives every capability registered by the
// production assembly through the real runtime. A capability that never confirms its
// execution scope fails on the missing EXECUTION_EXITED frame.
func TestProductionCapabilitiesConfirmExecutionExit(t *testing.T) {
	underTest := newProductionRuntimeUnderTest(t)
	fixture := prepareProductionCapabilityFixture(t)
	arguments := productionCapabilityArguments(t, fixture)
	registered := make(map[string]bool, len(underTest.declarations))
	for _, declaration := range underTest.declarations {
		registered[declaration.ID] = true
	}
	for _, id := range []string{
		"fs.read", "fs.write", "fs.write.preview", "fs.edit", "fs.edit.preview", "fs.find", "fs.grep",
		"process.exec", "lsp.goto-definition", "lsp.workspace-symbols", "lsp.java-decompile", "skill.install",
	} {
		if !registered[id] {
			t.Fatalf("production capability registration is missing %s", id)
		}
	}

	for index, declaration := range underTest.declarations {
		declaration := declaration
		t.Run(declaration.ID, func(t *testing.T) {
			invocation := fmt.Sprintf("capability-exit-%03d", index)
			argumentsJSON := arguments[declaration.ID]
			if argumentsJSON == "" {
				argumentsJSON = "{}"
			}
			if declaration.ID == "process.exec" {
				requireBash(t, underTest.bash)
			}
			frames := invokeProductionCapability(t, underTest, invocation, declaration.ID, argumentsJSON)
			terminals := countInvocationFrames(frames, invocation, protocol.MessageType_COMPLETED, protocol.MessageType_FAILED, protocol.MessageType_CANCELLED)
			if terminals != 1 {
				t.Fatalf("%s terminal frames = %d, want exactly one: %v", declaration.ID, terminals, frames)
			}
			if exits := countInvocationFrames(frames, invocation, protocol.MessageType_EXECUTION_EXITED); exits != 1 {
				t.Fatalf("%s EXECUTION_EXITED frames = %d, want exactly one: %v", declaration.ID, exits, frames)
			}
		})
	}
}

// TestProductionCapabilitiesCancel pins the caller-cancellation contract for the two
// long-running production capabilities: one CANCELLED terminal, no COMPLETED, and one
// EXECUTION_EXITED after the executor has left its scope.
func TestProductionCapabilitiesCancel(t *testing.T) {
	underTest := newProductionRuntimeUnderTest(t)
	root := t.TempDir()

	t.Run("process.exec", func(t *testing.T) {
		requireBash(t, underTest.bash)
		const invocation = "capability-cancel-process"
		arguments := productionArguments(t, map[string]any{
			"command": "printf started; while :; do :; done",
			"workdir": root,
		})
		invokeProductionCapabilityAsync(t, underTest, invocation, "process.exec", arguments)
		waitInvocationFrame(t, underTest, invocation, func(envelope *protocol.Envelope) bool {
			return envelope.MessageType == protocol.MessageType_PROGRESS && envelopeText(envelope.GetProgress().GetResult()) == "started"
		})
		cancelProductionInvocation(t, underTest, invocation)
		assertCancelledInvocation(t, underTest, invocation)
	})

	t.Run("fs.grep", func(t *testing.T) {
		const invocation = "capability-cancel-grep"
		// A file large enough that the content scan cannot finish before the CANCEL frame
		// queued right behind the INVOKE is processed.
		large := filepath.Join(root, "large.txt")
		if err := os.WriteFile(large, []byte(strings.Repeat("a", 64<<20)), 0o600); err != nil {
			t.Fatal(err)
		}
		arguments := productionArguments(t, map[string]any{
			"path":    root,
			"pattern": "needle-that-does-not-exist",
			"literal": true,
		})
		invokeProductionCapabilityAsync(t, underTest, invocation, "fs.grep", arguments)
		cancelProductionInvocation(t, underTest, invocation)
		assertCancelledInvocation(t, underTest, invocation)
	})
}

type productionCapabilityFixture struct {
	root         string
	readFile     string
	writeFile    string
	writePreview string
	editFile     string
	editPreview  string
	lspTarget    string
}

func prepareProductionCapabilityFixture(t *testing.T) productionCapabilityFixture {
	t.Helper()
	root := t.TempDir()
	fixture := productionCapabilityFixture{
		root:         root,
		readFile:     filepath.Join(root, "read.txt"),
		writeFile:    filepath.Join(root, "write.txt"),
		writePreview: filepath.Join(root, "write-preview.txt"),
		editFile:     filepath.Join(root, "edit.txt"),
		editPreview:  filepath.Join(root, "edit-preview.txt"),
		lspTarget:    filepath.Join(root, "target.unknown-extension"),
	}
	for path, content := range map[string]string{
		fixture.readFile:                "alpha\nbeta\n",
		fixture.editFile:                "alpha\n",
		fixture.editPreview:             "alpha\n",
		fixture.lspTarget:               "package main\n",
		filepath.Join(root, "grep.txt"): "alpha\n",
	} {
		if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	return fixture
}

func productionCapabilityArguments(t *testing.T, fixture productionCapabilityFixture) map[string]string {
	t.Helper()
	return map[string]string{
		"fs.read":          productionArguments(t, map[string]any{"path": fixture.readFile}),
		"fs.write":         productionArguments(t, map[string]any{"path": fixture.writeFile, "content": "written\n"}),
		"fs.write.preview": productionArguments(t, map[string]any{"path": fixture.writePreview, "content": "preview\n"}),
		"fs.edit":          productionArguments(t, map[string]any{"path": fixture.editFile, "old_string": "alpha", "new_string": "beta"}),
		"fs.edit.preview":  productionArguments(t, map[string]any{"path": fixture.editPreview, "old_string": "alpha", "new_string": "beta"}),
		"fs.find":          productionArguments(t, map[string]any{"path": fixture.root}),
		"fs.grep":          productionArguments(t, map[string]any{"path": fixture.root, "pattern": "alpha", "literal": true}),
		"process.exec":     productionArguments(t, map[string]any{"command": "printf production-exit", "workdir": fixture.root}),
		"lsp.goto-definition": productionArguments(t, map[string]any{
			"path": fixture.lspTarget, "line": 1,
		}),
		"lsp.workspace-symbols": productionArguments(t, map[string]any{
			"path": fixture.lspTarget, "query": "Q",
		}),
		"lsp.java-decompile": productionArguments(t, map[string]any{
			"path": fixture.lspTarget, "target": "jdt://contents/java.base/java/lang/String.class",
		}),
		"skill.install": "{}",
	}
}

func productionArguments(t *testing.T, value map[string]any) string {
	t.Helper()
	encoded, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return string(encoded)
}

type productionRuntimeUnderTest struct {
	connection   *productionCapabilityConnection
	declarations []capability.Declaration
	dataDir      string
	bash         string
	cancel       context.CancelFunc
	exited       chan struct{}
	runErr       error
}

func newProductionRuntimeUnderTest(t *testing.T) *productionRuntimeUnderTest {
	t.Helper()
	dataDir := t.TempDir()
	bash, bashErr := config.ResolveBash(nil)
	if bashErr != nil {
		bash = ""
	}
	supervisor, err := native.NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := supervisor.Close(context.Background()); err != nil {
			t.Errorf("close supervisor: %v", err)
		}
	})
	networks := newNetworkRuntimeStore(networkForTest(t, ""))
	t.Cleanup(func() {
		if err := networks.close(); err != nil {
			t.Errorf("close network runtime: %v", err)
		}
	})
	facade := lsp.NewFacade(supervisor, lsp.PoolOptions{})
	prepared, err := facade.Prepare(nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := prepared.Publish(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := facade.Close(); err != nil {
			t.Errorf("close LSP facade: %v", err)
		}
	})
	applied := config.DaemonConfig{DataDir: dataDir}
	if bash != "" {
		applied.BashExecutable = &bash
	}
	capabilities := productionCapabilities(facade, func() config.DaemonConfig { return applied }, networks, &runtimeResourceControl{})
	declarations, err := capability.Declare(capabilities)
	if err != nil {
		t.Fatal(err)
	}
	connection := newProductionCapabilityConnection()
	underTest := &productionRuntimeUnderTest{
		connection: connection, declarations: declarations, dataDir: dataDir, bash: bash,
		exited: make(chan struct{}),
	}
	runtime, err := daemonruntime.NewRuntime(daemonruntime.RuntimeOptions{
		EnvironmentID: e2eEnvironmentID,
		DaemonVersion: config.DaemonVersion,
		Connections:   productionCapabilityConnectionFactory{connection: connection},
		ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			candidate := applied
			candidate.EnvironmentID = e2eEnvironmentID
			candidate.ConfigVersion = version
			return candidate, nil
		},
		Capabilities: capabilities,
		Processes:    supervisor,
	})
	if err != nil {
		t.Fatal(err)
	}
	runCtx, cancel := context.WithCancel(context.Background())
	underTest.cancel = cancel
	go func() {
		underTest.runErr = runtime.Run(runCtx)
		close(underTest.exited)
	}()
	t.Cleanup(func() {
		cancel()
		select {
		case <-underTest.exited:
		case <-time.After(productionCapabilityTimeout):
			t.Errorf("runtime did not stop")
		}
		if underTest.runErr != nil {
			t.Errorf("runtime stopped with error: %v", underTest.runErr)
		}
	})
	connection.send(&protocol.Envelope{
		MessageType: protocol.MessageType_SETTINGS, EnvironmentId: proto.String(e2eEnvironmentID),
		Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{}}},
	})
	connection.send(&protocol.Envelope{
		MessageType: protocol.MessageType_ENVIRONMENT_CONFIG, EnvironmentId: proto.String(e2eEnvironmentID),
		Payload: &protocol.Envelope_EnvironmentConfig{EnvironmentConfig: &protocol.EnvironmentConfig{
			Version: proto.Int64(1), Config: &protocol.DaemonConfiguration{StudioUrl: "https://studio.example", Lsp: &protocol.LspConfiguration{}},
		}},
	})
	waitProductionFrames(t, underTest, func(frames []*protocol.Envelope) bool {
		for _, frame := range frames {
			if frame.MessageType == protocol.MessageType_READY {
				return true
			}
		}
		return false
	})
	return underTest
}

func requireBash(t *testing.T, bash string) {
	t.Helper()
	if bash == "" {
		t.Skip("the production process.exec capability requires bash, which is unavailable on this platform")
	}
}

func invokeProductionCapability(t *testing.T, underTest *productionRuntimeUnderTest, invocation, capabilityID, arguments string) []*protocol.Envelope {
	t.Helper()
	invokeProductionCapabilityAsync(t, underTest, invocation, capabilityID, arguments)
	waitInvocationTerminal(t, underTest, invocation)
	waitInvocationFrame(t, underTest, invocation, func(envelope *protocol.Envelope) bool {
		return envelope.MessageType == protocol.MessageType_EXECUTION_EXITED
	})
	return invocationFrames(underTest.connection.frames(), invocation)
}

func invokeProductionCapabilityAsync(t *testing.T, underTest *productionRuntimeUnderTest, invocation, capabilityID, arguments string) {
	t.Helper()
	underTest.connection.send(&protocol.Envelope{
		MessageType: protocol.MessageType_INVOKE, EnvironmentId: proto.String(e2eEnvironmentID), InvocationId: proto.String(invocation),
		Payload: &protocol.Envelope_Invoke{Invoke: &protocol.Invoke{
			CapabilityId: proto.String(capabilityID), ArgumentsJson: proto.String(arguments),
			TimeoutMillis: proto.Int64(0), MaxOutputBytes: proto.Int64(10 << 20),
			ImageInputTier: imageTier(protocol.ImageInputTier_P720),
		}},
	})
}

func cancelProductionInvocation(t *testing.T, underTest *productionRuntimeUnderTest, invocation string) {
	t.Helper()
	underTest.connection.send(&protocol.Envelope{
		MessageType: protocol.MessageType_CANCEL, EnvironmentId: proto.String(e2eEnvironmentID), InvocationId: proto.String(invocation),
		Payload: &protocol.Envelope_Cancel{Cancel: &protocol.Cancel{}},
	})
}

func assertCancelledInvocation(t *testing.T, underTest *productionRuntimeUnderTest, invocation string) {
	t.Helper()
	waitInvocationTerminal(t, underTest, invocation)
	waitInvocationFrame(t, underTest, invocation, func(envelope *protocol.Envelope) bool {
		return envelope.MessageType == protocol.MessageType_EXECUTION_EXITED
	})
	frames := invocationFrames(underTest.connection.frames(), invocation)
	if got := countInvocationFrames(frames, invocation, protocol.MessageType_CANCELLED); got != 1 {
		t.Fatalf("CANCELLED terminals = %d, want exactly one: %v", got, frames)
	}
	if got := countInvocationFrames(frames, invocation, protocol.MessageType_COMPLETED); got != 0 {
		t.Fatalf("COMPLETED terminals = %d, want none: %v", got, frames)
	}
	if got := countInvocationFrames(frames, invocation, protocol.MessageType_EXECUTION_EXITED); got != 1 {
		t.Fatalf("EXECUTION_EXITED frames = %d, want exactly one: %v", got, frames)
	}
}

func waitInvocationTerminal(t *testing.T, underTest *productionRuntimeUnderTest, invocation string) []*protocol.Envelope {
	t.Helper()
	return waitProductionFrames(t, underTest, func(frames []*protocol.Envelope) bool {
		return countInvocationFrames(frames, invocation, protocol.MessageType_COMPLETED, protocol.MessageType_FAILED, protocol.MessageType_CANCELLED) > 0
	})
}

func waitInvocationFrame(t *testing.T, underTest *productionRuntimeUnderTest, invocation string, match func(*protocol.Envelope) bool) []*protocol.Envelope {
	t.Helper()
	return waitProductionFrames(t, underTest, func(frames []*protocol.Envelope) bool {
		for _, frame := range invocationFrames(frames, invocation) {
			if match(frame) {
				return true
			}
		}
		return false
	})
}

func waitProductionFrames(t *testing.T, underTest *productionRuntimeUnderTest, predicate func([]*protocol.Envelope) bool) []*protocol.Envelope {
	t.Helper()
	deadline := time.NewTimer(productionCapabilityTimeout)
	defer deadline.Stop()
	ticker := time.NewTicker(time.Millisecond)
	defer ticker.Stop()
	for {
		frames := underTest.connection.frames()
		if predicate(frames) {
			return frames
		}
		select {
		case <-underTest.exited:
			t.Fatalf("runtime stopped before the expected frame: %v", underTest.runErr)
		case <-deadline.C:
			t.Fatalf("timed out waiting for the expected frame; frames: %v", frames)
		case <-ticker.C:
		}
	}
}

func invocationFrames(frames []*protocol.Envelope, invocation string) []*protocol.Envelope {
	var matched []*protocol.Envelope
	for _, frame := range frames {
		if frame.GetInvocationId() == invocation {
			matched = append(matched, frame)
		}
	}
	return matched
}

func countInvocationFrames(frames []*protocol.Envelope, invocation string, types ...protocol.MessageType) int {
	count := 0
	for _, frame := range invocationFrames(frames, invocation) {
		for _, messageType := range types {
			if frame.MessageType == messageType {
				count++
				break
			}
		}
	}
	return count
}

func envelopeText(result *protocol.CapabilityResult) string {
	if result == nil {
		return ""
	}
	var text strings.Builder
	for _, content := range result.GetContents() {
		if value := content.GetText(); value != nil {
			text.WriteString(value.GetText())
		}
	}
	return text.String()
}

type productionCapabilityConnectionFactory struct {
	connection *productionCapabilityConnection
}

func (f productionCapabilityConnectionFactory) Connect(context.Context) (daemonruntime.Connection, error) {
	return f.connection, nil
}

type productionCapabilityConnection struct {
	in     chan *protocol.Envelope
	mu     sync.Mutex
	out    []*protocol.Envelope
	closed chan struct{}
	once   sync.Once
}

func newProductionCapabilityConnection() *productionCapabilityConnection {
	return &productionCapabilityConnection{in: make(chan *protocol.Envelope, 16), closed: make(chan struct{})}
}

func (c *productionCapabilityConnection) send(envelope *protocol.Envelope) {
	c.in <- envelope
}

func (c *productionCapabilityConnection) Receive(ctx context.Context) (*protocol.Envelope, error) {
	select {
	case envelope := <-c.in:
		return envelope, nil
	case <-c.closed:
		return nil, os.ErrClosed
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func (c *productionCapabilityConnection) Send(_ context.Context, envelope *protocol.Envelope) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.out = append(c.out, envelope)
	return nil
}

func (c *productionCapabilityConnection) Close() error {
	c.once.Do(func() { close(c.closed) })
	return nil
}

func (c *productionCapabilityConnection) frames() []*protocol.Envelope {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]*protocol.Envelope(nil), c.out...)
}
