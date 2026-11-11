package h2xconnect

import (
	"os"
	"strings"
	"testing"
)

func TestInitEnablesAndPreservesOriginalGODEBUG(t *testing.T) {
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
