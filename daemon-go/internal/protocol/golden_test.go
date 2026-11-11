package protocol

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"

	"google.golang.org/protobuf/proto"
)

func TestGoldenMessagesRoundTripExactly(t *testing.T) {
	const goldenDir = "../../../platform/daemon-server/src/main/protocol/golden"
	entries, err := os.ReadDir(goldenDir)
	if err != nil {
		t.Fatal(err)
	}
	count := 0
	for _, entry := range entries {
		if entry.IsDir() || filepath.Ext(entry.Name()) != ".bin" {
			continue
		}
		count++
		t.Run(entry.Name(), func(t *testing.T) {
			original, err := os.ReadFile(filepath.Join(goldenDir, entry.Name()))
			if err != nil {
				t.Fatal(err)
			}
			var envelope Envelope
			if err := proto.Unmarshal(original, &envelope); err != nil {
				t.Fatal(err)
			}
			got, err := proto.Marshal(&envelope)
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(original, got) {
				t.Fatalf("byte mismatch: want %x, got %x", original, got)
			}
		})
	}
	if count != 28 {
		t.Fatalf("golden sample count = %d, want 28", count)
	}
}
