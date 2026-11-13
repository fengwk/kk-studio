package textcodec

import (
	"path/filepath"
	"strings"

	"golang.org/x/text/encoding"
	"golang.org/x/text/encoding/htmlindex"
	"golang.org/x/text/encoding/ianaindex"
)

// format selects the label index a declaration is resolved with.
type format int

const (
	// formatWHATWG resolves labels with the WHATWG encoding index used by HTML and CSS.
	formatWHATWG format = iota
	// formatIANA resolves labels with the IANA index, falling back to WHATWG.
	formatIANA
)

// resolved is a declaration resolved to an encoding implementation. xtext is nil for UTF-8
// and US-ASCII, which the codec handles itself so that it stays strict.
type resolved struct {
	enc   Encoding
	xtext encoding.Encoding
}

// formatOf reports which label index applies to a declaration found in path.
func formatOf(path, where string) format {
	switch {
	case where == declarationHTML, where == declarationCSS:
		return formatWHATWG
	case strings.EqualFold(filepath.Ext(path), ".html"), strings.EqualFold(filepath.Ext(path), ".htm"):
		return formatWHATWG
	}
	return formatIANA
}

// resolveLabel maps a declared label to an encoding. Python and Ruby labels go through
// CPython's get_normal_name first, and ASCII is always strict.
func resolveLabel(label, where string, form format) (resolved, error) {
	trimmed := strings.TrimSpace(label)
	if trimmed == "" {
		return resolved{}, &UnsupportedLabelError{Label: label, Declaration: where}
	}
	normalized := strings.ToLower(trimmed)
	if form == formatIANA {
		normalized = normalizeCPythonName(normalized)
	}
	switch normalized {
	case "utf-8", "utf8":
		return resolved{enc: declaredEncoding("UTF-8", where)}, nil
	case "ascii", "us-ascii":
		return resolved{enc: declaredEncoding("US-ASCII", where)}, nil
	}
	if isUTF16Or32Label(strings.ToUpper(normalized)) {
		return resolved{}, &DeclarationConflictError{Declared: declaredEncoding(unicodeFamilyName(normalized), where)}
	}
	xtext, err := lookupEncoding(normalized, form)
	if err != nil || xtext == nil {
		return resolved{}, &UnsupportedLabelError{Label: trimmed, Declaration: where}
	}
	name, err := displayNameOf(xtext)
	if err != nil {
		return resolved{}, &UnsupportedLabelError{Label: trimmed, Declaration: where}
	}
	if isUTF16Or32Label(name) {
		return resolved{}, &DeclarationConflictError{Declared: declaredEncoding(name, where)}
	}
	return resolved{enc: declaredEncoding(name, where), xtext: xtext}, nil
}

// lookupEncoding resolves an already trimmed, lower-case label in the index of its format.
func lookupEncoding(label string, form format) (encoding.Encoding, error) {
	if form == formatWHATWG {
		return htmlindex.Get(label)
	}
	if xtext, err := ianaindex.IANA.Encoding(label); err == nil && xtext != nil {
		return xtext, nil
	}
	return htmlindex.Get(label)
}

// encodingByName resolves a display name for Encode and for callers that keep only the name.
// ISO-8859-1 resolves through IANA, so a declared Latin-1 file keeps its own characters
// instead of being widened to windows-1252.
func encodingByName(name string) (encoding.Encoding, error) {
	return lookupEncoding(strings.ToLower(strings.TrimSpace(name)), formatIANA)
}

// displayNameOf reports the display name of an encoding: the MIME name, else the IANA name.
func displayNameOf(xtext encoding.Encoding) (string, error) {
	if name, err := ianaindex.MIME.Name(xtext); err == nil {
		return name, nil
	}
	return ianaindex.IANA.Name(xtext)
}

// normalizeCPythonName applies CPython's get_normal_name to an already lower-case label.
func normalizeCPythonName(label string) string {
	shortened := strings.ReplaceAll(label, "_", "-")
	if len(shortened) > 12 {
		shortened = shortened[:12]
	}
	switch {
	case shortened == "utf-8" || strings.HasPrefix(shortened, "utf-8-"):
		return "utf-8"
	case shortened == "latin-1", shortened == "iso-8859-1", shortened == "iso-latin-1",
		strings.HasPrefix(shortened, "latin-1-"), strings.HasPrefix(shortened, "iso-8859-1-"),
		strings.HasPrefix(shortened, "iso-latin-1-"):
		return "iso-8859-1"
	}
	return label
}

// unicodeFamilyName returns the canonical name of a UTF-16 or UTF-32 label.
func unicodeFamilyName(label string) string {
	switch label {
	case "utf-16", "utf16":
		return "UTF-16"
	case "utf-16le", "utf16le":
		return "UTF-16LE"
	case "utf-16be", "utf16be":
		return "UTF-16BE"
	case "utf-32", "utf32":
		return "UTF-32"
	case "utf-32le", "utf32le":
		return "UTF-32LE"
	case "utf-32be", "utf32be":
		return "UTF-32BE"
	}
	return label
}

// declaredEncoding builds the Encoding reported for a declaration.
func declaredEncoding(name, where string) Encoding {
	return Encoding{Name: name, Source: SourceDeclaration, Declaration: where}
}

// strictASCII reports whether name is the strict ASCII encoding.
func strictASCII(name string) bool {
	return name == "US-ASCII"
}
