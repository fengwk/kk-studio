package lsp

import "testing"

// LSP-T-079 verifies code-point input conversion to the negotiated LSP encoding and CRLF handling.
func TestPosition_EncodingsAndBounds(t *testing.T) {
	for _, test := range []struct {
		encoding string
		want     int
	}{{"utf-8", 5}, {"utf-16", 3}, {"utf-32", 2}} {
		pos, err := documentPosition("a😀b\r\nnext", 1, 2, test.encoding)
		if err != nil {
			t.Fatal(err)
		}
		if pos["line"] != 0 || pos["character"] != test.want {
			t.Errorf("%s position = %v, want character %d", test.encoding, pos, test.want)
		}
	}
	_, err := documentPosition("a\nb", 3, 0, "utf-16")
	if err == nil || err.Error() != "line 3 is beyond the end of the synchronized document" {
		t.Fatalf("line error = %v", err)
	}
	_, err = documentPosition("a\nb", 2, 2, "utf-16")
	if err == nil || err.Error() != "character 2 is beyond line 2 of the synchronized document (1 code points)" {
		t.Fatalf("character error = %v", err)
	}
}
