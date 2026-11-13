// Package textcodec is the single text encoding implementation shared by read, grep,
// write, edit and LSP documents. It decides a file's encoding in one order — byte order
// mark, then standard declaration, then valid UTF-8 — and refuses everything it cannot
// reproduce byte for byte instead of guessing.
package textcodec

import (
	"bytes"
	"errors"
	"fmt"
	"unicode/utf8"
)

const (
	// binaryProbeBytes is Git's binary detection window.
	binaryProbeBytes = 8000
	// maxDeclaredSize bounds whole-file decoding of declared non-Unicode encodings.
	maxDeclaredSize = 64 << 20
)

// Source tells how the encoding of a file was established.
type Source int

const (
	// SourceBOM means the encoding was established by a byte order mark.
	SourceBOM Source = iota
	// SourceDeclaration means a standard declaration in the file named the encoding.
	SourceDeclaration
	// SourceUTF8 means the bytes are valid UTF-8 and no declaration applied.
	SourceUTF8
)

// Encoding identifies the encoding of a file and why it was chosen.
type Encoding struct {
	// Name is the display name: "UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE",
	// "US-ASCII", or the MIME/IANA name of a declared encoding.
	Name string
	// BOM reports whether the file starts with a byte order mark for this encoding.
	BOM bool
	// Source tells how the encoding was established.
	Source Source
	// Declaration locates the declaration, for example "XML declaration" or "HTML meta
	// charset"; it is empty when no declaration applied.
	Declaration string
}

// ErrBinary reports data that contains a NUL byte in its first 8000 bytes and is therefore
// not text. For BOM-marked UTF-16 and UTF-32 the decoded characters of those bytes are judged.
var ErrBinary = errors.New("file is binary")

// UnknownEncodingError reports text that is neither BOM-marked nor declared nor valid UTF-8.
type UnknownEncodingError struct {
	Path string
}

func (e *UnknownEncodingError) Error() string {
	return fmt.Sprintf("%s is not UTF-8 text and declares no encoding", e.Path)
}

// UnsupportedLabelError reports a declaration naming a label no known encoding implements.
type UnsupportedLabelError struct {
	Label       string
	Declaration string
}

func (e *UnsupportedLabelError) Error() string {
	return fmt.Sprintf("the %s names an unknown encoding %q", e.Declaration, e.Label)
}

// DeclarationConflictError reports a non-UTF-8 declaration on bytes that are valid UTF-8 and
// contain non-ASCII bytes, or a UTF-16/UTF-32 declaration without a byte order mark.
type DeclarationConflictError struct {
	Declared Encoding
}

func (e *DeclarationConflictError) Error() string {
	return fmt.Sprintf("the %s declares %s, but the content cannot be read as %s: UTF-16 and UTF-32 files must start with a byte order mark, and a non-UTF-8 declaration must be backed by bytes that are not valid UTF-8",
		e.Declared.Declaration, e.Declared.Name, e.Declared.Name)
}

// UndecodableError reports bytes the stated encoding cannot turn back into themselves.
type UndecodableError struct {
	Encoding Encoding
	Offset   int
}

func (e *UndecodableError) Error() string {
	where := ""
	if e.Encoding.Declaration != "" {
		where = " declared by the " + e.Encoding.Declaration
	}
	return fmt.Sprintf("the bytes are not valid %s%s; the first byte that cannot be reproduced is at offset %d",
		e.Encoding.Name, where, e.Offset)
}

// UnrepresentableError reports text the target encoding cannot hold.
type UnrepresentableError struct {
	Encoding Encoding
	Char     rune
	Line     int
}

func (e *UnrepresentableError) Error() string {
	return fmt.Sprintf("%q (U+%04X) on line %d cannot be encoded in %s", string(e.Char), e.Char, e.Line, e.Encoding.Name)
}

// IsCodecError reports whether err is one of the failures this package reports. Errors a
// caller's own reader returns are not codec failures and pass through it unchanged.
func IsCodecError(err error) bool {
	var unknown *UnknownEncodingError
	var unsupported *UnsupportedLabelError
	var conflict *DeclarationConflictError
	var undecodable *UndecodableError
	var unrepresentable *UnrepresentableError
	var tooLarge *TooLargeError
	return errors.Is(err, ErrBinary) || errors.As(err, &unknown) || errors.As(err, &unsupported) ||
		errors.As(err, &conflict) || errors.As(err, &undecodable) || errors.As(err, &unrepresentable) ||
		errors.As(err, &tooLarge)
}

// TooLargeError reports a file above the whole-file decode limit for a non-Unicode encoding.
type TooLargeError struct {
	Encoding Encoding
	Size     int64
}

func (e *TooLargeError) Error() string {
	return fmt.Sprintf("the file is %d bytes, above the %d byte limit for files declared %s",
		e.Size, maxDeclaredSize, e.Encoding.Name)
}

// checkBinaryWindow reports whether the first 8000 bytes after start hold a NUL byte.
func checkBinaryWindow(data []byte, start int) bool {
	end := min(len(data), start+binaryProbeBytes)
	return start < end && bytes.IndexByte(data[start:end], 0) >= 0
}

// binaryUnits reports whether the first 8000 bytes of body hold a NUL character, which in
// UTF-16 and UTF-32 is encoded as a unit of zero.
func binaryUnits(body []byte, width int) bool {
	end := min(len(body)-len(body)%width, binaryProbeBytes)
	for offset := 0; offset < end; offset += width {
		if body[offset] == 0 {
			zero := true
			for next := 1; next < width; next++ {
				if body[offset+next] != 0 {
					zero = false
					break
				}
			}
			if zero {
				return true
			}
		}
	}
	return false
}

// hasHighByte reports whether data holds a byte above ASCII.
func hasHighByte(data []byte) bool {
	for _, b := range data {
		if b >= 0x80 {
			return true
		}
	}
	return false
}

// firstNonASCII reports the offset of the first byte above ASCII, or -1.
func firstNonASCII(data []byte) int {
	for offset, b := range data {
		if b >= 0x80 {
			return offset
		}
	}
	return -1
}

// firstInvalidOffset reports the offset of the first byte that is not part of a valid UTF-8
// sequence, or the length of data when every byte is valid.
func firstInvalidOffset(data []byte) int {
	for offset := 0; offset < len(data); {
		r, size := utf8.DecodeRune(data[offset:])
		if r == utf8.RuneError && size <= 1 {
			return offset
		}
		offset += size
	}
	return len(data)
}

// lineAt reports the one-based line of a byte offset, counting CRLF as one line break.
func lineAt(text string, offset int) int {
	line := 1
	for index := 0; index < offset && index < len(text); index++ {
		switch text[index] {
		case '\n':
			line++
		case '\r':
			if index+1 < len(text) && text[index+1] == '\n' {
				continue
			}
			line++
		}
	}
	return line
}
