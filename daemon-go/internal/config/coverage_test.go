package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	runtime "runtime"
	"strings"
	"testing"
)

// RUN-T-183 covers list, marker, control-character, and declaration-order validation branches.
func TestConfigValidationHelpers(t *testing.T) {
	if validStringList(nil, true) || !validStringList(nil, false) || !validStringList([]string{"a", "b"}, true) || validStringList([]string{" "}, false) || validStringList([]string{"bad\x00value"}, false) {
		t.Fatal("string-list validation returned an unexpected result")
	}
	for _, path := range []string{"a/b", "README.md"} {
		if !validRelativePath(path) {
			t.Fatalf("valid relative path %q rejected", path)
		}
	}
	for _, path := range []string{"", ".", "..", "a/../b", "a//b", `/absolute`, `a\\b`, "a:b", "a\x00b"} {
		if validRelativePath(path) {
			t.Fatalf("invalid relative path %q accepted", path)
		}
	}
	if hasControl("safe") || !hasControl("line\u2028break") || hasControl("ordinary") {
		t.Fatal("control-character validation returned an unexpected result")
	}
	if hasUnknown(map[string]json.RawMessage{"known": nil}, "known") || !hasUnknown(map[string]json.RawMessage{"extra": nil}, "known") {
		t.Fatal("unknown-field validation returned an unexpected result")
	}
	if _, err := orderedJSONKeys([]byte(`[]`)); err == nil {
		t.Fatal("accepted a non-object JSON value")
	}
	if _, err := orderedJSONKeys([]byte(`{"key":1} trailing`)); err == nil {
		t.Fatal("accepted trailing JSON data")
	}
	if _, err := orderedJSONKeys([]byte(`{"key":`)); err == nil {
		t.Fatal("accepted an incomplete JSON object")
	}
	for _, document := range [][]byte{
		[]byte(`{"key":1,"key":2}`),
		[]byte(strings.Repeat("[", 22) + "0" + strings.Repeat("]", 22)),
		[]byte(strings.Repeat("9", 65)),
		[]byte(`{} {}`),
	} {
		if err := rejectDuplicateKeys(document); err == nil {
			t.Errorf("accepted invalid strict JSON %q", document)
		}
	}
}

// RUN-T-165/176/185 exercise hot Bash resolution and executable path validation.
func TestResolveExecutableBranches(t *testing.T) {
	resolved, err := ResolveExecutable(os.Args[0])
	if err != nil || !filepath.IsAbs(resolved) {
		t.Fatalf("test executable resolution = %q, %v", resolved, err)
	}
	for _, value := range []string{"", filepath.Join(t.TempDir(), "missing")} {
		if _, err := ResolveExecutable(value); err == nil {
			t.Fatalf("resolved invalid executable %q", value)
		}
	}
	if _, err := ResolveExecutable(t.TempDir()); err == nil {
		t.Fatal("accepted a directory as an executable")
	}
	if runtime.GOOS != "windows" {
		path := filepath.Join(t.TempDir(), "not-executable")
		if err := os.WriteFile(path, []byte("data"), 0600); err != nil {
			t.Fatal(err)
		}
		if _, err := ResolveExecutable(path); err == nil {
			t.Fatal("accepted a non-executable file")
		}
	}
	testExecutable := os.Args[0]
	if resolved, err := ResolveBash(&testExecutable); err != nil || resolved == "" {
		t.Fatalf("ResolveBash(test executable) = %q, %v", resolved, err)
	}
	invalidBash := filepath.Join(t.TempDir(), "missing-bash")
	if _, err := ResolveBash(&invalidBash); err == nil {
		t.Fatal("resolved a missing hot-configuration Bash executable")
	}
}

// RUN-T-168/171/179 reject non-private, non-regular, and empty registration tokens.
func TestReadRegistrationTokenFailures(t *testing.T) {
	path := writeConfigFixture(t, `{"environmentId":"123e4567-e89b-42d3-a456-426614174000","version":"0","daemonConfig":{"studioUrl":"http://localhost","lsp":{"servers":{"x":{"command":["x"],"extensions":[".x"]}}}}}`)
	tokenPath := filepath.Join(filepath.Dir(path), "daemon.token")
	if err := os.WriteFile(tokenPath, []byte("  \n"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadRegistrationToken(path); err == nil {
		t.Fatal("accepted an empty trimmed token")
	}
	if runtime.GOOS != "windows" {
		if err := os.WriteFile(tokenPath, []byte("token"), 0600); err != nil {
			t.Fatal(err)
		}
		if err := os.Chmod(tokenPath, 0644); err != nil {
			t.Fatal(err)
		}
		if _, err := ReadRegistrationToken(path); err == nil {
			t.Fatal("accepted a token accessible by group or other users")
		}
	}
	if err := os.Remove(tokenPath); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadRegistrationToken(path); err == nil {
		t.Fatal("accepted a missing token file")
	}
	if err := os.WriteFile(tokenPath, []byte("token"), 0600); err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" {
		if err := os.Chmod(tokenPath, 0200); err != nil {
			t.Fatal(err)
		}
		if _, err := ReadRegistrationToken(path); err == nil {
			t.Fatal("accepted a token without owner read permission")
		}
	}
	if err := os.Remove(tokenPath); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(tokenPath, 0700); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadRegistrationToken(path); err == nil {
		t.Fatal("accepted a directory as the token file")
	}
}

// RUN-T-160/166/167/182/183 exercise additional environment validation branches.
func TestLoadRejectsInvalidConfigurationBranches(t *testing.T) {
	base := `{"environmentId":"123e4567-e89b-42d3-a456-426614174000","version":"0","daemonConfig":{"studioUrl":"http://localhost","lsp":{"servers":{"x":{"command":["x"],"extensions":[".x"]}}}}}`
	cases := []string{
		strings.Replace(base, `"studioUrl":"http://localhost"`, `"studioUrl":"http://localhost?query=secret"`, 1),
		strings.Replace(base, `"lsp":{"servers":`, `"lsp":{"servers":{}}`, 1),
		strings.Replace(base, `"command":["x"]`, `"command":[]`, 1),
		strings.Replace(base, `"extensions":[".x"]`, `"extensions":[]`, 1),
		strings.Replace(base, `"extensions":[".x"]`, `"extensions":["."]`, 1),
		strings.Replace(base, `"extensions":[".x"]`, `"extensions":[".x\\y"]`, 1),
		strings.Replace(base, `"extensions":[".x"]`, `"extensions":[".x\\n"]`, 1),
		strings.Replace(base, `"extensions":[".x"]`, `"extensions":[".x"],"rootMarkers":["../escape"]`, 1),
		strings.Replace(base, `"studioUrl":"http://localhost"`, `"studioUrl":"http://localhost","bashExecutable":"  "`, 1),
		strings.Replace(base, `"studioUrl":"http://localhost"`, `"studioUrl":"http://localhost","proxyUrl":"https://proxy.example:1234"`, 1),
		strings.Replace(base, `"studioUrl":"http://localhost"`, `"studioUrl":"http://localhost","unknown":true`, 1),
	}
	for index, document := range cases {
		path := writeConfigFixture(t, document)
		if _, err := LoadDaemonConfig(path); err == nil {
			t.Errorf("case %d accepted invalid configuration", index)
		}
	}
}

// RUN-T-159/166/167/177 cover optional config values and derived executable paths.
func TestLoadOptionalFieldsAndDistributionPaths(t *testing.T) {
	document := `{"environmentId":"123e4567-e89b-42d3-a456-426614174000","version":"9","daemonConfig":{"studioUrl":"https://studio.example/","bashExecutable":"/bin/bash","proxyUrl":"http://proxy.example:8080","lsp":{"servers":{"go":{"command":["gopls"],"extensions":[".go"],"rootMarkers":["go.mod"],"firstMatchMarkers":["go.work"]}}}}}`
	path := writeConfigFixture(t, document)
	got, err := LoadDaemonConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if got.ConfigVersion != 9 || got.ProxyURL == nil || *got.ProxyURL != "http://proxy.example:8080" || got.BashExecutable == nil || *got.BashExecutable != "/bin/bash" || len(got.LSP) != 1 || got.LSP[0].FirstMatchMarkers[0] != "go.work" {
		t.Fatalf("optional config fields = %+v", got)
	}
	if DistributionDir(filepath.Join("/opt", "dist", "daemon")) != filepath.Join("/opt") || DistributionDir(filepath.Join("/opt", "bin", "daemon")) != filepath.Join("/opt", "bin") {
		t.Fatal("distribution directory resolution returned an unexpected path")
	}
}

// RUN-T-180 rejects an oversized configuration before parsing it.
func TestLoadRejectsOversizedConfig(t *testing.T) {
	path := writeConfigFixture(t, strings.Repeat(" ", maxConfigBytes+1))
	if _, err := LoadDaemonConfig(path); err == nil {
		t.Fatal("accepted oversized configuration")
	}
}

// RUN-T-161/162/163/167/181/182 cover malformed nested configuration fields.
func TestLoadRejectsInvalidConfigShapes(t *testing.T) {
	base := `{"environmentId":"123e4567-e89b-42d3-a456-426614174000","version":"0","daemonConfig":{"studioUrl":"http://localhost","lsp":{"servers":{"x":{"command":["x"],"extensions":[".x"]}}}}}`
	cases := []string{
		`[]`,
		strings.Replace(base, `"studioUrl":"http://localhost"`, `"studioUrl":"https://user@localhost"`, 1),
		strings.Replace(base, `"studioUrl":"http://localhost"`, `"studioUrl":"http://localhost/path"`, 1),
		strings.Replace(base, `"version":"0"`, `"version":"01"`, 1),
		strings.Replace(base, `"environmentId":"123e4567-e89b-42d3-a456-426614174000"`, `"environmentId":"INVALID"`, 1),
		strings.Replace(base, `"lsp":{"servers":`, `"lsp":{"extra":1,"servers":`, 1),
		strings.Replace(base, `"command":["x"]`, `"command":"x"`, 1),
		strings.Replace(base, `"extensions":[".x"]`, `"extensions":["x"]`, 1),
		strings.Replace(base, `"command":["x"]`, `"command":[" "]`, 1),
		strings.Replace(base, `"extensions":[".x"]`, `"extensions":["a/b"]`, 1),
	}
	for index, document := range cases {
		path := writeConfigFixture(t, document)
		if _, err := LoadDaemonConfig(path); err == nil {
			t.Errorf("case %d accepted invalid configuration", index)
		}
	}
}
