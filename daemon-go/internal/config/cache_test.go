package config

import (
	"bytes"
	"testing"
)

// RUN-T-188 keeps the cached JSON shape stable and preserves ordered LSP declarations.
func TestSerializeDaemonCachePreservesConfigurationOrder(t *testing.T) {
	got, err := SerializeDaemonCache(DaemonConfig{
		EnvironmentID: "id",
		ConfigVersion: 4,
		StudioURL:     "https://studio.example",
		LSP: []LSPServerConfig{
			{ID: "ts", Command: []string{"typescript-language-server"}, Extensions: []string{".ts"}},
			{ID: "go", Command: []string{"gopls"}},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	want := `{"environmentId":"id","version":"4","daemonConfig":{"studioUrl":"https://studio.example","bashExecutable":null,"lsp":{"servers":{"ts":{"command":["typescript-language-server"],"extensions":[".ts"]},"go":{"command":["gopls"]}}},"proxyUrl":null}}`
	if string(got) != want {
		t.Fatalf("serialized cache = %s, want %s", got, want)
	}
}

// RUN-T-188 serializes all optional LSP lists and non-null daemon settings.
func TestSerializeDaemonCacheEmitsOptionalValues(t *testing.T) {
	bash := "/bin/bash"
	proxy := "http://proxy.example:8080"
	got, err := SerializeDaemonCache(DaemonConfig{
		EnvironmentID:         "env",
		ConfigVersion:         9,
		StudioURL:             "http://studio.example",
		BashExecutableSetting: &bash,
		ProxyURL:              &proxy,
		LSP: []LSPServerConfig{{
			ID:                "go",
			Command:           []string{"gopls", "serve"},
			Extensions:        []string{".go"},
			RootMarkers:       []string{"go.mod"},
			FirstMatchMarkers: []string{"go.work"},
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	for _, value := range []string{`"bashExecutable":"/bin/bash"`, `"extensions":[".go"]`, `"rootMarkers":["go.mod"]`, `"firstMatchMarkers":["go.work"]`, `"proxyUrl":"http://proxy.example:8080"`} {
		if !bytes.Contains(got, []byte(value)) {
			t.Errorf("serialized cache lacks %s: %s", value, got)
		}
	}
	got, err = SerializeDaemonCache(DaemonConfig{EnvironmentID: "env", StudioURL: "http://studio.example"})
	if err != nil || !bytes.Contains(got, []byte(`"lsp":null`)) {
		t.Fatalf("empty LSP cache = %s, %v", got, err)
	}
}
