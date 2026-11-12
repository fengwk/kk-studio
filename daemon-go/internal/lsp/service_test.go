package lsp

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

// LSP-T-015 verifies service-level client accounting and file refresh routing.
func TestService_ActiveClientCount(t *testing.T) {
	root := t.TempDir()
	executable := filepath.Join(root, "fake-lsp")
	if err := os.WriteFile(executable, []byte("fake"), 0700); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(root, "main.go")
	if err := os.WriteFile(file, []byte("package main\n"), 0600); err != nil {
		t.Fatal(err)
	}
	discovery, err := NewDiscovery([]config.LSPServerConfig{{ID: "go", Command: []string{executable}, Extensions: []string{".go"}}}, ResolverOptions{})
	if err != nil {
		t.Fatal(err)
	}
	supervisor := &fakeSupervisor{}
	service := NewService(discovery, supervisor, PoolOptions{IdleTimeout: time.Hour})
	support := service.Support(file)
	if !support.Supported || !support.Available || support.Language == nil || *support.Language != "go" {
		t.Fatalf("support = %#v", support)
	}
	if service.ActiveClientCount() != 0 {
		t.Fatal("new service has an active client")
	}
	var confirmed int
	result, err := service.GotoDefinition(context.Background(), file, 1, 0, func() { confirmed++ })
	if err != nil || result != filepath.FromSlash("/tmp/target.go")+":3:4" {
		t.Fatalf("definition = %q, %v", result, err)
	}
	if service.ActiveClientCount() != 1 || supervisor.starts != 1 || confirmed != 1 {
		t.Fatalf("clients=%d starts=%d confirmations=%d", service.ActiveClientCount(), supervisor.starts, confirmed)
	}
	service.FileChanged(file)
	if err := service.Close(); err != nil {
		t.Fatal(err)
	}
	if service.ActiveClientCount() != 0 {
		t.Fatal("closed service still reports active clients")
	}
}

// LSP-T-015 covers optional callback selection and request timeout construction.
func TestService_RequestHelpers(t *testing.T) {
	if firstCallback(nil) != nil {
		t.Fatal("missing callback returned a function")
	}
	firstCalls, secondCalls := 0, 0
	selected := firstCallback([]func(){func() { firstCalls++ }, func() { secondCalls++ }})
	selected()
	if firstCalls != 1 || secondCalls != 0 {
		t.Fatalf("callback counts = %d, %d", firstCalls, secondCalls)
	}
	ctx, cancel := requestContext(context.Background(), 25)
	defer cancel()
	if _, ok := ctx.Deadline(); !ok {
		t.Fatal("positive timeout did not set a deadline")
	}
	noTimeout, noCancel := requestContext(context.Background(), 0)
	defer noCancel()
	if _, ok := noTimeout.Deadline(); ok {
		t.Fatal("zero timeout unexpectedly set a deadline")
	}
	if got := joinItems([]string{"one", "two"}); got != "one\ntwo" {
		t.Fatalf("joined items = %q", got)
	}
}

// LSP-F-016 and LSP-F-024 retain stable path validation error text.
func TestValidatePathErrors(t *testing.T) {
	dir := t.TempDir()
	missing := filepath.Join(dir, "missing")
	unnormalizedMissing := dir + string(filepath.Separator) + "nested" + string(filepath.Separator) + ".." + string(filepath.Separator) + "missing"
	cases := []struct {
		path string
		want string
	}{{"", "path is required"}, {"   ", "path is required"}, {"relative", "path must be an absolute path (a relative or non-absolute value was given)"}, {missing, "path does not exist: " + filepath.ToSlash(missing)}, {unnormalizedMissing, "path does not exist: " + filepath.ToSlash(missing)}}
	for _, tc := range cases {
		_, err := validatePath(tc.path)
		if err == nil || err.Error() != tc.want {
			t.Errorf("validatePath(%q) error = %v, want %q", tc.path, err, tc.want)
		}
	}
}
