package coding

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// COD-T-167, COD-T-168, COD-T-185: capability roots and grep text arguments are preflighted.
func TestSearchCapabilityRejectsInvalidRootsAndNUL(t *testing.T) {
	for _, capability := range []string{"fs.find", "fs.grep"} {
		result := invokeCoding(t, Provider{}, capability, map[string]any{
			"path": "relative", "pattern": "x",
		}, nil)
		if !result.Error || !strings.Contains(result.Contents[0].Text, "absolute path") {
			t.Errorf("%s relative root result=%#v", capability, result)
		}
	}

	file := filepath.Join(t.TempDir(), "file.txt")
	if err := os.WriteFile(file, []byte("x\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	result := invokeCoding(t, Provider{}, "fs.find", map[string]any{
		"path": file, "pattern": "*",
	}, nil)
	if !result.Error || !strings.Contains(result.Contents[0].Text, "path must be a directory") {
		t.Fatalf("find file root result=%#v", result)
	}

	root := t.TempDir()
	for _, test := range []struct {
		name string
		args map[string]any
		want string
	}{
		{"pattern", map[string]any{"path": root, "pattern": "\x00"}, "pattern must not contain NUL"},
		{"glob", map[string]any{"path": root, "pattern": "x", "glob": "\x00"}, "glob must not contain NUL"},
	} {
		t.Run(test.name, func(t *testing.T) {
			result := invokeCoding(t, Provider{}, "fs.grep", test.args, nil)
			if !result.Error || !strings.Contains(result.Contents[0].Text, test.want) {
				t.Fatalf("grep result=%#v want %q", result, test.want)
			}
		})
	}
}

// COD-T-188: grep cancellation remains a CANCELLED error with incomplete search details.
func TestGrepCancellationReturnsCancelledError(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "a.txt"), []byte("x\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	arguments, err := json.Marshal(map[string]any{"path": root, "pattern": "z"})
	if err != nil {
		t.Fatal(err)
	}
	result, err := grepCapability(ctx, runtime.CapabilityCall{InvocationID: "cancelled-grep", Arguments: arguments}, runtime.CapabilityContext{})
	if err != nil || !result.Error || len(result.Contents) != 1 || result.Contents[0].Text != "[Search cancelled; results are incomplete.]" {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	var details struct {
		Code   string `json:"code"`
		Search struct {
			Outcome    string `json:"outcome"`
			Incomplete bool   `json:"incomplete"`
		} `json:"search"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	if details.Code != "CANCELLED" || details.Search.Outcome != "CANCELLED" || !details.Search.Incomplete {
		t.Fatalf("details=%s", result.Details)
	}
}

// COD-T-186: Git's local exclude applies to grep, while .ignore does not.
func TestGrepIgnoreSources(t *testing.T) {
	root := t.TempDir()
	if output, err := exec.Command("git", "init", "-q", root).CombinedOutput(); err != nil {
		t.Fatalf("git init: %s %v", output, err)
	}
	for path, content := range map[string]string{
		filepath.Join(root, ".git", "info", "exclude"): "excluded.txt\n",
		filepath.Join(root, ".ignore"):                 "ignored.txt\n",
		filepath.Join(root, "excluded.txt"):            "needle\n",
		filepath.Join(root, "ignored.txt"):             "needle\n",
	} {
		if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	result := invokeCoding(t, Provider{}, "fs.grep", map[string]any{"path": root, "pattern": "needle"}, nil)
	if result.Error || len(result.Contents) != 1 || result.Contents[0].Text != "ignored.txt:1:needle" {
		t.Fatalf("result=%#v", result)
	}
}
