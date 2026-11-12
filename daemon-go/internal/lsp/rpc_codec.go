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

func prefixSuffixLength(suffix []byte, marker string) int {
	maximum := len(suffix)
	if maximum >= len(marker) {
		maximum = len(marker) - 1
	}
	for length := maximum; length > 0; length-- {
		if strings.EqualFold(string(suffix[len(suffix)-length:]), marker[:length]) {
			return length
		}
	}
	return 0
}

type recordingCodec struct {
	codec       jsonrpc2.ObjectCodec
	onReadError func(error)
}

func (c *recordingCodec) ReadObject(reader *bufio.Reader, value any) error {
	err := c.codec.ReadObject(reader, value)
	if err != nil && c.onReadError != nil {
		c.onReadError(err)
	}
	return err
}

func (c *recordingCodec) WriteObject(writer io.Writer, value any) error {
	return c.codec.WriteObject(writer, value)
}

func (c *lspCodec) ReadObject(r *bufio.Reader, value any) error {
	var header []byte
	if c.first {
		const marker = "content-length:"
		window := make([]byte, 0, len(marker))
		readBytes := 0
		for {
			b, err := r.ReadByte()
			if err != nil {
				return err
			}
			readBytes++
			window = append(window, b)
			if len(window) > len(marker) {
				window = window[1:]
			}
			if strings.EqualFold(string(window), marker) {
				header = append(header, window...)
				break
			}
			if readBytes-prefixSuffixLength(window, marker) > maxBannerBytes {
				return errors.New("LSP server produced more than 8192 bytes of non-protocol output before the first message")
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
