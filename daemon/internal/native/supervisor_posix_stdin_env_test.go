//go:build linux || darwin

package native

import (
	"context"
	"io"
	"testing"
	"time"
)

// Verifies Env replaces the daemon environment only when replacement is requested.
func TestPOSIXSupervisor_EnvironmentReplacement(t *testing.T) {
	t.Setenv("KK_STUDIO_NATIVE_DAEMON_ENV", "daemon")
	for _, testCase := range []struct {
		name       string
		replaceEnv bool
		want       string
	}{
		{name: "overlay", want: "daemon:spec"},
		{name: "replace", replaceEnv: true, want: "absent:spec"},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			supervisor, err := NewSupervisor()
			if err != nil {
				t.Fatal(err)
			}
			defer supervisor.Close(context.Background())
			process, err := supervisor.Start(context.Background(), ProcessSpec{
				Executable: "/bin/sh",
				Args:       []string{"-c", `printf '%s' "${KK_STUDIO_NATIVE_DAEMON_ENV-absent}:${KK_STUDIO_NATIVE_SPEC_ENV}"`},
				Env:        []string{"KK_STUDIO_NATIVE_SPEC_ENV=spec"},
				ReplaceEnv: testCase.replaceEnv,
			})
			if err != nil {
				t.Fatal(err)
			}
			output, err := io.ReadAll(process.Stdout())
			if err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			if _, err := process.Wait(ctx); err != nil {
				t.Fatal(err)
			}
			if string(output) != testCase.want {
				t.Fatalf("command environment = %q, want %q", output, testCase.want)
			}
		})
	}
}

// Verifies a null stdin reaches end-of-file without the caller closing a pipe.
func TestPOSIXSupervisor_NullStdinReachesEndOfFile(t *testing.T) {
	supervisor, err := NewSupervisor()
	if err != nil {
		t.Fatal(err)
	}
	defer supervisor.Close(context.Background())
	process, err := supervisor.Start(context.Background(), ProcessSpec{
		Executable: "/bin/sh",
		Args:       []string{"-c", "cat >/dev/null; exit 23"},
		NullStdin:  true,
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := process.Stdin().Write([]byte("discarded")); err != nil {
		t.Fatalf("null stdin rejected a write: %v", err)
	}
	if err := process.Stdin().Close(); err != nil {
		t.Fatalf("null stdin close = %v", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	exit, err := process.Wait(ctx)
	if err != nil {
		t.Fatalf("null stdin did not reach end-of-file: %v", err)
	}
	if exit.Code == nil || *exit.Code != 23 {
		t.Fatalf("exit after reading null stdin = %+v", exit)
	}
}

// Verifies the null stdin is a real device file that reads end-of-file and accepts writes.
func TestPOSIXNullStdinIsDevice(t *testing.T) {
	file, err := openNullStdin()
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	if read, err := file.Read(make([]byte, 1)); read != 0 || err != io.EOF {
		t.Fatalf("null stdin read = %d, %v; want immediate end-of-file", read, err)
	}
	if _, err := file.Write([]byte("discarded")); err != nil {
		t.Fatalf("null stdin write = %v", err)
	}
	info, err := file.Stat()
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().IsRegular() {
		t.Fatalf("null stdin is a regular file: %v", info.Mode())
	}
}
