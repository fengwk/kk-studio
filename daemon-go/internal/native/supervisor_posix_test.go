//go:build linux || darwin

package native

import (
	"context"
	"io"
	"os"
	"testing"
	"time"

	"go.uber.org/goleak"
)

func TestMain(m *testing.M) {
	if len(os.Args) > 1 && os.Args[1] == "__native-helper" {
		os.Exit(HelperMain(os.Args[2:]))
	}
	goleak.VerifyTestMain(m)
}

// NAT-N-001, NAT-T-115, NAT-T-118
func TestPOSIXSupervisor_NaturalExitDrains(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", "printf native-ok; exit 21"}})
	if err != nil {
		t.Fatal(err)
	}
	readDone := make(chan struct{})
	var stdout []byte
	go func() { stdout, _ = io.ReadAll(process.Stdout()); close(readDone) }()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	select {
	case <-readDone:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	exit, err := process.Wait(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if string(stdout) != "native-ok" {
		t.Fatalf("stdout = %q", stdout)
	}
	if exit.Code == nil || *exit.Code != 21 {
		t.Fatalf("exit = %+v", exit)
	}
}

// NAT-T-119
func TestPOSIXSupervisor_DuplexStreams(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{
		Executable: "/bin/sh",
		Args:       []string{"-c", "IFS= read -r value; printf 'out:%s' \"$value\"; printf 'native-error' >&2; exit 7"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := io.WriteString(process.Stdin(), "native-input\n"); err != nil {
		t.Fatal(err)
	}
	if err := process.Stdin().Close(); err != nil {
		t.Fatal(err)
	}
	stdout, err := io.ReadAll(process.Stdout())
	if err != nil {
		t.Fatal(err)
	}
	stderr, err := io.ReadAll(process.Stderr())
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	exit, err := process.Wait(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if string(stdout) != "out:native-input" || string(stderr) != "native-error" {
		t.Fatalf("stdout/stderr = %q/%q", stdout, stderr)
	}
	if exit.Code == nil || *exit.Code != 7 {
		t.Fatalf("exit = %+v", exit)
	}
}

// NAT-T-133
func TestPOSIXSupervisor_StartAfterClose(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	if err := supervisor.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	if _, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/true"}); err == nil {
		t.Fatal("start after close succeeded")
	}
}

// NAT-T-132
func TestPOSIXSupervisor_ExecFailureHasUnknownExit(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/no/such/native-command"})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	exit, err := process.Wait(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if exit.Code != nil || exit.Signal != nil {
		t.Fatalf("failed exec reported an exit status: %+v", exit)
	}
}

// NAT-T-116
func TestPOSIXSupervisor_Terminate(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{Executable: "/bin/sh", Args: []string{"-c", "sleep 30"}})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := process.Terminate(ctx, 10*time.Millisecond); err != nil {
		t.Fatal(err)
	}
}

// NAT-T-120
func TestPOSIXSupervisor_PreCancelled(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := supervisor.Start(ctx, ProcessSpec{Executable: "/bin/true"}); err == nil {
		t.Fatal("cancelled start succeeded")
	}
}
