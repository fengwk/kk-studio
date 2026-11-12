//go:build linux || darwin

package native

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestPOSIXAwaitScopeConfirmsProcessGroupLeader(t *testing.T) {
	for _, testCase := range []struct {
		name    string
		pgid    int
		pgidErr error
		wantErr string
	}{
		{name: "confirmed", pgid: 42},
		{name: "different process group", pgid: 43, wantErr: "not the process group leader"},
		{name: "query failure", pgidErr: errors.New("injected getpgid failure"), wantErr: "injected getpgid failure"},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			dir := stateTestDir(t)
			if err := publishText(dir, scopeFile, "42"); err != nil {
				t.Fatal(err)
			}
			p := &posixProcess{
				pid:      42,
				sid:      42,
				stateDir: dir,
				done:     make(chan struct{}),
				processGroupID: func(pid int) (int, error) {
					if pid != 42 {
						t.Fatalf("getpgid PID = %d, want 42", pid)
					}
					return testCase.pgid, testCase.pgidErr
				},
			}
			err := p.awaitScopeWithMembers(context.Background(), func(int) ([]sessionMember, error) {
				return []sessionMember{{PID: 42, Session: 42, Identity: "helper-identity"}}, nil
			})
			if testCase.wantErr == "" {
				if err != nil {
					t.Fatalf("awaitScope error = %v, want success", err)
				}
			} else if err == nil || !strings.Contains(err.Error(), testCase.wantErr) {
				t.Fatalf("awaitScope error = %v, want substring %q", err, testCase.wantErr)
			}
		})
	}
}

func TestLinuxSubreaper_PrctlFailure(t *testing.T) {
	wantErr := errors.New("injected PR_SET_CHILD_SUBREAPER failure")
	called := false
	supervisor, err := newPOSIXSupervisor("linux", func() error {
		called = true
		return wantErr
	})
	if !called {
		t.Fatal("subreaper setup was not called")
	}
	if supervisor != nil || !errors.Is(err, wantErr) {
		t.Fatalf("supervisor, error = %v, %v; want nil supervisor and wrapped setup error", supervisor, err)
	}
}

func TestLinuxSupervisor_SubreaperFailure(t *testing.T) {
	dir := stateTestDir(t)
	launch, err := json.Marshal(launchSpec{Cwd: t.TempDir(), Args: []string{"not-started"}})
	if err != nil {
		t.Fatal(err)
	}
	if err := publish(dir, launchFile, launch); err != nil {
		t.Fatal(err)
	}
	called := false
	code := helperMainWithSubreaper([]string{dir}, "linux", func() error {
		called = true
		return errors.New("injected PR_SET_CHILD_SUBREAPER failure")
	})
	if !called || code != 1 {
		t.Fatalf("subreaper called = %t, helper exit = %d; want true and 1", called, code)
	}
	phase, err := readState(dir, errorFile)
	if err != nil || string(phase) != "helper-posix-failure" {
		t.Fatalf("failure phase = %q, %v; want helper-posix-failure", phase, err)
	}
	for _, name := range []string{scopeFile, exitFile, cleanupFile} {
		if _, err := os.Lstat(filepath.Join(dir, name)); !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("subreaper failure published %s; stat error = %v", name, err)
		}
	}
}
