package coding

import (
	"errors"
	"fmt"
	"path/filepath"

	"github.com/fengwk/kk-studio/daemon/internal/textcodec"
)

// codecContext selects the wording a text codec failure is reported with.
type codecContext int

const (
	codecRead codecContext = iota
	codecSearch
	codecWrite
	codecEdit
)

// codecError converts a text codec failure into a tool error with the CODEC code.
func codecError(path string, err error, context codecContext) error {
	return failRun(codecErrorMessage(path, err, context), "CODEC")
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
	var unknown *textcodec.UnknownEncodingError
	var unrepresentable *textcodec.UnrepresentableError
	switch {
	case errors.Is(err, textcodec.ErrBinary):
		return fmt.Sprintf("%s is a binary file (it contains NUL bytes); %s", shown, binarySupport(context))
	case errors.As(err, &unknown):
		return fmt.Sprintf("%s is not UTF-8 text and declares no encoding, so it was not %s. If you know its encoding, view it with bash, for example iconv -f GBK -t UTF-8 '%s'.%s",
			shown, codecPastTense(context), shown, codecUnknownTail(context))
	case errors.As(err, &unrepresentable):
		return fmt.Sprintf("The new text cannot be encoded in %s: %q (U+%04X) on line %d.",
			unrepresentable.Encoding.Name, string(unrepresentable.Char), unrepresentable.Char, unrepresentable.Line)
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

// codecPastTense reports what did not happen to an undeclared file.
func codecPastTense(context codecContext) string {
	switch context {
	case codecSearch:
		return "searched"
	case codecWrite:
		return "rewritten"
	case codecEdit:
		return "edited"
	}
	return "read"
}

// codecUnknownTail tells the caller how to make the file usable by the tool.
func codecUnknownTail(context codecContext) string {
	switch context {
	case codecSearch:
		return ""
	case codecWrite, codecEdit:
		return " To rewrite it, convert it to UTF-8 or add a standard encoding declaration."
	}
	return " To use read, edit or write on it, convert it to UTF-8 or add a standard encoding declaration."
}

// decodeTextMessage renders a codec failure for a document whose path is not known.
func decodeTextMessage(err error) string {
	var unknown *textcodec.UnknownEncodingError
	switch {
	case errors.Is(err, textcodec.ErrBinary):
		return "file appears to be binary"
	case errors.As(err, &unknown):
		return "file is not UTF-8 text and declares no encoding"
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

// encodeWriteContent decides the encoding content is written with and encodes it. existing is
// the encoding of the current file, or nil when the file does not exist yet.
func encodeWriteContent(path string, existing *textcodec.Encoding, content string) ([]byte, error) {
	declared, hasDeclaration, err := textcodec.Declared(path, []byte(content))
	if err != nil {
		return nil, codecError(path, err, codecWrite)
	}
	target, err := writeEncoding(path, existing, declared, hasDeclaration)
	if err != nil {
		return nil, err
	}
	encoded, err := textcodec.Encode(content, target)
	if err != nil {
		return nil, codecError(path, err, codecWrite)
	}
	return encoded, nil
}

// writeEncoding reports the encoding a rewritten file must keep or adopt.
func writeEncoding(path string, existing *textcodec.Encoding, declared textcodec.Encoding, hasDeclaration bool) (textcodec.Encoding, error) {
	shown := filepath.ToSlash(path)
	if existing != nil && existing.BOM {
		if hasDeclaration && !bomMatchesDeclaration(existing.Name, declared.Name) {
			return textcodec.Encoding{}, failRun(fmt.Sprintf("%s starts with a %s byte order mark, but the new content declares %s.", shown, existing.Name, declared.Name), "CODEC")
		}
		return *existing, nil
	}
	if hasDeclaration {
		if wideFamily(declared.Name) {
			return textcodec.Encoding{}, failRun(fmt.Sprintf("%s: the new content declares %s, but that encoding needs a byte order mark and the file does not have one.", shown, declared.Name), "CODEC")
		}
		return declared, nil
	}
	if existing != nil && existing.Source == textcodec.SourceDeclaration && !isUnicodeEncoding(existing.Name) {
		return textcodec.Encoding{}, failRun(fmt.Sprintf("The new content removes the %s declaration of %s. Keep the declaration, or declare the new encoding explicitly to convert the file.", existing.Name, shown), "CODEC")
	}
	return textcodec.Encoding{Name: "UTF-8", Source: textcodec.SourceUTF8}, nil
}

// encodeEditContent encodes the result of an edit, which must keep the encoding of the file it
// rewrites: a byte order mark stays authoritative, and otherwise the declaration of the result
// has to resolve to the original encoding.
func encodeEditContent(path string, existing textcodec.Encoding, content string) ([]byte, error) {
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
			return failRun(changeEncodingMessage(path, existing.Name, declared.Name), "CODEC")
		}
		return nil
	}
	if !hasDeclaration {
		if existing.Source == textcodec.SourceDeclaration && !isUnicodeEncoding(existing.Name) {
			return failRun(changeEncodingMessage(path, existing.Name, "UTF-8"), "CODEC")
		}
		return nil
	}
	if declared.Name != existing.Name {
		message := changeEncodingMessage(path, existing.Name, declared.Name)
		if wideFamily(declared.Name) {
			message += " That declaration needs a byte order mark."
		}
		return failRun(message, "CODEC")
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
