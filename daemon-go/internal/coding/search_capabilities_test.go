package coding

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// COD-T-189, COD-T-190
func TestSearchTimeoutEmptyResultsAreNonError(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "a.txt"), []byte("hello\n"), 0600); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithDeadline(context.Background(), time.Unix(1, 0))
	defer cancel()
	for _, test := range []struct {
		name     string
		executor runtime.CapabilityExecutor
		args     string
		empty    string
	}{
		{name: "find", executor: findCapability, args: `{"path":"` + root + `","pattern":"*.zzz","limit":10}`, empty: "No files found matching pattern"},
		{name: "grep", executor: grepCapability, args: `{"path":"` + root + `","pattern":"zzz","literal":true,"limit":10}`, empty: "No matches found"},
	} {
		t.Run(test.name, func(t *testing.T) {
			call := runtime.CapabilityCall{InvocationID: test.name, Arguments: json.RawMessage(test.args), TimeoutMillis: 2000}
			result, err := test.executor(ctx, call, runtime.CapabilityContext{})
			if err != nil || result.Error {
				t.Fatalf("result=%#v err=%v", result, err)
			}
			if !strings.Contains(result.Contents[0].Text, "[Search timed out after 2s; results are incomplete. Narrow the path or pattern, or raise timeout_seconds.]") || strings.Contains(result.Contents[0].Text, test.empty) {
				t.Fatalf("text=%q", result.Contents[0].Text)
			}
			var details struct {
				Search struct {
					Outcome    string `json:"outcome"`
					Incomplete bool   `json:"incomplete"`
				} `json:"search"`
			}
			if err := json.Unmarshal(result.Details, &details); err != nil {
				t.Fatal(err)
			}
			if details.Search.Outcome != "TIMED_OUT" || !details.Search.Incomplete {
				t.Fatalf("details=%s", result.Details)
			}
		})
	}
}

// COD-T-188
func TestSearchCancellationReturnsCancelledError(t *testing.T) {
	root := t.TempDir()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	call := runtime.CapabilityCall{InvocationID: "cancel", Arguments: json.RawMessage(`{"path":"` + root + `","pattern":"x"}`)}
	result, err := findCapability(ctx, call, runtime.CapabilityContext{})
	if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, "context canceled") {
		t.Fatalf("result=%#v err=%v", result, err)
	}
	var details map[string]any
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	searchDetails := details["search"].(map[string]any)
	if searchDetails["outcome"] != "CANCELLED" || searchDetails["incomplete"] != true {
		t.Fatalf("details=%v", details)
	}
}
