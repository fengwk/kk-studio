package coding

import (
	"bytes"
	"encoding/json"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// Approval must show every changed line ending and commit exactly the previewed text.
func TestWriteReviewsExactLineEndings(t *testing.T) {
	for _, tc := range []struct{ name, before, after, diff string }{
		{"to LF", "a\r\nb\r\n", "a\nb\n", "- 1|a␍\n- 2|b␍\n+ 1|a\n+ 2|b"},
		{"to CRLF", "a\nb\n", "a\r\nb\r\n", "- 1|a\n- 2|b\n+ 1|a␍\n+ 2|b␍"},
		{"mixed", "a\r\r\nb\r\nc\n", "a\r\r\nb\nc\n", "  1|a␍\n- 2|b␍\n+ 2|b\n  3|c"},
		{"content and endings", "a\r\nb\r\n", "a!\nb\n", "- 1|a␍\n- 2|b␍\n+ 1|a!\n+ 2|b"},
		{"unterminated context", "a\r\nlast\r", "a!\r\nlast\r", "- 1|a␍\n+ 1|a!␍\n  2|last␍"},
		{"new file", "", "a\rb\r\nlast\r", "+ 1|a␍b␍\n+ 2|last␍\n\\ No newline at end of file"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "new.txt")
			if tc.before != "" {
				path = writeFixture(t, "existing.txt", []byte(tc.before))
			}
			args := map[string]any{"path": path, "content": tc.after}
			preview := invokeCoding(t, Provider{}, "fs.write.preview", args, nil)
			if preview.Error {
				t.Fatal(preview.Contents[0].Text)
			}
			want := writeReviewJSON(t, preview.Contents[0].JSON)
			if want.Diff != tc.diff {
				t.Fatalf("diff=%q, want %q", want.Diff, tc.diff)
			}
			result := invokeCoding(t, Provider{}, "fs.write", args, nil)
			var details struct{ WriteReview WriteReview }
			if err := json.Unmarshal(result.Details, &details); result.Error || err != nil || !reflect.DeepEqual(want, details.WriteReview) {
				t.Fatalf("commit review=%s, preview=%+v", result.Details, want)
			}
			if got := string(mustRead(t, path)); got != tc.after {
				t.Fatalf("bytes=%q", got)
			}
			if _, err := Write(path, tc.after, nil); err == nil || !strings.Contains(err.Error(), unchangedFileMessage) {
				t.Fatalf("identical write: %v", err)
			}
		})
	}
}

// Both unedited runs and matched no-op entries must retain noncanonical or malformed bytes.
func TestEditPreservesOriginalBytes(t *testing.T) {
	for _, tc := range []struct{ name, path, prefix, skipped string }{
		{"Shift_JIS", "file.py", "# coding: shift_jis\n\x87\x90\nkeep: \x87\x90\n", "≒\nkeep"},
		{"malformed UTF-8", "file.txt", "bad: \xe2\x82!\xff\n", "�!�"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			path := writeFixture(t, tc.path, []byte(tc.prefix+"old\n"))
			args := editArgs(path, replace(tc.skipped, tc.skipped), replace("old", "new"))
			preview := invokeCoding(t, Provider{}, "fs.edit.preview", args, nil)
			var want EditReview
			if err := json.Unmarshal(preview.Contents[0].JSON, &want); preview.Error || err != nil {
				t.Fatalf("preview=%+v", preview.Contents)
			}
			result := invokeCoding(t, Provider{}, "fs.edit", args, nil)
			if result.Error {
				t.Fatal(result.Contents[0].Text)
			}
			if got := mustRead(t, path); !bytes.Equal(got, []byte(tc.prefix+"new\n")) {
				t.Errorf("bytes=%x, want %x", got, tc.prefix+"new\n")
			}
			var details struct{ EditReview EditReview }
			if err := json.Unmarshal(result.Details, &details); err != nil || want != details.EditReview || want.LossyDecode != nil || strings.Contains(result.Contents[0].Text, "Note:") {
				t.Fatalf("preview=%+v, commit=%s, text=%s", want, result.Details, result.Contents[0].Text)
			}
		})
	}
}

// Mapping must not count the unit peeked after an unpaired high surrogate as consumed.
func TestEditPreservesMalformedUTF16(t *testing.T) {
	original := []byte{0xff, 0xfe, 0x00, 0xd8, 'x', 0, '\n', 0}
	path := writeFixture(t, "wide.txt", original)
	result := invokeCoding(t, Provider{}, "fs.edit", editArgs(path, replace("x", "y")), nil)
	if result.Error {
		t.Fatal(result.Contents[0].Text)
	}
	want := []byte{0xff, 0xfe, 0x00, 0xd8, 'y', 0, '\n', 0}
	if got := mustRead(t, path); !bytes.Equal(got, want) {
		t.Fatalf("bytes=%x, want %x", got, want)
	}
}

// Replacing the first half of a shifted run with ASCII cannot leave the second half decodable.
func TestEditRejectsIncompatibleStatefulSplice(t *testing.T) {
	original := declaredBytes(t, "ISO-2022-JP", "# coding: iso-2022-jp\n日本\n")
	path := writeFixture(t, "file.py", original)
	for _, capability := range []string{"fs.edit.preview", "fs.edit"} {
		result := invokeCoding(t, Provider{}, capability, editArgs(path, replace("日", "A")), nil)
		if !result.Error || !strings.Contains(result.Contents[0].Text, "use write to rewrite the whole file") {
			t.Errorf("%s: %+v", capability, result.Contents)
		}
		if got := mustRead(t, path); !bytes.Equal(got, original) {
			t.Fatalf("%s modified original bytes: %x", capability, got)
		}
	}
}
