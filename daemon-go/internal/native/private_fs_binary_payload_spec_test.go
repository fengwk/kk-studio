package native

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

// NAT-T-103 verifies private-file publication preserves arbitrary bytes.
func TestPrivateFS_BinaryPayload(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "private")
	if err := EnsurePrivateDirectory(directory); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(directory, "payload")
	want := []byte{0x00, 0xff, 0x80, 0x01, 0x7f}
	if err := writePrivateFile(path, want); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("binary payload = %v, want %v", got, want)
	}
}
