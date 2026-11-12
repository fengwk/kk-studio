package coding

import (
	"context"
	"os"
	"path/filepath"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// COD-T-025
func TestMutationFileChangedHookRunsAfterSuccessfulWriteAndEdit(t *testing.T) {
	for _, capabilityID := range []string{"fs.write", "fs.edit"} {
		t.Run(capabilityID, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "changed.txt")
			if capabilityID == "fs.edit" {
				if err := os.WriteFile(path, []byte("before\n"), 0o600); err != nil {
					t.Fatal(err)
				}
			}
			args := map[string]any{"path": path, "content": "after\n"}
			if capabilityID == "fs.edit" {
				args = map[string]any{"path": path, "old_string": "before", "new_string": "after"}
			}
			call := runtime.CapabilityCall{InvocationID: "hook", Arguments: mustJSON(args)}
			calls := 0
			changedPath := ""
			result, err := Provider{}.Capabilities()[capabilityID](context.Background(), call, runtime.CapabilityContext{FileChanged: func(changed string) {
				calls++
				changedPath = changed
			}})
			if err != nil || result.Error {
				t.Fatalf("result=%#v err=%v", result, err)
			}
			resolvedPath, err := filepath.EvalSymlinks(path)
			if err != nil {
				t.Fatal(err)
			}
			if changedPath != resolvedPath {
				t.Errorf("hook path=%q want=%q", changedPath, resolvedPath)
			}
			if calls != 1 {
				t.Fatalf("hook calls=%d want=1", calls)
			}
		})
	}
}
