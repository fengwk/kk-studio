package lsp

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"strconv"
	"strings"
	"testing"
)

// LSP-T-080 and LSP-T-081 verify banner tolerance and fragmented Content-Length framing.
func TestLSPCodec_ReadFramedMessages(t *testing.T) {
	body := `{"jsonrpc":"2.0","id":1,"result":{}}`
	wire := "banner output\ncontent-length: " + string(rune('0'+len(body)/10)) + string(rune('0'+len(body)%10)) + "\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n" + body
	codec := newLSPCodec()
	var first json.RawMessage
	if err := codec.ReadObject(bufio.NewReader(oneByteReader{reader: strings.NewReader(wire)}), &first); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(first, []byte(body)) {
		t.Fatalf("decoded %s", first)
	}
}

// LSP-T-080 through LSP-T-082 validate malformed headers, bodies, and write failures.
func TestLSPCodec_RejectsOversizedMessageBeforeReadingBody(t *testing.T) {
	declaredLength := maxMessageBytes + 1
	wire := "Content-Length: " + strconv.Itoa(declaredLength) + "\r\n\r\n"
	var recorded error
	codec := &recordingCodec{codec: newLSPCodec(), onReadError: func(err error) { recorded = err }}
	var value any
	err := codec.ReadObject(bufio.NewReader(strings.NewReader(wire)), &value)
	want := "LSP message of " + strconv.Itoa(declaredLength) + " bytes exceeds the 64 MiB limit"
	if err == nil || err.Error() != want {
		t.Fatalf("oversized message error = %v, want %q", err, want)
	}
	if recorded == nil || recorded.Error() != want {
		t.Fatalf("recorded transport error = %v, want %q", recorded, want)
	}
}

func TestLSPCodec_FramesRejectInvalidInputAndWrites(t *testing.T) {
	for _, tc := range []struct {
		name, wire, want string
	}{{"invalid length", "Content-Length: x\r\n\r\n", "invalid LSP Content-Length"}, {"missing length", "Content-Type: application/json\r\n\r\n{}", "missing LSP Content-Length"}, {"truncated body", "Content-Length: 3\r\n\r\n{}", "unexpected EOF"}, {"invalid JSON", "Content-Length: 1\r\n\r\nx", "invalid character"}} {
		t.Run(tc.name, func(t *testing.T) {
			codec := &lspCodec{first: false}
			var value any
			err := codec.ReadObject(bufio.NewReader(strings.NewReader(tc.wire)), &value)
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("read error = %v, want substring %q", err, tc.want)
			}
		})
	}
	codec := newLSPCodec()
	if err := codec.WriteObject(io.Discard, make(chan int)); err == nil {
		t.Fatal("unsupported JSON value unexpectedly encoded")
	}
	if err := codec.WriteObject(failingWriter{}, map[string]any{}); err == nil || err.Error() != "writer failed" {
		t.Fatalf("header write error = %v", err)
	}
	bodyWriter := &secondWriteFails{}
	if err := codec.WriteObject(bodyWriter, map[string]any{}); err == nil || err.Error() != "writer failed" {
		t.Fatalf("body write error = %v", err)
	}
}

type oneByteReader struct{ reader io.Reader }

func (r oneByteReader) Read(data []byte) (int, error) {
	if len(data) > 1 {
		data = data[:1]
	}
	return r.reader.Read(data)
}

type failingWriter struct{}

func (failingWriter) Write([]byte) (int, error) { return 0, errors.New("writer failed") }

type secondWriteFails struct{ writes int }

func (w *secondWriteFails) Write(data []byte) (int, error) {
	w.writes++
	if w.writes == 2 {
		return 0, errors.New("writer failed")
	}
	return len(data), nil
}

// LSP-T-082 verifies that excessive bytes before the first protocol header fail closed.
func TestLSPCodec_BoundedBanner(t *testing.T) {
	codec := newLSPCodec()
	var value any
	body := `{}`
	valid := strings.Repeat("x", maxBannerBytes) + "Content-Length: 2\r\n\r\n" + body
	if err := codec.ReadObject(bufio.NewReader(strings.NewReader(valid)), &value); err != nil {
		t.Fatalf("exact-limit banner error = %v", err)
	}
	if got, ok := value.(map[string]any); !ok || len(got) != 0 {
		t.Fatalf("exact-limit message = %#v", value)
	}

	codec = newLSPCodec()
	value = nil
	input := strings.Repeat("x", maxBannerBytes+1) + "Content-Length: 2\r\n\r\n" + body
	if err := codec.ReadObject(bufio.NewReader(strings.NewReader(input)), &value); err == nil || err.Error() != "LSP server produced more than 8192 bytes of non-protocol output before the first message" {
		t.Fatalf("error = %v", err)
	}
}
