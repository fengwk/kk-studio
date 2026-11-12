package h2xconnect

import (
	"os"
	"strings"
	"testing"
)

// TRN-R-001 verifies Extended CONNECT can be enabled without discarding existing GODEBUG settings.
func TestEnableExtendedConnect(t *testing.T) {
	for _, tc := range []struct {
		name  string
		input string
		want  string
	}{
		{"empty", "", "http2xconnect=1"},
		{"preserve existing settings", "netdns=go", "netdns=go,http2xconnect=1"},
		{"avoid duplicate setting", "netdns=go,http2xconnect=1", "netdns=go,http2xconnect=1"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := enableExtendedConnect(tc.input); got != tc.want {
				t.Fatalf("enableExtendedConnect(%q) = %q, want %q", tc.input, got, tc.want)
			}
		})
	}
}

func TestInitAppendsAndPreservesGODEBUG(t *testing.T) {
	if !strings.Contains(os.Getenv("GODEBUG"), "http2xconnect=1") {
		t.Fatalf("GODEBUG = %q, missing http2xconnect=1", os.Getenv("GODEBUG"))
	}
	original := OriginalGODEBUG()
	want := original
	if !strings.Contains(original, "http2xconnect=1") {
		if original == "" {
			want = "http2xconnect=1"
		} else {
			want = original + ",http2xconnect=1"
		}
	}
	if got := os.Getenv("GODEBUG"); got != want {
		t.Fatalf("GODEBUG = %q, want %q", got, want)
	}
}
