package lsp

import (
	"strings"
	"testing"
)

// LSP-T-016 through LSP-T-026 verify text decoding and binary detection boundaries.
func TestCodec_Decode(t *testing.T) {
	tests := []struct {
		name          string
		data          []byte
		text, charset string
		bom           int
		err           string
	}{
		{"utf8", []byte("hello"), "hello", "utf-8", 0, ""},
		{"utf8 bom", []byte{0xef, 0xbb, 0xbf, 'a'}, "a", "utf-8", 3, ""},
		{"double bom", []byte{0xef, 0xbb, 0xbf, 0xef, 0xbb, 0xbf, 'a'}, "a", "utf-8", 3, ""},
		{"utf16le", []byte{0xff, 0xfe, 'h', 0, 'i', 0}, "hi", "utf-16le", 2, ""},
		{"utf16be", []byte{0xfe, 0xff, 0, 'h', 0, 'i'}, "hi", "utf-16be", 2, ""},
		{"invalid", []byte{0xc0, 0xaf}, "", "", 0, "file appears to be binary: invalid text encoding"},
		{"nul", []byte("a\x00b"), "", "", 0, "file appears to be binary: contains NUL character"},
		{"control", []byte{1}, "", "", 0, "file appears to be binary"},
		{"empty", nil, "", "utf-8", 0, ""},
		{"single byte", []byte{'a'}, "a", "utf-8", 0, ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := DecodeTextFile(tt.data)
			if tt.err != "" {
				if err == nil || !strings.Contains(err.Error(), tt.err) {
					t.Fatalf("error = %v, want containing %q", err, tt.err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if got.Text != tt.text || got.Charset != tt.charset || got.BOMLength != tt.bom {
				t.Fatalf("got %#v", got)
			}
		})
	}
}

// LSP-T-023 and LSP-T-024 cover malformed UTF-16 and NUL detection after BOM decoding.
func TestCodec_BOMControlAndMalformedUTF16(t *testing.T) {
	control, err := DecodeTextFile([]byte{0xef, 0xbb, 0xbf, 1})
	if err != nil || control.Text != string([]byte{1}) {
		t.Fatalf("BOM control bytes = %#v, %v", control, err)
	}
	for _, data := range [][]byte{{0xff, 0xfe, 0x00}, {0xff, 0xfe, 0x00, 0xd8}, {0xfe, 0xff, 0xdc, 0x00}} {
		if _, err := DecodeTextFile(data); err == nil || err.Error() != "file appears to be binary: invalid text encoding" {
			t.Errorf("DecodeTextFile(%x) error = %v", data, err)
		}
	}
	if _, err := DecodeTextFile([]byte{0xff, 0xfe, 0, 0}); err == nil || err.Error() != "file appears to be binary: contains NUL character" {
		t.Fatalf("BOM text NUL error = %v", err)
	}
}

// LSP-T-026 ensures control-byte probing excludes byte offset 8192.
func TestCodec_ProbeBoundary(t *testing.T) {
	data := make([]byte, 8193)
	for i := range data {
		data[i] = 'a'
	}
	data[8192] = 1
	if _, err := DecodeTextFile(data); err != nil {
		t.Fatalf("byte 8192 should be ignored: %v", err)
	}
	data[8191] = 1
	if _, err := DecodeTextFile(data); err == nil {
		t.Fatal("byte 8191 should be rejected")
	}
}
