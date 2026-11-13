package textcodec

import (
	"bufio"
	"errors"
	"io"
	"unicode/utf16"
	"unicode/utf8"
)

// Reader decodes a text document incrementally so a caller such as read can stop once its
// window is full; it validates only the bytes it has to read.
type Reader struct {
	enc    Encoding
	source runeSource
}

// NewReader probes the document's byte order mark and declaration and returns a decoder for
// it. A declared legacy encoding is decoded as a whole file before any text is served.
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
			return &Reader{enc: enc, source: &utf8Source{reader: buffered, declared: &enc, offset: len(bomUTF8)}}, nil
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
		return &Reader{enc: Encoding{Name: "UTF-8", Source: SourceUTF8}, source: &utf8Source{reader: buffered, unknown: path}}, nil
	}
	declared, err := resolveLabel(label, where, formatOf(path, where))
	if err != nil {
		return nil, err
	}
	if declared.xtext != nil {
		all, readErr := io.ReadAll(io.LimitReader(buffered, maxDeclaredSize+1))
		if readErr != nil {
			return nil, readErr
		}
		text, enc, decodeErr := decodeDeclared(all, declared)
		if decodeErr != nil {
			return nil, decodeErr
		}
		return &Reader{enc: enc, source: &memorySource{text: text}}, nil
	}
	return &Reader{enc: declared.enc, source: &utf8Source{reader: buffered, declared: &declared.enc, ascii: strictASCII(declared.enc.Name)}}, nil
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

// runeSource is the incremental decoder behind Reader.
type runeSource interface {
	readRune() (rune, error)
}

// utf8Source strictly decodes a UTF-8 or ASCII document.
type utf8Source struct {
	reader   *bufio.Reader
	declared *Encoding
	unknown  string
	ascii    bool
	offset   int
}

func (s *utf8Source) readRune() (rune, error) {
	value, size, err := s.reader.ReadRune()
	if err != nil {
		return 0, err
	}
	if value == utf8.RuneError && size == 1 || s.ascii && value > utf8.RuneSelf {
		if s.declared != nil {
			return 0, &UndecodableError{Encoding: *s.declared, Offset: s.offset}
		}
		return 0, &UnknownEncodingError{Path: s.unknown}
	}
	s.offset += size
	return value, nil
}

// wideSource strictly decodes a BOM-marked UTF-16 or UTF-32 document.
type wideSource struct {
	reader    *bufio.Reader
	enc       Encoding
	width     int
	bigEndian bool
	offset    int
}

func newWideSource(reader *bufio.Reader, enc Encoding) *wideSource {
	return &wideSource{
		reader:    reader,
		enc:       enc,
		width:     wideWidth(enc.Name),
		bigEndian: enc.Name == "UTF-16BE" || enc.Name == "UTF-32BE",
		offset:    len(bomPrefix(enc)),
	}
}

func (s *wideSource) readRune() (rune, error) {
	unit, err := s.readUnit()
	if err != nil {
		return 0, err
	}
	if s.width == 4 {
		if unit > utf8.MaxRune || unit >= 0xd800 && unit <= 0xdfff {
			return 0, &UndecodableError{Encoding: s.enc, Offset: s.offset - s.width}
		}
		return rune(unit), nil
	}
	if unit >= 0xdc00 && unit <= 0xdfff {
		return 0, &UndecodableError{Encoding: s.enc, Offset: s.offset - s.width}
	}
	if unit < 0xd800 || unit > 0xdbff {
		return rune(unit), nil
	}
	low, err := s.readUnit()
	if err != nil {
		if errors.Is(err, io.EOF) {
			return 0, &UndecodableError{Encoding: s.enc, Offset: s.offset - s.width}
		}
		return 0, err
	}
	if low < 0xdc00 || low > 0xdfff {
		return 0, &UndecodableError{Encoding: s.enc, Offset: s.offset - 2*s.width}
	}
	return utf16.DecodeRune(rune(unit), rune(low)), nil
}

// readUnit reads one unit of the document.
func (s *wideSource) readUnit() (uint32, error) {
	var unit [4]byte
	count, err := io.ReadFull(s.reader, unit[:s.width])
	if err != nil {
		if count == 0 && errors.Is(err, io.EOF) {
			return 0, io.EOF
		}
		return 0, &UndecodableError{Encoding: s.enc, Offset: s.offset}
	}
	s.offset += s.width
	return readUnit(unit[:s.width], s.width, s.bigEndian), nil
}

// memorySource serves runes from a whole-file decode.
type memorySource struct {
	text   []byte
	offset int
}

func (s *memorySource) readRune() (rune, error) {
	if s.offset >= len(s.text) {
		return 0, io.EOF
	}
	value, size := utf8.DecodeRune(s.text[s.offset:])
	s.offset += size
	return value, nil
}
