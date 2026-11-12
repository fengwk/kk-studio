package native

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestPrivateFilePublicAPI(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "private")
	if err := EnsurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "config")
	if err := WritePrivateFile(path, []byte("native")); err != nil {
		t.Fatal(err)
	}
	if err := VerifyPrivateFile(path); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(path)
	if err != nil || string(got) != "native" {
		t.Fatalf("private file = %q, %v", got, err)
	}
}

func TestPrivateConfigCacheWriter(t *testing.T) {
	writer := PrivateConfigCacheWriter{}
	dir := filepath.Join(t.TempDir(), "private-cache")
	if err := EnsurePrivateDirectory(dir); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "cache")

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := writer.WritePrivateAtomic(ctx, path, []byte("ignored")); err != context.Canceled {
		t.Fatalf("canceled write error = %v, want %v", err, context.Canceled)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("canceled write created the cache file: %v", err)
	}
	if err := writer.WritePrivateAtomic(context.Background(), "", nil); err == nil {
		t.Fatal("empty cache path accepted")
	}
	if err := writer.WritePrivateAtomic(context.Background(), path, []byte("cache")); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(path)
	if err != nil || string(got) != "cache" {
		t.Fatalf("cache file = %q, %v", got, err)
	}
}
