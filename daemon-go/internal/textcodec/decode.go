package textcodec

import (
	"bufio"
	"bytes"
	"errors"
	"io"
	"unicode/utf16"
	"unicode/utf8"

	"golang.org/x/text/transform"
)

// Decode returns the UTF-8 text of a whole file, the encoding it was decoded with, and the number
// of ill-formed sequences it replaced with U+FFFD. When data is already the text of its encoding it
// is returned without copying.
func Decode(path string, data []byte) ([]byte, Encoding, int, error) {
	if enc, ok := bomEncoding(data); ok {
		body := data[len(bomPrefix(enc)):]
		if enc.Name == "UTF-8" {
			if checkBinaryWindow(data, len(bomUTF8)) {
				return nil, enc, 0, ErrBinary
			}
			return decodeText(body, enc, false)
		}
		if binaryUnits(body, wideWidth(enc.Name)) {
			return nil, enc, 0, ErrBinary
		}
		return decodeWide(enc, body)
	}
	if checkBinaryWindow(data, 0) {
		return nil, Encoding{}, 0, ErrBinary
	}
	label, where := findDeclaration(path, data)
	if label == "" {
		return decodeText(data, Encoding{Name: "UTF-8", Source: SourceUTF8}, false)
	}
	declared, err := resolveLabel(label, where, formatOf(path, where))
	if err != nil {
		return nil, Encoding{}, 0, err
	}
	if declared.bomRequired {
		return nil, Encoding{}, 0, &DeclarationConflictError{Declared: declared.enc, Label: declared.label}
	}
	return decodeDeclared(declared, data)
}

// decodeText decodes UTF-8, or US-ASCII when ascii is set, and replaces every ill-formed sequence
// with U+FFFD. Valid UTF-8 is returned without copying.
func decodeText(data []byte, enc Encoding, ascii bool) ([]byte, Encoding, int, error) {
	if !ascii && utf8.Valid(data) {
		return data, enc, 0, nil
	}
	text, replacements, err := readAll(newUTF8Source(bufio.NewReader(bytes.NewReader(data)), ascii))
	if err != nil {
		return nil, enc, 0, err
	}
	return text, enc, replacements, nil
}

// decodeWide decodes a UTF-16 or UTF-32 document whose byte order the caller already stripped.
func decodeWide(enc Encoding, body []byte) ([]byte, Encoding, int, error) {
	text, replacements, err := readAll(newWideSource(bufio.NewReader(bytes.NewReader(body)), enc))
	if err != nil {
		return nil, enc, 0, err
	}
	return text, enc, replacements, nil
}

// decodeDeclared decodes data with the encoding a declaration named.
func decodeDeclared(declared resolved, data []byte) ([]byte, Encoding, int, error) {
	if declared.xtext == nil {
		return decodeText(data, declared.enc, strictASCII(declared.enc.Name))
	}
	if len(data) > maxDeclaredSize {
		return nil, Encoding{}, 0, &TooLargeError{Encoding: declared.enc, Size: int64(len(data))}
	}
	// A legacy decoder substitutes U+FFFD for bytes it cannot decode; the replacements are the
	// U+FFFD runes of its output.
	text, _, err := transform.Bytes(declared.xtext.NewDecoder(), data)
	if err != nil {
		return nil, Encoding{}, 0, err
	}
	return text, declared.enc, countReplacements(text), nil
}

// readAll decodes a whole document and reports how many sequences the source replaced.
func readAll(source runeSource) ([]byte, int, error) {
	var text []byte
	for {
		value, err := source.readRune()
		if err != nil {
			if errors.Is(err, io.EOF) {
				return text, source.replacements(), nil
			}
			return nil, 0, err
		}
		text = utf8.AppendRune(text, value)
	}
}

// runeSource is the incremental decoder behind Reader.
type runeSource interface {
	readRune() (rune, error)
	// replacements reports how many ill-formed sequences the source decoded to U+FFFD.
	replacements() int
}

// utf8Source decodes UTF-8, or US-ASCII, replacing every ill-formed sequence with U+FFFD. In an
// ASCII document every byte above ASCII is one replacement, as the declaration promises.
type utf8Source struct {
	reader   *bufio.Reader
	ascii    bool
	replaced int
}

func newUTF8Source(reader *bufio.Reader, ascii bool) *utf8Source {
	return &utf8Source{reader: reader, ascii: ascii}
}

func (s *utf8Source) readRune() (rune, error) {
	first, err := s.reader.ReadByte()
	if err != nil {
		return 0, err
	}
	if first < utf8.RuneSelf {
		return rune(first), nil
	}
	if s.ascii {
		return s.replacement(), nil
	}
	length, low, high, ok := utf8Lead(first)
	if !ok {
		return s.replacement(), nil
	}
	var sequence [utf8.UTFMax]byte
	sequence[0] = first
	for index := 1; index < length; index++ {
		next, err := s.reader.ReadByte()
		if err != nil {
			if errors.Is(err, io.EOF) {
				return s.replacement(), nil
			}
			return 0, err
		}
		if index == 1 && (next < low || next > high) || index > 1 && (next < 0x80 || next > 0xbf) {
			// The maximal subpart ends before this byte; the byte starts the next sequence.
			_ = s.reader.UnreadByte()
			return s.replacement(), nil
		}
		sequence[index] = next
	}
	value, size := utf8.DecodeRune(sequence[:length])
	if size != length {
		return s.replacement(), nil
	}
	return value, nil
}

func (s *utf8Source) replacement() rune {
	s.replaced++
	return utf8.RuneError
}

func (s *utf8Source) replacements() int {
	return s.replaced
}

// utf8Lead reports the length of the UTF-8 sequence a leading byte starts and the range its second
// byte must be in.
func utf8Lead(first byte) (length int, low, high byte, ok bool) {
	switch {
	case first < 0xc2:
		// Continuation bytes and the overlong two-byte leads C0 and C1 are ill-formed on their own.
		return 0, 0, 0, false
	case first <= 0xdf:
		return 2, 0x80, 0xbf, true
	case first == 0xe0:
		return 3, 0xa0, 0xbf, true
	case first <= 0xec:
		return 3, 0x80, 0xbf, true
	case first == 0xed:
		return 3, 0x80, 0x9f, true
	case first <= 0xef:
		return 3, 0x80, 0xbf, true
	case first == 0xf0:
		return 4, 0x90, 0xbf, true
	case first <= 0xf3:
		return 4, 0x80, 0xbf, true
	case first == 0xf4:
		return 4, 0x80, 0x8f, true
	}
	return 0, 0, 0, false
}

// wideSource decodes UTF-16 or UTF-32, replacing every unpaired surrogate, out-of-range value and
// truncated final unit with U+FFFD.
type wideSource struct {
	reader    *bufio.Reader
	width     int
	bigEndian bool
	next      uint32
	hasNext   bool
	short     bool
	replaced  int
}

func newWideSource(reader *bufio.Reader, enc Encoding) *wideSource {
	return &wideSource{
		reader:    reader,
		width:     wideWidth(enc.Name),
		bigEndian: enc.Name == "UTF-16BE" || enc.Name == "UTF-32BE",
	}
}

func (s *wideSource) readRune() (rune, error) {
	if s.short {
		// The unit after an unpaired high surrogate: the surrogate was one replacement, this
		// truncated final unit is another.
		s.short = false
		return s.replacement(), nil
	}
	unit, err := s.readUnit()
	if errors.Is(err, errShortUnit) {
		return s.replacement(), nil
	}
	if err != nil {
		return 0, err
	}
	if s.width == 4 {
		if unit > utf8.MaxRune || unit >= 0xd800 && unit <= 0xdfff {
			return s.replacement(), nil
		}
		return rune(unit), nil
	}
	if unit >= 0xdc00 && unit <= 0xdfff {
		return s.replacement(), nil
	}
	if unit < 0xd800 || unit > 0xdbff {
		return rune(unit), nil
	}
	low, err := s.readUnit()
	switch {
	case errors.Is(err, io.EOF):
		return s.replacement(), nil
	case errors.Is(err, errShortUnit):
		s.short = true
		return s.replacement(), nil
	case err != nil:
		return 0, err
	}
	if low < 0xdc00 || low > 0xdfff {
		// An unpaired high surrogate does not swallow the unit that follows it.
		s.next, s.hasNext = low, true
		return s.replacement(), nil
	}
	return utf16.DecodeRune(rune(unit), rune(low)), nil
}

// errShortUnit reports a final unit truncated by the end of the document.
var errShortUnit = errors.New("truncated final unit")

// readUnit reads one unit of the document.
func (s *wideSource) readUnit() (uint32, error) {
	if s.hasNext {
		s.hasNext = false
		return s.next, nil
	}
	var unit [4]byte
	count, err := io.ReadFull(s.reader, unit[:s.width])
	if err != nil {
		if count == 0 && errors.Is(err, io.EOF) {
			return 0, io.EOF
		}
		if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
			return 0, errShortUnit
		}
		return 0, err
	}
	return readUnit(unit[:s.width], s.width, s.bigEndian), nil
}

func (s *wideSource) replacement() rune {
	s.replaced++
	return utf8.RuneError
}

func (s *wideSource) replacements() int {
	return s.replaced
}

// readUnit reads one UTF-16 or UTF-32 unit.
func readUnit(body []byte, width int, bigEndian bool) uint32 {
	value := uint32(0)
	if bigEndian {
		for index := 0; index < width; index++ {
			value = value<<8 | uint32(body[index])
		}
		return value
	}
	for index := width - 1; index >= 0; index-- {
		value = value<<8 | uint32(body[index])
	}
	return value
}

// memorySource serves runes of a whole-file decode.
type memorySource struct {
	text     []byte
	offset   int
	replaced int
}

func (s *memorySource) readRune() (rune, error) {
	if s.offset >= len(s.text) {
		return 0, io.EOF
	}
	value, size := utf8.DecodeRune(s.text[s.offset:])
	s.offset += size
	return value, nil
}

func (s *memorySource) replacements() int {
	return s.replaced
}
