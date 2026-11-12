package config

import "testing"

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
