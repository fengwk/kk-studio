package coding

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/runtime"
)

// COD-N-001
func TestInvocationOutputLimitOverridesSettings(t *testing.T) {
	call := runtime.CapabilityCall{InvocationID: "limited", MaxOutputBytes: 1 << 20}
	handler := func(_ context.Context, call runtime.CapabilityCall, _ runtime.CapabilityContext) (runtime.CapabilityResult, error) {
		return textResult(call.InvocationID, strings.Repeat("x", (1<<20)+1)), nil
	}
	result, err := limitOutput(handler)(context.Background(), call, runtime.CapabilityContext{})
	if err != nil {
		t.Fatal(err)
	}
	if !result.Error {
		t.Fatal("expected per-call output limit failure")
	}
	want := "Tool output is 1.000001 MiB, which exceeds the tool output size limit of 1 MiB. The tool ran but its output was discarded; side effects may have occurred. Request a smaller result, for example a narrower read range or filtered command output, or ask the user to raise the limit in system settings."
	if result.Contents[0].Text != want {
		t.Fatalf("message=%q", result.Contents[0].Text)
	}
}

// COD-N-001
func TestOutputLimitMiBFormatting(t *testing.T) {
	for _, tc := range []struct {
		size, limit int64
		want        string
	}{
		{17_000_000, 8 << 20, "16.2"},
		{(16 << 20) + 1, 16 << 20, "16.000001"},
		{2 << 20, 1 << 20, "2.0"},
	} {
		t.Run(fmt.Sprint(tc.size), func(t *testing.T) {
			if got := formatMiB(tc.size, tc.limit); got != tc.want {
				t.Fatalf("formatMiB()=%q want=%q", got, tc.want)
			}
		})
	}
}

// COD-N-002
func TestReadMediaRejectsBeforeReadingWholeFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "media.dat")
	data := append([]byte("%PDF"), bytes.Repeat([]byte("x"), 1<<20)...)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	call := runtime.CapabilityCall{InvocationID: "media", Arguments: mustJSON(map[string]any{"path": path}), MaxOutputBytes: 1 << 20}
	result, err := Provider{}.Capabilities()["fs.read"](context.Background(), call, runtime.CapabilityContext{})
	if err != nil {
		t.Fatal(err)
	}
	if !result.Error || !strings.Contains(result.Contents[0].Text, "The file was not read.") {
		t.Fatalf("result=%#v", result)
	}
}

// COD-N-002
func TestReadMediaAtLimit(t *testing.T) {
	path := filepath.Join(t.TempDir(), "media.dat")
	data := append([]byte("%PDF"), bytes.Repeat([]byte("x"), (1<<20)-4)...)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	call := runtime.CapabilityCall{InvocationID: "media", Arguments: mustJSON(map[string]any{"path": path}), MaxOutputBytes: 1 << 20}
	result, err := Provider{}.Capabilities()["fs.read"](context.Background(), call, runtime.CapabilityContext{})
	if err != nil {
		t.Fatal(err)
	}
	if result.Error || len(result.Contents) != 1 || int64(len(result.Contents[0].Bytes)) != call.MaxOutputBytes {
		t.Fatalf("result=%#v", result)
	}
}

// COD-N-003
func TestReadImageUsesRequestedTierAndReportsDimensions(t *testing.T) {
	var encoded bytes.Buffer
	original := image.NewNRGBA(image.Rect(0, 0, 2, 3))
	original.Set(0, 0, color.NRGBA{R: 255, A: 255})
	if err := png.Encode(&encoded, original); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "tiny.png")
	if err := os.WriteFile(path, encoded.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	call := runtime.CapabilityCall{InvocationID: "image", Arguments: mustJSON(map[string]any{"path": path, "image_input_tier": "ORIGINAL"}), MaxOutputBytes: int64(encoded.Len())}
	result, err := Provider{}.Capabilities()["fs.read"](context.Background(), call, runtime.CapabilityContext{})
	if err != nil {
		t.Fatal(err)
	}
	if result.Error || !bytes.Equal(result.Contents[0].Bytes, encoded.Bytes()) {
		t.Fatalf("result=%#v", result)
	}
	var details struct {
		Image struct {
			OriginalWidth  int `json:"originalWidth"`
			OriginalHeight int `json:"originalHeight"`
			DisplayWidth   int `json:"displayWidth"`
			DisplayHeight  int `json:"displayHeight"`
		} `json:"image"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil {
		t.Fatal(err)
	}
	if details.Image.OriginalWidth != 2 || details.Image.OriginalHeight != 3 || details.Image.DisplayWidth != 2 || details.Image.DisplayHeight != 3 {
		t.Fatalf("details=%s", result.Details)
	}
}
