package coding

import (
	"errors"
	"fmt"
	"path/filepath"
	"strings"

	"github.com/fengwk/kk-studio/daemon/internal/textcodec"
)

// bomRequest is the character that asks write to store a byte order mark. It is a request, never
// content.
const bomRequest = "\uFEFF"

// codecContext selects the wording a text codec failure is reported with.
type codecContext int

const (
	codecRead codecContext = iota
	codecSearch
	codecWrite
	codecEdit
)

// codecError converts a text codec failure into a tool error with the CODEC code. Every codec
// failure is decided before the tool touches the file, so the result reports that nothing ran.
func codecError(path string, err error, context codecContext) error {
	return &toolError{kind: toolCodecRejected, code: "CODEC", message: codecErrorMessage(path, err, context)}
}

// isCodecFailure reports whether err is one of the typed failures of the text codec.
func isCodecFailure(err error) bool {
	return textcodec.IsCodecError(err)
}

// codecErrorMessage renders a text codec failure so that it names the file, and for declared
// files the encoding and where it was declared.
func codecErrorMessage(path string, err error, context codecContext) string {
	if path == "" {
		return decodeTextMessage(err)
	}
	shown := filepath.ToSlash(path)
	var unrepresentable *textcodec.UnrepresentableError
	switch {
	case errors.Is(err, textcodec.ErrBinary):
		return fmt.Sprintf("%s is a binary file (it contains NUL bytes); %s", shown, binarySupport(context))
	case errors.As(err, &unrepresentable):
		return unrepresentable.Error()
	}
	return fmt.Sprintf("%s: %s", shown, err)
}

// binarySupport explains what each tool accepts in place of binary content.
func binarySupport(context codecContext) string {
	switch context {
	case codecSearch:
		return "grep searches text files and directories."
	case codecWrite:
		return "write only rewrites text files."
	case codecEdit:
		return "edit only rewrites text files."
	}
	return "read supports text files, directories and supported media."
}

// decodeTextMessage renders a codec failure for a document whose path is not known.
func decodeTextMessage(err error) string {
	if errors.Is(err, textcodec.ErrBinary) {
		return "file appears to be binary"
	}
	return err.Error()
}

// isUnicodeEncoding reports whether name needs no declaration to stay recognizable.
func isUnicodeEncoding(name string) bool {
	switch name {
	case "UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE", "US-ASCII":
		return true
	}
	return false
}

// writtenContent is the text a write stores and the bytes it commits.
type writtenContent struct {
	text  string
	bytes []byte
}

// encodeWriteContent strips a byte order mark request from content, decides the encoding the write
// uses and encodes the text. existing is the encoding of the current file, or nil when the file
// does not exist yet.
func encodeWriteContent(path string, existing *textcodec.Encoding, content string) (writtenContent, error) {
	text, request := splitBOMRequest(content)
	target, err := writeEncoding(path, existing, request, text)
	if err != nil {
		return writtenContent{}, err
	}
	encoded, err := textcodec.Encode(text, target)
	if err != nil {
		return writtenContent{}, codecError(path, err, codecWrite)
	}
	return writtenContent{text: text, bytes: encoded}, nil
}

// splitBOMRequest reports the content without a leading U+FEFF and whether the content asked for a
// byte order mark.
func splitBOMRequest(content string) (string, bool) {
	text := strings.TrimPrefix(content, bomRequest)
	return text, len(text) != len(content)
}

// writeEncoding reports the encoding a rewritten file must keep or adopt.
func writeEncoding(path string, existing *textcodec.Encoding, request bool, text string) (textcodec.Encoding, error) {
	declared, hasDeclaration, err := textcodec.Declared(path, []byte(text))
	if err != nil {
		return textcodec.Encoding{}, codecError(path, err, codecWrite)
	}
	shown := filepath.ToSlash(path)
	if existing != nil && existing.BOM {
		if hasDeclaration && !bomMatchesDeclaration(existing.Name, declared.Name) {
			return textcodec.Encoding{}, failCodec(fmt.Sprintf("%s starts with a %s byte order mark, but the new content declares %s.", shown, existing.Name, declared.Name))
		}
		return *existing, nil
	}
	if hasDeclaration {
		if order, ok := wideByteOrder(declared.Name); ok {
			if !request {
				return textcodec.Encoding{}, failCodec(fmt.Sprintf("%s: the new content declares %s, but that encoding needs a byte order mark. Start the content with U+FEFF to write it with a byte order mark.", shown, declared.Name))
			}
			return textcodec.Encoding{Name: order, BOM: true, Source: textcodec.SourceBOM}, nil
		}
		if declared.Name != "UTF-8" {
			if request {
				return textcodec.Encoding{}, failCodec(fmt.Sprintf("%s: the new content declares %s, which has no byte order mark; remove the leading U+FEFF to write it.", shown, declared.Name))
			}
			return declared, nil
		}
		if request {
			return textcodec.Encoding{Name: "UTF-8", BOM: true, Source: textcodec.SourceBOM}, nil
		}
		return declared, nil
	}
	if existing != nil && existing.Source == textcodec.SourceDeclaration && !isUnicodeEncoding(existing.Name) {
		return textcodec.Encoding{}, failCodec(fmt.Sprintf("The new content removes the %s declaration of %s. Keep the declaration, or declare the new encoding explicitly to convert the file.", existing.Name, shown))
	}
	if request {
		return textcodec.Encoding{Name: "UTF-8", BOM: true, Source: textcodec.SourceBOM}, nil
	}
	return textcodec.Encoding{Name: "UTF-8", Source: textcodec.SourceUTF8}, nil
}

// wideByteOrder reports the concrete byte order a UTF-16 or UTF-32 declaration selects; the generic
// labels select little endian.
func wideByteOrder(name string) (string, bool) {
	switch name {
	case "UTF-16", "UTF-16LE":
		return "UTF-16LE", true
	case "UTF-16BE":
		return "UTF-16BE", true
	case "UTF-32", "UTF-32LE":
		return "UTF-32LE", true
	case "UTF-32BE":
		return "UTF-32BE", true
	}
	return "", false
}

// encodeEditContent encodes the result of an edit, which must keep the encoding of the file it
// rewrites: a byte order mark stays authoritative, and otherwise the declaration of the result
// has to resolve to the original encoding.
func encodeEditContent(path string, existing textcodec.Encoding, content string) ([]byte, error) {
	// A leading U+FEFF in a file without a byte order mark changes how the file is read: in UTF-8 it
	// becomes the byte order mark and vanishes from the text, and in a declared encoding its bytes
	// precede the declaration. Files with a BOM keep it as content after their BOM.
	if !existing.BOM && strings.HasPrefix(content, bomRequest) {
		return nil, failCodec(fmt.Sprintf("A leading U+FEFF cannot be written to %s without changing how the file is read: it would act as a byte order mark or precede the encoding declaration. Remove the leading U+FEFF; edit keeps the file's encoding.", filepath.ToSlash(path)))
	}
	declared, hasDeclaration, err := textcodec.Declared(path, []byte(content))
	if err != nil {
		return nil, codecError(path, err, codecEdit)
	}
	if err = checkEditEncoding(path, existing, declared, hasDeclaration); err != nil {
		return nil, err
	}
	encoded, err := textcodec.Encode(content, existing)
	if err != nil {
		return nil, codecError(path, err, codecEdit)
	}
	return encoded, nil
}

// checkEditEncoding refuses an edit that would change the effective encoding of a file.
func checkEditEncoding(path string, existing, declared textcodec.Encoding, hasDeclaration bool) error {
	if existing.BOM {
		if hasDeclaration && !bomMatchesDeclaration(existing.Name, declared.Name) {
			return failCodec(changeEncodingMessage(path, existing.Name, declared.Name))
		}
		return nil
	}
	if !hasDeclaration {
		if existing.Source == textcodec.SourceDeclaration && !isUnicodeEncoding(existing.Name) {
			return failCodec(changeEncodingMessage(path, existing.Name, "UTF-8"))
		}
		return nil
	}
	if declared.Name != existing.Name {
		message := changeEncodingMessage(path, existing.Name, declared.Name)
		if wideFamily(declared.Name) {
			message += " That declaration needs a byte order mark."
		}
		return failCodec(message)
	}
	return nil
}

// bomMatchesDeclaration reports whether a declared encoding is satisfied by a byte order mark:
// the concrete order matches itself and the family label matches either order.
func bomMatchesDeclaration(bom, declared string) bool {
	if bom == declared {
		return true
	}
	switch bom {
	case "UTF-16LE", "UTF-16BE":
		return declared == "UTF-16"
	case "UTF-32LE", "UTF-32BE":
		return declared == "UTF-32"
	}
	return false
}

// wideFamily reports whether a declaration names the UTF-16 or UTF-32 family, which a file can
// only use with a byte order mark.
func wideFamily(name string) bool {
	switch name {
	case "UTF-16", "UTF-16LE", "UTF-16BE", "UTF-32", "UTF-32LE", "UTF-32BE":
		return true
	}
	return false
}

// changeEncodingMessage reports an edit that would silently re-encode a file.
func changeEncodingMessage(path, from, to string) string {
	return fmt.Sprintf("This change would change the encoding of %s from %s to %s. Use write with the complete content to convert the file.", filepath.ToSlash(path), from, to)
}
