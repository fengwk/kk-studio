package coding

import (
	"os"
	"path/filepath"
	"testing"
)

func mediaSample(t *testing.T, name string) []byte {
	t.Helper()
	path := filepath.Join("../../../platform/runtime/src/test/resources/fun/fengwk/kkstudio/platform/storage/media", filepath.FromSlash(name))
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

// COD-T-116: the shared media fixtures are classified by their file headers.
func TestMediaSniffPositive(t *testing.T) {
	for _, test := range []struct {
		name string
		mime string
	}{
		{"video/sample.mp4", "video/mp4"},
		{"video/sample.mov", "video/quicktime"},
		{"audio/sample.wav", "audio/wav"},
		{"audio/sample-id3.mp3", "audio/mpeg"},
		{"audio/sample-frame.mp3", "audio/mpeg"},
		{"doc/sample.pdf", "application/pdf"},
	} {
		t.Run(test.name, func(t *testing.T) {
			if got := DetectMediaType(mediaSample(t, test.name)); got != test.mime {
				t.Fatalf("DetectMediaType(%q)=%q want=%q", test.name, got, test.mime)
			}
		})
	}
}

// COD-T-117: mislabeled fixtures do not acquire a MIME type from their extension.
func TestMediaSniffFalseExtension(t *testing.T) {
	for _, test := range []struct {
		name  string
		wrong string
	}{
		{"video/sample.mp4.fake", "video/mp4"},
		{"video/sample.mov.fake", "video/mp4"},
		{"audio/sample.wav.fake", "audio/wav"},
		{"audio/sample-id3.mp3.fake", "audio/mpeg"},
		{"audio/sample-frame.mp3.fake", "audio/mpeg"},
		{"doc/sample.pdf.fake", "application/pdf"},
	} {
		t.Run(test.name, func(t *testing.T) {
			got := DetectMediaType(mediaSample(t, test.name))
			if got == test.wrong {
				t.Fatalf("DetectMediaType(%q)=%q; should not infer MIME from extension", test.name, got)
			}
		})
	}
}
