package search

import (
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// This test records deliberate RE2/Rust differences rather than disguising them as parity.
func TestRegexGoRE2RustDifferences(t *testing.T) {
	root := t.TempDir()
	put(t, root, "unicode.txt", "é\nÉ\nfoo\n")
	got, err := Grep(root, ".", false, false, "", 10)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got, []string{"unicode.txt:1:é", "unicode.txt:2:É", "unicode.txt:3:foo"}) {
		t.Fatalf("Go dot matches = %v", got)
	}

	// Rust regex with --no-unicode rejects non-ASCII character classes; Go RE2 accepts them.
	got, err = Grep(root, "[é]", false, false, "", 10)
	if err != nil || !reflect.DeepEqual(got, []string{"unicode.txt:1:é"}) {
		t.Fatalf("Go Unicode class = %v, %v", got, err)
	}
	cmd := exec.Command("rg", "--no-config", "--no-unicode", "--crlf", "-n", "[é]", filepath.Join(root, "unicode.txt"))
	if out, err := cmd.CombinedOutput(); err == nil || !strings.Contains(string(out), "Unicode not allowed") {
		t.Fatalf("expected Rust regex rejection, output=%q err=%v", out, err)
	}

	// Go's Unicode simple-folding for (?i) is broader than rg's --no-unicode ASCII folding.
	got, err = Grep(root, "é", true, true, "", 10)
	if err != nil || !reflect.DeepEqual(got, []string{"unicode.txt:1:é", "unicode.txt:2:É"}) {
		t.Fatalf("Go Unicode literal fold = %v, %v", got, err)
	}
	cmd = exec.Command("rg", "--no-config", "--no-unicode", "--crlf", "-n", "--fixed-strings", "--ignore-case", "é", filepath.Join(root, "unicode.txt"))
	out, err := cmd.Output()
	if err != nil || !strings.Contains(string(out), "1:é") || strings.Contains(string(out), "2:É") {
		t.Fatalf("expected rg ASCII-only case fold, output=%q err=%v", out, err)
	}

	// Extended mode exists in Rust regex but not Go RE2.
	if _, err := Grep(root, `(?x)foo`, false, false, "", 10); err == nil {
		t.Fatal("Go RE2 unexpectedly accepted Rust extended mode")
	}
	cmd = exec.Command("rg", "--no-config", "--no-unicode", "--crlf", "-n", `(?x)foo`, filepath.Join(root, "unicode.txt"))
	if out, err := cmd.Output(); err != nil || !strings.Contains(string(out), "foo") {
		t.Fatalf("rg extended mode output=%q err=%v", out, err)
	}
}
