package lsp

import (
	"context"
	"net/url"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// LSP-F-014 verifies content-based document versions and language identifiers.
func TestDocumentSyncVersionsAndLanguageID(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sample.unknown")
	if err := os.WriteFile(path, []byte("first\n"), 0600); err != nil {
		t.Fatal(err)
	}
	supervisor := &fakeSupervisor{}
	client, err := Launch(context.Background(), ServerConfig{ID: "custom", Command: []string{"fake"}}, t.TempDir(), "/fake", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err = client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	if err = client.Sync(path); err != nil {
		t.Fatal(err)
	}
	if err = client.Sync(filepath.Join(filepath.Dir(path), "missing.go")); err == nil || err.Error() != "LSP request failed: the source file could not be read" {
		t.Fatalf("missing source sync = %v", err)
	}
	binaryPath := filepath.Join(filepath.Dir(path), "binary.go")
	if err = os.WriteFile(binaryPath, []byte{0x00}, 0600); err != nil {
		t.Fatal(err)
	}
	if err = client.Sync(binaryPath); err == nil || err.Error() != "LSP request failed: the source file is not valid text" {
		t.Fatalf("binary source sync = %v", err)
	}
	abs, _ := filepath.Abs(path)
	if doc := client.documents[abs]; doc.version != 1 || doc.text != "first\n" {
		t.Fatalf("opened document = %#v", doc)
	}
	if err = client.Sync(path); err != nil {
		t.Fatal(err)
	}
	if doc := client.documents[abs]; doc.version != 1 {
		t.Fatalf("unchanged document advanced to version %d", doc.version)
	}
	if err = os.WriteFile(path, []byte("second\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if err = client.Sync(path); err != nil {
		t.Fatal(err)
	}
	if doc := client.documents[abs]; doc.version != 2 || doc.text != "second\n" {
		t.Fatalf("changed document = %#v", doc)
	}
	for _, tc := range []struct{ ext, want string }{{".ts", "typescript"}, {".tsx", "typescriptreact"}, {".jsx", "javascriptreact"}, {".java", "java"}, {".pyi", "python"}, {".h", "c"}, {".hpp", "cpp"}, {".unknown", "custom"}} {
		if got := languageID(tc.ext, "custom"); got != tc.want {
			t.Errorf("languageID(%q) = %q, want %q", tc.ext, got, tc.want)
		}
	}
	client.Refresh(filepath.Join(filepath.Dir(path), "not-opened.unknown"))
	if err = client.Stop(time.Millisecond); err != nil {
		t.Fatal(err)
	}
	client.Refresh(path)
}

// LSP-T-097 verifies both JDT URI and local class-file decompilation paths.
func TestProtocol_JavaDecompile(t *testing.T) {
	root := t.TempDir()
	classFile := filepath.Join(root, "Example.class")
	if err := os.WriteFile(classFile, []byte{0xca, 0xfe}, 0600); err != nil {
		t.Fatal(err)
	}
	supervisor := &fakeSupervisor{}
	client, err := Launch(context.Background(), ServerConfig{ID: "java", Command: []string{"/opt/jdtls/bin/jdtls"}}, root, "/opt/jdtls/bin/jdtls", supervisor)
	if err != nil {
		t.Fatal(err)
	}
	if err = client.Initialize(context.Background(), time.Second); err != nil {
		t.Fatal(err)
	}
	for _, target := range []string{"See jdt://contents/java.base/java/lang/String.class in output", classFile, (&url.URL{Scheme: "file", Path: filepath.ToSlash(classFile)}).String()} {
		got, err := client.JavaDecompile(context.Background(), target)
		if err != nil || got != "class contents" {
			t.Errorf("JavaDecompile(%q) = %q, %v", target, got, err)
		}
	}
	for _, target := range []string{"relative.class", "file://", filepath.Join(root, "missing.class"), root} {
		if _, err := client.JavaDecompile(context.Background(), target); err == nil {
			t.Errorf("JavaDecompile(%q) unexpectedly succeeded", target)
		}
	}
	if err = client.Stop(time.Millisecond); err != nil {
		t.Fatal(err)
	}
}
