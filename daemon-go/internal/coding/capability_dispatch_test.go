package coding

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/coding/image"
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

// COD-F-016
func TestProcessCapabilityRequiresValidWorkdir(t *testing.T) {
	root := t.TempDir()
	file := filepath.Join(root, "file.txt")
	if err := os.WriteFile(file, []byte("content"), 0o600); err != nil {
		t.Fatal(err)
	}
	missing := filepath.Join(root, "missing")
	for _, test := range []struct {
		name     string
		workdir  string
		omit     bool
		want     string
		wantCode string
	}{
		{name: "required", omit: true, want: "workdir is required", wantCode: "INVALID_ARGUMENT"},
		{name: "absolute", workdir: "relative", want: "workdir must be an absolute path (a relative or non-absolute value was given)", wantCode: "INVALID_ARGUMENT"},
		{name: "exists", workdir: missing, want: "workdir does not exist: " + filepath.ToSlash(missing), wantCode: "NOT_FOUND"},
		{name: "directory", workdir: file, want: "workdir is not a directory: " + filepath.ToSlash(file), wantCode: "INVALID_ARGUMENT"},
	} {
		t.Run(test.name, func(t *testing.T) {
			args := map[string]string{"command": "true"}
			if !test.omit {
				args["workdir"] = test.workdir
			}
			arguments, err := json.Marshal(args)
			if err != nil {
				t.Fatal(err)
			}
			result, err := processCapability(context.Background(), runtime.CapabilityCall{InvocationID: test.name, Arguments: arguments}, runtime.CapabilityContext{})
			if err != nil || !result.Error || !strings.Contains(result.Contents[0].Text, test.want) || !strings.Contains(string(result.Details), `"code":"`+test.wantCode+`"`) {
				t.Fatalf("result=%#v err=%v", result, err)
			}
		})
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
