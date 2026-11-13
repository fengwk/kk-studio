package runtime

import (
	"bytes"
	"context"
	"errors"
	"net"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

type failingShutdownWriter struct {
	calls int
	err   error
}

func (w *failingShutdownWriter) Write([]byte) (int, error) {
	w.calls++
	return 0, w.err
}

type closeErrorConnection struct {
	Connection
	err    error
	closed bool
}

func (c *closeErrorConnection) Close() error {
	c.closed = true
	_ = c.Connection.Close()
	return c.err
}

func TestShutdownFailureTrackerContinuesAndRedactsDiagnostics(t *testing.T) {
	var output bytes.Buffer
	tracker := newShutdownFailureTracker(&output)
	first := errors.New("private path /home/user/id_rsa")
	second := errors.New("credential=secret")
	secondCalled := false

	tracker.release("connection close", func() error { return first })
	tracker.release("resource close", func() error {
		secondCalled = true
		return second
	})
	tracker.release("unknown phase", func() error { panic("untrusted panic value") })

	failures := tracker.snapshot()
	if !secondCalled || len(failures) != 3 || !errors.Is(failures[0], first) || !errors.Is(failures[1], second) {
		t.Fatalf("shutdown failures = %v; second release called=%v", failures, secondCalled)
	}
	want := "daemon connection close failed: Error\n" +
		"daemon resource close failed: Error\n" +
		"daemon unknown phase failed: UnknownError\n"
	if output.String() != want {
		t.Fatalf("diagnostics = %q; want %q", output.String(), want)
	}
	if strings.Contains(output.String(), "private path") || strings.Contains(output.String(), "id_rsa") || strings.Contains(output.String(), "credential") || strings.Contains(output.String(), "untrusted") {
		t.Fatalf("diagnostic leaked failure details: %q", output.String())
	}
	if failures[2].Error() != "shutdown action failed with a non-error value" {
		t.Fatalf("non-error panic was not safely represented: %q", failures[2].Error())
	}
}

func TestShutdownDiagnosticFailureIsAggregatedWithoutRetry(t *testing.T) {
	writeErr := errors.New("diagnostic output failed")
	writer := &failingShutdownWriter{err: writeErr}
	tracker := newShutdownFailureTracker(writer)
	original := errors.New("private failure")

	tracker.record("connection close", original)
	tracker.collect("temporary resource sweep", errors.New("sweep failure"))

	failures := tracker.snapshot()
	if len(failures) != 3 || !errors.Is(failures[0], original) || !errors.Is(failures[1], writeErr) || !errors.Is(failures[2], writeErr) {
		t.Fatalf("shutdown failures = %v", failures)
	}
	if writer.calls != 2 {
		t.Fatalf("diagnostic writes = %d, want one attempt for each failure", writer.calls)
	}
}

func TestRuntimeShutdownTreatsAlreadyClosedConnectionAsSuccess(t *testing.T) {
	base := newTestConnection()
	connection := &closeErrorConnection{Connection: base, err: net.ErrClosed}
	r, err := NewRuntime(RuntimeOptions{
		EnvironmentID: "env",
		Connections:   fixedConnectionFactory{connection: connection},
		ApplyConfig: func(context.Context, int64, *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	var output bytes.Buffer
	r.shutdown.writer = &output

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := r.Run(ctx); err != nil {
		t.Fatalf("already closed connection became shutdown failure: %v", err)
	}
	if !connection.closed || len(r.ShutdownFailures()) != 0 || output.Len() != 0 {
		t.Fatalf("closed=%v, shutdown failures=%v, diagnostics=%q", connection.closed, r.ShutdownFailures(), output.String())
	}
}

func TestRuntimePublishesConnectionCloseFailureAfterShutdown(t *testing.T) {
	base := newTestConnection()
	closeErr := errors.New("private transport detail")
	connection := &closeErrorConnection{Connection: base, err: closeErr}
	r, err := NewRuntime(RuntimeOptions{
		EnvironmentID: "env",
		Connections:   fixedConnectionFactory{connection: connection},
		ApplyConfig: func(context.Context, int64, *protocol.DaemonConfiguration) (config.DaemonConfig, error) {
			return config.DaemonConfig{}, nil
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	var output bytes.Buffer
	r.shutdown.writer = &output

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	runErr := r.Run(ctx)
	if !errors.Is(runErr, closeErr) || runErr.Error() != "daemon could not release every resource" {
		t.Fatalf("runtime shutdown error = %v, want safe aggregate containing connection close failure", runErr)
	}
	failures := r.ShutdownFailures()
	if !connection.closed || len(failures) != 1 || !errors.Is(failures[0], closeErr) {
		t.Fatalf("closed=%v, shutdown failures=%v", connection.closed, failures)
	}
	if got, want := output.String(), "daemon connection close failed: Error\n"; got != want {
		t.Fatalf("diagnostics = %q, want %q", got, want)
	}
	if strings.Contains(output.String(), "private transport detail") {
		t.Fatalf("shutdown diagnostic leaked error details: %q", output.String())
	}
}
