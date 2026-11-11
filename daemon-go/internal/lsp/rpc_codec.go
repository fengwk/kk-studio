package lsp

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"

	"github.com/sourcegraph/jsonrpc2"
)

const maxBannerBytes = 8192

// lspCodec adapts LSP Content-Length framing to jsonrpc2 object transport.
type lspCodec struct{ first bool }

func newLSPCodec() *lspCodec { return &lspCodec{first: true} }
func (c *lspCodec) ReadObject(r *bufio.Reader, value any) error {
	var header []byte
	for {
		line, err := r.ReadBytes('\n')
		if err != nil {
			return err
		}
		if c.first {
			lower := bytes.ToLower(line)
			if !bytes.HasPrefix(bytes.TrimSpace(lower), []byte("content-length:")) {
				if len(header)+len(line) > maxBannerBytes {
					return errors.New("LSP server produced more than 8192 bytes of non-protocol output before the first message")
				}
				continue
			}
			c.first = false
		}
		header = append(header, line...)
		if bytes.Equal(bytes.TrimSpace(line), nil) {
			break
		}
		if len(header) > 64*1024 {
			return errors.New("invalid LSP message header")
		}
		if len(line) == 2 && line[0] == '\r' && line[1] == '\n' {
			break
		}
		if len(line) == 1 && line[0] == '\n' {
			break
		}
	}
	length := -1
	for _, line := range bytes.Split(header, []byte("\n")) {
		key, val, ok := bytes.Cut(line, []byte(":"))
		if ok && strings.EqualFold(strings.TrimSpace(string(key)), "Content-Length") {
			n, err := strconv.Atoi(strings.TrimSpace(string(val)))
			if err != nil || n < 0 {
				return errors.New("invalid LSP Content-Length")
			}
			length = n
		}
	}
	if length < 0 {
		return errors.New("missing LSP Content-Length")
	}
	body := make([]byte, length)
	if _, err := io.ReadFull(r, body); err != nil {
		return err
	}
	return json.Unmarshal(body, value)
}
func (c *lspCodec) WriteObject(w io.Writer, value any) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	if _, err = fmt.Fprintf(w, "Content-Length: %d\r\n\r\n", len(data)); err != nil {
		return err
	}
	_, err = w.Write(data)
	return err
}

var _ jsonrpc2.ObjectCodec = (*lspCodec)(nil)
