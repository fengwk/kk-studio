package runtime

import (
	"context"
	"errors"
	"math"
	"strings"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"github.com/fengwk/kk-studio/daemon/internal/update"
)

func runtimeOptionsForTest() RuntimeOptions {
	return RuntimeOptions{
		EnvironmentID: "env",
		Connections:   fixedConnectionFactory{connection: newTestConnection()},
		ApplyConfig: func(_ context.Context, version int64, _ *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{EnvironmentID: "env", ConfigVersion: version}, nil
		},
	}
}

// RUN-T-081 validates constructor constraints before a runtime can enter its run loop.
func TestNewRuntimeRejectsInvalidOptions(t *testing.T) {
	valid := runtimeOptionsForTest()
	cases := []struct {
		name string
		edit func(*RuntimeOptions)
		want string
	}{
		{"missing environment", func(o *RuntimeOptions) { o.EnvironmentID = "" }, "runtime requires environmentId"},
		{"missing connections", func(o *RuntimeOptions) { o.Connections = nil }, "runtime requires environmentId"},
		{"missing config applier", func(o *RuntimeOptions) { o.ApplyConfig = nil }, "runtime requires environmentId"},
		{"negative reconnect delay", func(o *RuntimeOptions) { o.InitialReconnectDelay = -1 }, "invalid reconnect delay bounds"},
		{"max reconnect below initial", func(o *RuntimeOptions) { o.InitialReconnectDelay = time.Second; o.MaxReconnectDelay = time.Millisecond }, "invalid reconnect delay bounds"},
		{"zero invocation limit", func(o *RuntimeOptions) { o.MaxConcurrentInvocations = -1 }, "maxConcurrentInvocations must be between 1 and 64"},
		{"oversized invocation limit", func(o *RuntimeOptions) { o.MaxConcurrentInvocations = 65 }, "maxConcurrentInvocations must be between 1 and 64"},
		{"negative heartbeat", func(o *RuntimeOptions) { o.HeartbeatInterval = -1 }, "heartbeat interval must not be negative"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			o := valid
			tc.edit(&o)
			if _, err := NewRuntime(o); err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("NewRuntime error = %v, want containing %q", err, tc.want)
			}
		})
	}

	manager := newTestEnvironmentConfigRuntime(t, initialRuntimeConfig(), (&testConfigurationPreparer{}).prepare, &testCacheWriter{})
	bad := valid
	bad.EnvironmentConfig = manager
	bad.Status = NewStatusTracker()
	if _, err := NewRuntime(bad); err == nil || err.Error() != "runtime and environment config runtime must share a status tracker" {
		t.Fatalf("tracker mismatch error = %v", err)
	}
	otherEnvironment := initialRuntimeConfig()
	otherEnvironment.EnvironmentID = "other"
	bad.EnvironmentConfig = newTestEnvironmentConfigRuntime(t, otherEnvironment, (&testConfigurationPreparer{}).prepare, &testCacheWriter{})
	bad.Status = bad.EnvironmentConfig.StatusTracker()
	if _, err := NewRuntime(bad); err == nil || err.Error() != "runtime and environment config runtime must share an environmentId" {
		t.Fatalf("environment mismatch error = %v", err)
	}
	injectedHost := runtimeOptionsForTest()
	injectedHost.HostInfo = &protocol.HostInfo{UserName: stringPtr("test-user")}
	if _, err := NewRuntime(injectedHost); err != nil {
		t.Fatalf("injected host facts were rejected: %v", err)
	}
}

func newReadyRuntimeForTest(t *testing.T, options RuntimeOptions) (*Runtime, *testConnection) {
	t.Helper()
	connection := newTestConnection()
	options.Connections = fixedConnectionFactory{connection: connection}
	r, err := NewRuntime(options)
	if err != nil {
		t.Fatal(err)
	}
	active := r.attachConnection(connection)
	r.mu.Lock()
	r.bound = true
	r.ready = true
	r.connection = active
	r.mu.Unlock()
	if err := r.gate.Bind(options.EnvironmentID); err != nil {
		t.Fatal(err)
	}
	r.gate.MarkReady()
	return r, connection
}

// RUN-T-059 verifies scope, server rejection, unsupported frames, and pre-READY control behavior.
func TestRuntimeHandleControlFrameEdges(t *testing.T) {
	r, _ := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	r.mu.Lock()
	r.bound = false
	r.mu.Unlock()
	r.gate.Clear()
	if err := r.handle(context.Background(), &protocol.Envelope{MessageType: protocol.MessageType_HEARTBEAT}); err == nil || err.Error() != "environment scope is not bound" {
		t.Fatalf("unbound frame error = %v", err)
	}
	active := r.attachConnection(newTestConnection())
	_ = active
	r.mu.Lock()
	r.bound = true
	r.ready = true
	r.mu.Unlock()
	if err := r.gate.Bind("env"); err != nil {
		t.Fatal(err)
	}
	r.gate.MarkReady()
	wrong := &protocol.Envelope{MessageType: protocol.MessageType_HEARTBEAT, EnvironmentId: stringPtr("other")}
	if err := r.handle(context.Background(), wrong); err == nil || err.Error() != "envelope is bound to a different environment" {
		t.Fatalf("wrong scope error = %v", err)
	}
	if err := r.handle(context.Background(), &protocol.Envelope{MessageType: protocol.MessageType_ERROR}); err == nil || err.Error() != "control server rejected the connection" {
		t.Fatalf("server error frame = %v", err)
	}
	if err := r.handle(context.Background(), &protocol.Envelope{MessageType: protocol.MessageType(999)}); err != nil {
		t.Fatalf("unsupported frame send error = %v", err)
	}
	if err := r.handleSettings(&protocol.Envelope{EnvironmentId: stringPtr("env"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{TemporaryResourceTtlSeconds: int64Ptr(-1)}}}}); err == nil || err.Error() != "settings.resourceBudgets is invalid" {
		t.Fatalf("invalid resource budget error = %v", err)
	}
	if err := r.handleSettings(&protocol.Envelope{EnvironmentId: stringPtr("other"), Payload: &protocol.Envelope_Settings{Settings: &protocol.Settings{ResourceBudgets: &protocol.ResourceBudgets{}}}}); err == nil {
		t.Fatal("expected mismatched SETTINGS environment rejection")
	}

	r.mu.Lock()
	r.ready = false
	r.mu.Unlock()
	if err := r.handle(context.Background(), &protocol.Envelope{MessageType: protocol.MessageType_CANCEL, EnvironmentId: stringPtr("env")}); err != nil {
		t.Fatalf("pre-READY CANCEL = %v", err)
	}
	if err := r.handle(context.Background(), &protocol.Envelope{MessageType: protocol.MessageType_RECOVER, EnvironmentId: stringPtr("env")}); err != nil {
		t.Fatalf("pre-READY RECOVER = %v", err)
	}
}

// RUN-T-021 exercises result encoding validation before any resource can be uploaded.
func TestRuntimeEncodeResultRejectsInvalidPayloads(t *testing.T) {
	r := newEncodingRuntime(t, nil)
	cases := []struct {
		name   string
		result CapabilityResult
		budget int64
		want   string
	}{
		{"call id", CapabilityResult{CallID: "other"}, 1, "callId does not match"},
		{"details object", CapabilityResult{CallID: testInvocationID, Details: []byte(`[]`)}, 1, "details is not a JSON object"},
		{"too many contents", CapabilityResult{CallID: testInvocationID, Contents: make([]ResultContent, maxResultContents+1)}, 1, "too many content items"},
		{"budget must be positive", CapabilityResult{CallID: testInvocationID}, 0, "maximumResourceBytes must be positive"},
		{"negative resource size", CapabilityResult{CallID: testInvocationID, Contents: []ResultContent{{Type: "resource", Size: -1}}}, 1, "resource size must not be negative"},
		{"unknown content", CapabilityResult{CallID: testInvocationID, Contents: []ResultContent{{Type: "other"}}}, 1, "content type is invalid"},
		{"missing uploader", CapabilityResult{CallID: testInvocationID, Contents: []ResultContent{{Type: "binary", Bytes: []byte("x")}}}, 1, "resource upload is not configured"},
		{"invalid json content", CapabilityResult{CallID: testInvocationID, Contents: []ResultContent{{Type: "json", JSON: []byte("{")}}}, 1, "JSON content is invalid"},
		{"invalid progress content", CapabilityResult{CallID: testInvocationID, Contents: []ResultContent{{Type: "binary", Bytes: []byte("x")}}}, 1, "progress does not allow binary content"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			progress := tc.name == "invalid progress content"
			if _, err := r.encodeResult(context.Background(), testInvocationID, tc.result, tc.budget, progress); err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("encodeResult error = %v, want containing %q", err, tc.want)
			}
		})
	}
	largeDetails := CapabilityResult{CallID: testInvocationID, Details: []byte(`{"x":"` + strings.Repeat("x", maxDetailsBytes) + `"}`)}
	if _, err := r.encodeResult(context.Background(), testInvocationID, largeDetails, math.MaxInt64, false); err == nil || !strings.Contains(err.Error(), "details JSON exceeds") {
		t.Fatalf("oversized details error = %v", err)
	}
	largeJSON := CapabilityResult{CallID: testInvocationID, Contents: []ResultContent{{Type: "json", JSON: []byte(`"` + strings.Repeat("x", maxContentJSONBytes) + `"`)}}}
	if _, err := r.encodeResult(context.Background(), testInvocationID, largeJSON, math.MaxInt64, false); err == nil || !strings.Contains(err.Error(), "JSON content exceeds") {
		t.Fatalf("oversized JSON content error = %v", err)
	}
}

type failingSendConnection struct {
	*testConnection
	err error
}

func (c *failingSendConnection) Send(context.Context, *protocol.Envelope) error { return c.err }

// RUN-T-020 rejects malformed invocation fields and preserves one-execution admission limits.
func TestRuntimeHandleInvokeRejectsMalformedCalls(t *testing.T) {
	options := runtimeOptionsForTest()
	options.MaxConcurrentInvocations = 1
	options.Capabilities = map[string]CapabilityExecutor{"echo": func(context.Context, CapabilityCall, CapabilityContext) (CapabilityResult, error) {
		return CapabilityResult{}, nil
	}}
	r, _ := newReadyRuntimeForTest(t, options)
	cases := []struct {
		name string
		e    *protocol.Envelope
		want string
	}{
		{"missing payload", &protocol.Envelope{InvocationId: stringPtr("bad-payload")}, "invoke payload is required"},
		{"missing tier", invokeWithID("bad-tier", nil, 1, `{}`), "imageInputTier is required"},
		{"invalid tier", invokeWithID("bad-tier-value", imageTierPtr(protocol.ImageInputTier(99)), 1, `{}`), "imageInputTier is invalid"},
		{"missing budget", invokeWithID("bad-budget", imageTierPtr(protocol.ImageInputTier_P720), 0, `{}`), "maxOutputBytes must be a positive integer"},
		{"missing capability", invokeWithEmptyCapability("empty-capability"), "capabilityId is required"},
		{"unknown capability", invokeWithCapability("unknown", imageTierPtr(protocol.ImageInputTier_P720), 1, `{}`), "unknown Environment capability: unknown"},
		{"expected file on other capability", invokeWithExpectedFile("expected-file"), "expectedFile is only valid for fs.edit"},
		{"non-object arguments", invokeWithArgs("non-object", "[]"), "arguments must be a JSON object"},
		{"invalid arguments", invokeWithArgs("invalid-json", "{"), "arguments must be a JSON object"},
		{"negative timeout", invokeWithTimeout("negative-timeout", -1), "timeoutMillis must be a non-negative duration"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if err := r.handleInvoke(context.Background(), tc.e); err != nil {
				t.Fatal(err)
			}
			frames := r.connection.connection.(*testConnection).frames()
			found := false
			for _, frame := range frames {
				if frame.MessageType == protocol.MessageType_FAILED && frame.GetFailed().GetMessage() == tc.want {
					found = true
				}
			}
			if !found {
				t.Fatalf("failed frame %q not found in %+v", tc.want, frames)
			}
		})
	}
	if err := r.handleInvoke(context.Background(), &protocol.Envelope{}); err != nil {
		t.Fatal(err)
	}
	r.admissionMu.Lock()
	r.admissionPaused = true
	r.admissionMu.Unlock()
	if err := r.handleInvoke(context.Background(), invokeWithArgs("paused", `{}`)); err != nil {
		t.Fatal(err)
	}
	r.admissionMu.Lock()
	r.admissionPaused = false
	r.admissionMu.Unlock()
	if decision, err := r.updateOps.Decide(updateCommandForTest(), "1", true, ""); err != nil || decision.Action != "admit" {
		t.Fatalf("update admission = %+v, %v", decision, err)
	}
	if err := r.handleInvoke(context.Background(), invokeWithArgs("update-held", `{}`)); err != nil {
		t.Fatal(err)
	}
}

func invokeWithID(id string, tier *protocol.ImageInputTier, budget int64, args string) *protocol.Envelope {
	return &protocol.Envelope{InvocationId: stringPtr(id), Payload: &protocol.Envelope_Invoke{Invoke: &protocol.Invoke{CapabilityId: stringPtr("echo"), ImageInputTier: tier, MaxOutputBytes: int64Ptr(budget), ArgumentsJson: stringPtr(args)}}}
}

func invokeWithArgs(id, args string) *protocol.Envelope {
	return invokeWithID(id, imageTierPtr(protocol.ImageInputTier_P720), 1, args)
}

func invokeWithEmptyCapability(id string) *protocol.Envelope {
	e := invokeWithArgs(id, `{}`)
	e.GetInvoke().CapabilityId = stringPtr("")
	return e
}

func invokeWithCapability(id string, tier *protocol.ImageInputTier, budget int64, args string) *protocol.Envelope {
	e := invokeWithID(id, tier, budget, args)
	e.GetInvoke().CapabilityId = stringPtr("unknown")
	return e
}

func invokeWithExpectedFile(id string) *protocol.Envelope {
	e := invokeWithArgs(id, `{}`)
	e.GetInvoke().ExpectedFile = &protocol.ExpectedFile{Path: stringPtr("/tmp/file"), Sha256: stringPtr("abc")}
	return e
}

func invokeWithTimeout(id string, timeout int64) *protocol.Envelope {
	e := invokeWithArgs(id, `{}`)
	e.GetInvoke().TimeoutMillis = int64Ptr(timeout)
	return e
}

// RUN-T-009 returns RECOVER_NOT_FOUND for a valid unknown recovery request and drops failed send connections.
func TestRuntimeRecoveryAndSendFailureEdges(t *testing.T) {
	r, c := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	if err := r.handleRecover(&protocol.Envelope{InvocationId: stringPtr("missing")}); err != nil {
		t.Fatal(err)
	}
	if !c.sent(protocol.MessageType_RECOVER_NOT_FOUND) {
		t.Fatal("missing recovery did not send RECOVER_NOT_FOUND")
	}
	if err := r.sendProgress("missing", CapabilityResult{CallID: "missing"}); err != nil {
		t.Fatalf("progress for an unknown call = %v", err)
	}
	failed := &failingSendConnection{testConnection: newTestConnection(), err: errors.New("disconnected")}
	r.dropActiveConnection()
	active := r.attachConnection(failed)
	if err := r.sendFrame(&protocol.Envelope{MessageType: protocol.MessageType_HEARTBEAT}); err == nil {
		t.Fatal("expected send failure")
	}
	if r.isActiveConnection(active) {
		t.Fatal("send failure retained the broken connection")
	}
}

// RUN-T-068/073 verifies confirmed execution exits are published once after terminal completion.
func TestRuntimeLifecycleHelperBranches(t *testing.T) {
	if waitReconnect(context.Background(), time.Millisecond) != true || waitReconnectAlreadyCancelled() {
		t.Fatal("reconnect wait did not distinguish timeout from cancellation")
	}
	if doubledDelay(time.Second, 10*time.Second) != 2*time.Second || doubledDelay(6*time.Second, 10*time.Second) != 10*time.Second || doubledDelay(10*time.Second, 10*time.Second) != 10*time.Second {
		t.Fatal("reconnect backoff did not double and cap")
	}
	if _, err := NewInvocationJournal(0); err == nil {
		t.Fatal("journal accepted a non-positive capacity")
	}
	r, connection := newReadyRuntimeForTest(t, runtimeOptionsForTest())
	if _, ok := r.gate.BoundEnvironment(); !ok {
		t.Fatal("bound environment was not available")
	}
	if err := r.sendProgress("unknown", CapabilityResult{CallID: "unknown"}); err != nil {
		t.Fatal(err)
	}
	r.journal.Start("running")
	if err := r.sendProgress("running", CapabilityResult{CallID: "running"}); err != nil {
		t.Fatalf("progress without registered executor call = %v", err)
	}
	r.callsMu.Lock()
	r.calls["running"] = &runningCall{maxOutputBytes: 8, cancel: func() {}}
	r.callsMu.Unlock()
	if err := r.sendProgress("running", CapabilityResult{CallID: "running", Contents: []ResultContent{{Type: "text", Text: "progress"}}}); err != nil {
		t.Fatal(err)
	}
	if !connection.sent(protocol.MessageType_PROGRESS) {
		t.Fatal("valid progress was not sent")
	}
	if err := r.sendProgress("running", CapabilityResult{CallID: "running", Contents: []ResultContent{{Type: "binary", Bytes: []byte("x")}}}); err == nil {
		t.Fatal("invalid progress content was accepted")
	}

	r.journal.Start("exit")
	r.noteExecutionExit("exit")
	r.publishRequestedExit("exit")
	r.publishRequestedExit("exit")
	if got := countFrames(connection.frames(), protocol.MessageType_EXECUTION_EXITED); got != 1 {
		t.Fatalf("execution exit frames=%d, want 1", got)
	}
	if _, err := invokeCapability(context.Background(), CapabilityCall{}, CapabilityContext{}, func(context.Context, CapabilityCall, CapabilityContext) (CapabilityResult, error) {
		panic("secret panic")
	}); err == nil || err.Error() != "capability execution failed" {
		t.Fatalf("panic was not contained: %v", err)
	}
	if _, err := invokeCapability(context.Background(), CapabilityCall{}, CapabilityContext{}, func(context.Context, CapabilityCall, CapabilityContext) (CapabilityResult, error) {
		return CapabilityResult{}, nil
	}); err != nil {
		t.Fatalf("ordinary executor error = %v", err)
	}
	if sameOptionalString(nil, stringPtr("x")) || !sameOptionalString(nil, nil) || !sameOptionalString(stringPtr("x"), stringPtr("x")) || sameOptionalString(stringPtr("x"), stringPtr("y")) {
		t.Fatal("optional string comparison returned an invalid result")
	}
}

func waitReconnectAlreadyCancelled() bool {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	return waitReconnect(ctx, time.Hour)
}

func countFrames(frames []*protocol.Envelope, kind protocol.MessageType) int {
	count := 0
	for _, frame := range frames {
		if frame.MessageType == kind {
			count++
		}
	}
	return count
}

func updateCommandForTest() update.Command {
	return update.Command{OperationID: "123e4567-e89b-42d3-a456-426614174000", TargetVersion: "2.0.0"}
}
