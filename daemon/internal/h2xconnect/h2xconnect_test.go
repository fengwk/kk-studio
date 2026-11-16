package h2xconnect

import (
	"os"
	"strings"
	"testing"
)

// Verifies Extended CONNECT can be enabled without discarding existing GODEBUG settings.
func TestWithSetting(t *testing.T) {
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
			if got := WithSetting(tc.input); got != tc.want {
				t.Fatalf("WithSetting(%q) = %q, want %q", tc.input, got, tc.want)
			}
		})
	}
}

// WithEnvironment rewrites the GODEBUG entry the re-executed daemon inherits.
func TestWithEnvironmentEnablesGODEBUGInPlace(t *testing.T) {
	for _, test := range []struct {
		name string
		env  []string
		want string
	}{
		{name: "missing", env: []string{"PATH=/bin"}, want: "GODEBUG=http2xconnect=1"},
		{name: "append", env: []string{"GODEBUG=netdns=go", "PATH=/bin"}, want: "GODEBUG=netdns=go,http2xconnect=1"},
		{name: "existing", env: []string{"GODEBUG=netdns=go,http2xconnect=1"}, want: "GODEBUG=netdns=go,http2xconnect=1"},
	} {
		t.Run(test.name, func(t *testing.T) {
			got := WithEnvironment(test.env)
			values := make([]string, 0, 1)
			for _, entry := range got {
				if strings.HasPrefix(entry, "GODEBUG=") {
					values = append(values, entry)
				}
			}
			if len(values) != 1 || values[0] != test.want {
				t.Fatalf("WithEnvironment(%q) = %q, want one %q", test.env, got, test.want)
			}
		})
	}
}

// TestInitAppendsAndPreservesGODEBUG pins the value the package leaves in the process environment.
func TestInitAppendsAndPreservesGODEBUG(t *testing.T) {
	if !strings.Contains(os.Getenv("GODEBUG"), "http2xconnect=1") {
		t.Fatalf("GODEBUG = %q, missing http2xconnect=1", os.Getenv("GODEBUG"))
	}
	want := inherited
	if !strings.Contains(inherited, "http2xconnect=1") {
		if inherited == "" {
			want = "http2xconnect=1"
		} else {
			want = inherited + ",http2xconnect=1"
		}
	}
	if got := os.Getenv("GODEBUG"); got != want {
		t.Fatalf("GODEBUG = %q, want %q", got, want)
	}
}
