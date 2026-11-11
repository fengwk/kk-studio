package lsp

import (
	"bufio"
	"bytes"
	"encoding/json"
	"strings"
	"testing"
)

// LSP-T-080 and LSP-T-081 verify banner tolerance and fragmented Content-Length framing.
func TestLSPCodec_ReadFramedMessages(t *testing.T) {
	body := `{"jsonrpc":"2.0","id":1,"result":{}}`
	wire := "banner output\ncontent-length: " + string(rune('0'+len(body)/10)) + string(rune('0'+len(body)%10)) + "\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n" + body
	codec := newLSPCodec()
	var first json.RawMessage
	if err := codec.ReadObject(bufio.NewReader(strings.NewReader(wire)), &first); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(first, []byte(body)) {
		t.Fatalf("decoded %s", first)
	}
}

// LSP-T-082 verifies that excessive bytes before the first protocol header fail closed.
func TestLSPCodec_BoundedBanner(t *testing.T) {
	codec := newLSPCodec()
	var value any
	// A newline ensures the reader returns the complete banner line to the codec.
	input := strings.Repeat("x", maxBannerBytes+1) + "\n"
	if err := codec.ReadObject(bufio.NewReader(strings.NewReader(input)), &value); err == nil || !strings.Contains(err.Error(), "more than 8192 bytes") {
		t.Fatalf("error = %v", err)
	}
}
