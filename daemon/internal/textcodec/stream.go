package textcodec

import (
	"bufio"
	"io"
	"unicode/utf8"
)

// Reader decodes a text document incrementally so a caller such as read can stop once its window
// is full; it decodes only the bytes it has to read.
type Reader struct {
	enc    Encoding
	source runeSource
}

// NewReader probes the document's byte order mark and declaration and returns a decoder for it. A
// declared legacy encoding is decoded as a whole file before any text is served.
func NewReader(path string, source io.Reader) (*Reader, error) {
	buffered := bufio.NewReaderSize(source, binaryProbeBytes+192)
	prefix, _ := buffered.Peek(binaryProbeBytes)
	if enc, ok := bomEncoding(prefix); ok {
		if _, err := buffered.Discard(len(bomPrefix(enc))); err != nil {
			return nil, err
		}
		if enc.Name == "UTF-8" {
			if checkBinaryWindow(prefix, len(bomUTF8)) {
				return nil, ErrBinary
			}
			return &Reader{enc: enc, source: newUTF8Source(buffered, false)}, nil
		}
		if binaryUnits(prefix[len(bomPrefix(enc)):], wideWidth(enc.Name)) {
			return nil, ErrBinary
		}
		return &Reader{enc: enc, source: newWideSource(buffered, enc)}, nil
	}
	if checkBinaryWindow(prefix, 0) {
		return nil, ErrBinary
	}
	label, where := findDeclaration(path, prefix)
	if label == "" {
		return &Reader{enc: Encoding{Name: "UTF-8", Source: SourceUTF8}, source: newUTF8Source(buffered, false)}, nil
	}
	declared, err := resolveLabel(label, where, formatOf(path, where))
	if err != nil {
		return nil, err
	}
	if declared.bomRequired {
		return nil, &DeclarationConflictError{Declared: declared.enc, Label: declared.label}
	}
	if declared.xtext == nil {
		return &Reader{enc: declared.enc, source: newUTF8Source(buffered, strictASCII(declared.enc.Name))}, nil
	}
	all, readErr := io.ReadAll(io.LimitReader(buffered, maxDeclaredSize+1))
	if readErr != nil {
		return nil, readErr
	}
	text, enc, _, decodeErr := decodeDeclared(declared, all)
	if decodeErr != nil {
		return nil, decodeErr
	}
	return &Reader{enc: enc, source: &memorySource{text: text}}, nil
}

// Encoding reports the encoding NewReader established.
func (r *Reader) Encoding() Encoding {
	return r.enc
}

// ReadRune returns the next character of the document and its width in UTF-8 bytes.
func (r *Reader) ReadRune() (rune, int, error) {
	value, err := r.source.readRune()
	if err != nil {
		return 0, 0, err
	}
	return value, utf8.RuneLen(value), nil
}
