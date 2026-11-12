package native

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

// Malformed launch records fail closed with a fixed error phase.
func TestInternalHelper_FailsClosedOnInvalidLaunch(t *testing.T) {
	if code := HelperMain(nil); code != 2 {
		t.Fatalf("missing state directory exit = %d, want 2", code)
	}
	for _, args := range [][]string{{"relative"}, {filepath.Join(t.TempDir(), "control\npath")}} {
		if code := HelperMain(args); code != 2 {
			t.Fatalf("invalid helper args %q exit = %d, want 2", args, code)
		}
	}

	for _, testCase := range []struct {
		name    string
		payload []byte
	}{
		{name: "missing"},
		{name: "malformed", payload: []byte("{")},
		{name: "incomplete", payload: []byte(`{"cwd":"/tmp"}`)},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			dir := stateTestDir(t)
			if testCase.payload != nil {
				if err := publish(dir, launchFile, testCase.payload); err != nil {
					t.Fatal(err)
				}
			}
			if runtime.GOOS == "windows" {
				t.Setenv("KK_STUDIO_NATIVE_STDIN_HANDLE", "")
			}
			if code := HelperMain([]string{dir}); code != 1 {
				t.Fatalf("helper exit = %d, want 1", code)
			}
			phase, err := readState(dir, errorFile)
			if err != nil || string(phase) != "helper-launch-failure" {
				t.Fatalf("error phase = %q, %v", phase, err)
			}
		})
	}
}

// Malformed launch data is never copied into the helper's diagnostic record.
func TestInternalHelper_DoesNotEchoLaunchData(t *testing.T) {
	dir := stateTestDir(t)
	secret := filepath.Join(t.TempDir(), "secret-command")
	if err := publish(dir, launchFile, []byte("invalid:"+secret)); err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS == "windows" {
		t.Setenv("KK_STUDIO_NATIVE_STDIN_HANDLE", "")
	}
	if code := HelperMain([]string{dir}); code != 1 {
		t.Fatalf("helper exit = %d, want 1", code)
	}
	phase, err := readState(dir, errorFile)
	if err != nil || string(phase) != "helper-launch-failure" {
		t.Fatalf("error phase = %q, %v", phase, err)
	}
	if data, err := os.ReadFile(filepath.Join(dir, errorFile)); err != nil || string(data) != "helper-launch-failure" {
		t.Fatalf("published diagnostic = %q, %v", data, err)
	}
}
