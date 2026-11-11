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
	if c.first {
		const marker = "content-length:"
		window := make([]byte, 0, len(marker))
		bannerBytes := 0
		for {
			b, err := r.ReadByte()
			if err != nil {
				if bannerBytes+len(window) > maxBannerBytes {
					return errors.New("LSP server produced more than 8192 bytes of non-protocol output before the first message")
				}
				return err
			}
			window = append(window, b)
			if len(window) > len(marker) {
				window = window[1:]
				bannerBytes++
				if bannerBytes > maxBannerBytes {
					return errors.New("LSP server produced more than 8192 bytes of non-protocol output before the first message")
				}
			}
			if strings.EqualFold(string(window), marker) {
				header = append(header, window...)
				break
			}
		}
		c.first = false
	}
	for {
		b, err := r.ReadByte()
		if err != nil {
			return err
		}
		header = append(header, b)
		if len(header) > 64*1024 {
			return errors.New("invalid LSP message header")
		}
		if bytes.HasSuffix(header, []byte("\r\n\r\n")) || bytes.HasSuffix(header, []byte("\n\n")) {
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
