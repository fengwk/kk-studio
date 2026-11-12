package coding

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// COD-F-008
func TestEditCapabilityRejectsInvalidReplacementCases(t *testing.T) {
	for _, test := range []struct {
		name    string
		content string
		old     string
		new     string
		all     bool
		want    string
	}{
		{name: "empty-old", content: "abc", old: "", new: "x", want: "old_string must not be empty"},
		{name: "identical", content: "abc", old: "a\r\nb", new: "a\nb", want: "must differ after line-ending normalization"},
		{name: "not-found", content: "abc", old: "missing", new: "x", want: "Could not find old_string"},
		{name: "multiple", content: "a a", old: "a", new: "x", want: "Found 2 exact matches"},
		{name: "overlap", content: "aaa", old: "aa", new: "x", all: true, want: "overlapping exact matches"},
	} {
		t.Run(test.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "file.txt")
			if err := os.WriteFile(path, []byte(test.content), 0600); err != nil {
				t.Fatal(err)
			}
			args := map[string]any{"path": path, "old_string": test.old, "new_string": test.new, "replace_all": test.all}
			result, err := editCapability(context.Background(), capabilityCall(t, test.name, args), runtime.CapabilityContext{})
			if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, test.want) {
				t.Fatalf("result=%#v err=%v", result, err)
			}
			actual, err := os.ReadFile(path)
			if err != nil || string(actual) != test.content {
				t.Fatalf("rejected edit changed file to %q: %v", actual, err)
			}
		})
	}
}

// COD-F-008
func TestEditCapabilityRejectsMissingPathAndDirectory(t *testing.T) {
	root := t.TempDir()
	missing := filepath.Join(root, "missing.txt")
	directory := filepath.Join(root, "directory")
	if err := os.Mkdir(directory, 0700); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{missing, directory} {
		result, err := editCapability(context.Background(), capabilityCall(t, "reject", map[string]any{"path": path, "old_string": "a", "new_string": "b"}), runtime.CapabilityContext{})
		if err != nil || !result.Error {
			t.Fatalf("path=%s result=%#v err=%v", path, result, err)
		}
	}
}
