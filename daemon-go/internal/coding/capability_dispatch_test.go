package coding

import (
	"context"
	"encoding/json"
	"io"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/coding/image"
	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// COD-N-003
func TestParseImageTierAcceptsEveryTier(t *testing.T) {
	for _, test := range []struct {
		input runtime.ImageInputTier
		want  image.Tier
	}{
		{runtime.ImageInputTierP720, image.P720},
		{runtime.ImageInputTierP1080, image.P1080},
		{runtime.ImageInputTierOriginal, image.Original},
	} {
		got, err := parseImageTier(test.input)
		if err != nil || got != test.want {
			t.Fatalf("tier=%q got=%v err=%v", test.input, got, err)
		}
	}
}

// COD-F-017
func TestProcessCapabilityDefaultsWorkdirAndDispatches(t *testing.T) {
	process := &fakeProcess{stdout: io.NopCloser(strings.NewReader("done")), stderr: io.NopCloser(strings.NewReader("")), wait: func(context.Context) (native.ProcessExit, error) { return testProcessExit(0), nil }}
	arguments, err := json.Marshal(map[string]string{"command": "true"})
	if err != nil {
		t.Fatal(err)
	}
	dataDir := t.TempDir()
	result, err := processCapability(context.Background(), runtime.CapabilityCall{InvocationID: "default-workdir", Arguments: arguments}, runtime.CapabilityContext{Config: config.DaemonConfig{DataDir: dataDir}, Processes: fakeSupervisor{process}})
	if err != nil || result.Error || !strings.HasPrefix(result.Contents[0].Text, "done") {
		t.Fatalf("result=%#v err=%v", result, err)
	}
}

// COD-F-008
func TestEditAndWritePreviewRejectCancelledCalls(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	writeArgs, err := json.Marshal(map[string]string{"path": t.TempDir() + "/new.txt", "content": "new"})
	if err != nil {
		t.Fatal(err)
	}
	write, err := previewWriteCapability(ctx, runtime.CapabilityCall{InvocationID: "cancel-preview", Arguments: writeArgs}, runtime.CapabilityContext{})
	if err != nil || !write.Error || !strings.Contains(write.Contents[0].Text, "Operation cancelled") {
		t.Fatalf("write preview=%#v err=%v", write, err)
	}
	editArgs, err := json.Marshal(map[string]string{"path": t.TempDir() + "/missing.txt", "old_string": "old", "new_string": "new"})
	if err != nil {
		t.Fatal(err)
	}
	edit, err := editPreviewCapability(ctx, runtime.CapabilityCall{InvocationID: "cancel-edit", Arguments: editArgs}, runtime.CapabilityContext{})
	if err != nil || !edit.Error || !strings.Contains(edit.Contents[0].Text, "Operation cancelled") {
		t.Fatalf("edit preview=%#v err=%v", edit, err)
	}
}
