package lsp

import (
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

// LSP-T-079 verifies 1-based code-point input conversion to the negotiated LSP encoding and CRLF handling.
func TestPosition_EncodingsAndBounds(t *testing.T) {
	for _, test := range []struct {
		encoding string
		want     int
	}{{"utf-8", 5}, {"utf-16", 3}, {"utf-32", 2}} {
		pos, err := documentPosition("a😀b\r\nnext", 1, 3, test.encoding)
		if err != nil {
			t.Fatal(err)
		}
		if pos.Line != 0 || pos.Character != test.want {
			t.Errorf("%s position = %v, want character %d", test.encoding, pos, test.want)
		}
	}
	_, err := documentPosition("a\nb", 3, 1, "utf-16")
	if err == nil || err.Error() != "line 3 is beyond the end of the synchronized document" {
		t.Fatalf("line error = %v", err)
	}
	_, err = documentPosition("a\nb", 2, 3, "utf-16")
	if err == nil || err.Error() != "character 3 is beyond line 2 of the synchronized document (1 code points)" {
		t.Fatalf("character error = %v", err)
	}
}

// LSP-T-079 round-trips Unicode code-point columns through all negotiated encodings.
func TestPosition_UnicodeRoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "unicode.go")
	if err := os.WriteFile(path, []byte("first\n汉😀x\r\n"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		encoding string
		wire     int
	}{{"utf-8", 7}, {"utf-16", 3}, {"utf-32", 2}} {
		position, err := documentPosition("first\n汉😀x\r\n", 2, 3, test.encoding)
		if err != nil {
			t.Fatal(err)
		}
		if position.Line != 1 || position.Character != test.wire {
			t.Errorf("%s input position = %v, want wire column %d", test.encoding, position, test.wire)
		}
		client := &Client{positionEncoding: test.encoding}
		locations := client.formatDefinitionLocations(mustDefinitionResult(t, `[{"uri":"`+pathURI(path)+`","range":{"start":{"line":1,"character":`+strconv.Itoa(test.wire)+`}}}]`))
		want := filepath.FromSlash(path) + ":2:3"
		if len(locations) != 1 || locations[0] != want {
			t.Errorf("%s output locations = %v, want %q", test.encoding, locations, want)
		}
	}
}

// Unreadable and non-file targets retain a 1-based server column when text conversion is unavailable.
func TestPosition_OutputFallback(t *testing.T) {
	client := &Client{positionEncoding: "utf-16"}
	locations := client.formatDefinitionLocations(mustDefinitionResult(t, `[{"uri":"jdt://contents/java.base/java/lang/String.class","range":{"start":{"line":0,"character":4}}}]`))
	if len(locations) != 1 || locations[0] != "jdt://contents/java.base/java/lang/String.class:1:5" {
		t.Fatalf("jdt locations = %v", locations)
	}
	missingPath := filepath.Join(t.TempDir(), "missing.go")
	locations = client.formatDefinitionLocations(mustDefinitionResult(t, `[{"uri":"`+pathURI(missingPath)+`","range":{"start":{"line":0,"character":4}}}]`))
	if len(locations) != 1 || locations[0] != missingPath+":1:5" {
		t.Fatalf("unreadable file locations = %v", locations)
	}
	if got, ok := documentColumn("😀x", 1, "utf-16"); ok || got != 0 {
		t.Fatalf("mid-surrogate offset = %d, %v, want unconvertible", got, ok)
	}
	path := filepath.Join(t.TempDir(), "surrogate.go")
	if err := os.WriteFile(path, []byte("😀x"), 0600); err != nil {
		t.Fatal(err)
	}
	locations = client.formatDefinitionLocations(mustDefinitionResult(t, `[{"uri":"`+pathURI(path)+`","range":{"start":{"line":0,"character":1}}}]`))
	if len(locations) != 1 || locations[0] != path+":1:2" {
		t.Fatalf("mid-surrogate locations = %v", locations)
	}
}
