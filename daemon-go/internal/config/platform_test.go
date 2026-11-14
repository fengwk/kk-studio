package config

import (
	"os"
	"testing"
)

// A missing home must reject home-relative commands without breaking absolute commands.
func TestResolveExecutableWithoutHome(t *testing.T) {
	t.Setenv("HOME", "")
	t.Setenv("USERPROFILE", "")
	if _, err := ResolveExecutable("~/bin/jdtls"); err == nil {
		t.Fatal("resolved a home-relative command without a home directory")
	}
	if _, err := ResolveExecutable(os.Args[0]); err != nil {
		t.Fatalf("absolute command incorrectly required a home directory: %v", err)
	}
}

// RUN-T-187 checks the supported release keys and fails closed for unsupported targets.
func TestReleaseKey(t *testing.T) {
	for _, tc := range []struct{ os, arch, want string }{{"linux", "amd64", "linux-amd64"}, {"linux", "arm64", "linux-arm64"}, {"darwin", "amd64", "macos-amd64"}, {"darwin", "arm64", "macos-arm64"}, {"windows", "amd64", "windows-amd64"}} {
		got, err := ReleaseKey(tc.os, tc.arch)
		if err != nil || got != tc.want {
			t.Fatalf("ReleaseKey(%q,%q)=(%q,%v)", tc.os, tc.arch, got, err)
		}
	}
	if _, err := ReleaseKey("plan9", "amd64"); err == nil {
		t.Fatal("unsupported OS accepted")
	}
	if _, err := ReleaseKey("linux", "386"); err == nil {
		t.Fatal("unsupported architecture accepted")
	}
	if _, err := ReleaseKey("windows", "arm64"); err == nil {
		t.Fatal("Windows ARM64 has no bundled release and must be rejected")
	}
}
