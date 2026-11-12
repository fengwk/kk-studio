package ide

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

// IDE-T-041
func TestBundledExtensionDefaults(t *testing.T) {
	path := filepath.Join("..", "..", "ide-extension", "package.json")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var manifest struct {
		Main             string   `json:"main"`
		ActivationEvents []string `json:"activationEvents"`
		Contributes      struct {
			Defaults map[string]any `json:"configurationDefaults"`
		} `json:"contributes"`
	}
	if err := json.Unmarshal(data, &manifest); err != nil {
		t.Fatal(err)
	}
	want := map[string]any{
		"workbench.colorTheme":                       "Dark Modern",
		"window.density.editorTabHeight":             "compact",
		"chat.disableAIFeatures":                     true,
		"extensions.ignoreRecommendations":           true,
		"files.watcherExclude":                       map[string]any{"**": true},
		"task.autoDetect":                            "off",
		"npm.autoDetect":                             "off",
		"npm.fetchOnlinePackageInfo":                 false,
		"typescript.disableAutomaticTypeAcquisition": true,
	}
	if !reflect.DeepEqual(manifest.Contributes.Defaults, want) || manifest.ActivationEvents == nil || len(manifest.ActivationEvents) != 1 || manifest.ActivationEvents[0] != "*" || manifest.Main != "./extension.cjs" {
		t.Fatalf("unexpected extension defaults: %#v", manifest)
	}
}
