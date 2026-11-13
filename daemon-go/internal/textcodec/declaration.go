package textcodec

import (
	"bytes"
	"fmt"
	"path/filepath"
	"regexp"
	"strings"

	"golang.org/x/net/html"
)

// maxDeclarationBytes bounds the prescan used by HTML and JSP declarations.
const maxDeclarationBytes = 1024

// Declaration locations reported in Encoding.Declaration.
const (
	declarationXML  = "XML declaration"
	declarationCSS  = "CSS @charset"
	declarationHTML = "HTML meta charset"
	declarationJSP  = "JSP page directive"
	// declarationCoding is completed with the line the coding comment sits on.
	declarationCoding = "coding comment"
)

// codingComment matches the PEP 263 encoding comment shared by Python and Ruby. The pattern
// has no word boundary, so "fileencoding=" and "encoding:" both count.
var codingComment = regexp.MustCompile(`^[ \t\f]*#.*?coding[:=][ \t]*([-_.a-zA-Z0-9]+)`)

// jspPageDirective finds an <%@ page ... %> directive in the prescan window.
var jspPageDirective = regexp.MustCompile(`(?s)<%@\s*page\b(.*?)%>`)

// Declared reports the standard declaration found in decoded text, for write content and
// edit results.
func Declared(path string, text []byte) (Encoding, bool, error) {
	label, where := findDeclaration(path, text)
	if label == "" {
		return Encoding{}, false, nil
	}
	declared, err := resolveLabel(label, where, formatOf(path, where))
	if err != nil {
		return Encoding{}, false, err
	}
	return declared.enc, true, nil
}

// findDeclaration reports the encoding name declared in the standard position for path, or
// "" when the file declares none.
func findDeclaration(path string, data []byte) (label, where string) {
	if label, ok := xmlDeclaration(data); ok {
		return label, declarationXML
	}
	if label, ok := cssCharset(data); ok {
		return label, declarationCSS
	}
	switch strings.ToLower(filepath.Ext(path)) {
	case ".html", ".htm":
		if label, ok := htmlMetaCharset(data); ok {
			return label, declarationHTML
		}
	case ".py", ".pyw", ".pyi":
		if label, line, ok := codingCommentDeclaration(data, pythonCommentAllowed); ok {
			return label, fmt.Sprintf("%s on line %d", declarationCoding, line)
		}
	case ".rb":
		if label, line, ok := codingCommentDeclaration(data, rubyShebangAllowed); ok {
			return label, fmt.Sprintf("%s on line %d", declarationCoding, line)
		}
	case ".jsp", ".jspf", ".tag":
		if label, ok := jspPageEncoding(data); ok {
			return label, declarationJSP
		}
	}
	return "", ""
}

// xmlDeclaration reads the encoding named by an XML declaration that starts at byte 0.
func xmlDeclaration(data []byte) (string, bool) {
	if !bytes.HasPrefix(data, []byte("<?xml")) {
		return "", false
	}
	if len(data) > maxDeclarationBytes {
		data = data[:maxDeclarationBytes]
	}
	end := bytes.Index(data, []byte("?>"))
	if end < 0 {
		return "", false
	}
	attributes := string(data[len("<?xml"):end])
	for {
		name, value, rest, ok := nextAttribute(attributes)
		if !ok {
			return "", false
		}
		attributes = rest
		if strings.EqualFold(name, "encoding") {
			return value, value != ""
		}
	}
}

// cssCharset reads a CSS @charset rule that starts at byte 0.
func cssCharset(data []byte) (string, bool) {
	const prefix = `@charset "`
	if !bytes.HasPrefix(data, []byte(prefix)) {
		return "", false
	}
	end := bytes.IndexByte(data[len(prefix):], '"')
	if end < 0 {
		return "", false
	}
	valueStart := len(prefix) + end + 1
	if !bytes.HasPrefix(data[valueStart:], []byte(";")) {
		return "", false
	}
	value := string(data[len(prefix) : len(prefix)+end])
	return value, value != ""
}

// htmlMetaCharset runs the HTML prescan over the first 1024 bytes and reports the charset of
// a meta element before </head> or <body>.
func htmlMetaCharset(data []byte) (string, bool) {
	if len(data) > maxDeclarationBytes {
		data = data[:maxDeclarationBytes]
	}
	tokenizer := html.NewTokenizer(bytes.NewReader(data))
	for {
		switch tokenizer.Next() {
		case html.ErrorToken:
			return "", false
		case html.TextToken, html.CommentToken, html.DoctypeToken:
			continue
		case html.StartTagToken, html.SelfClosingTagToken:
			token := tokenizer.Token()
			switch token.Data {
			case "body":
				return "", false
			case "meta":
				if label, ok := metaCharsetAttribute(token.Attr); ok {
					return label, true
				}
			}
		case html.EndTagToken:
			if tokenizer.Token().Data == "head" {
				return "", false
			}
		}
	}
}

// metaCharsetAttribute reads the encoding a meta element declares, following the WHATWG
// algorithm for both the charset and the http-equiv="Content-Type" pragma.
func metaCharsetAttribute(attributes []html.Attribute) (string, bool) {
	var charset, httpEquiv, content string
	for _, attribute := range attributes {
		switch strings.ToLower(attribute.Key) {
		case "charset":
			charset = attribute.Val
		case "http-equiv":
			httpEquiv = attribute.Val
		case "content":
			content = attribute.Val
		}
	}
	if charset != "" {
		return strings.TrimSpace(charset), true
	}
	if strings.EqualFold(httpEquiv, "content-type") && content != "" {
		if label, ok := charsetOfContentType(content); ok {
			return label, true
		}
	}
	return "", false
}

// charsetOfContentType extracts the encoding from a Content-Type value, following the
// WHATWG "extract an encoding from a meta element" algorithm.
func charsetOfContentType(value string) (string, bool) {
	lower := strings.ToLower(value)
	for offset := 0; ; {
		index := strings.Index(lower[offset:], "charset")
		if index < 0 {
			return "", false
		}
		position := offset + index + len("charset")
		offset = position
		for position < len(value) && isASCIISpace(value[position]) {
			position++
		}
		if position >= len(value) || value[position] != '=' {
			continue
		}
		position++
		for position < len(value) && isASCIISpace(value[position]) {
			position++
		}
		end := position
		for end < len(value) && !isASCIISpace(value[end]) && value[end] != ';' {
			end++
		}
		label := strings.Trim(value[position:end], `"'`)
		return label, label != ""
	}
}

// codingCommentDeclaration reads a PEP 263 encoding comment from line 1, or from line 2 when
// line 1 is acceptable as a prefix line.
func codingCommentDeclaration(data []byte, line2Allowed func(string) bool) (string, int, bool) {
	lines, terminated := splitDeclarationLines(data, 2)
	if label, ok := firstCodingComment(lines[0]); ok {
		return label, 1, true
	}
	// Line 2 counts only when line 1 ended inside data: otherwise a truncated first line
	// would be mistaken for a second one.
	if len(lines) > 1 && terminated[0] && line2Allowed(lines[0]) {
		if label, ok := firstCodingComment(lines[1]); ok {
			return label, 2, true
		}
	}
	return "", 0, false
}

// pythonCommentAllowed reports whether a Python first line may be followed by an encoding
// comment: only a comment or whitespace is allowed.
func pythonCommentAllowed(line string) bool {
	trimmed := strings.TrimSpace(line)
	return trimmed == "" || strings.HasPrefix(trimmed, "#")
}

// rubyShebangAllowed reports whether a Ruby first line may be followed by an encoding comment.
func rubyShebangAllowed(line string) bool {
	return strings.HasPrefix(line, "#!")
}

// firstCodingComment reports the encoding named by a PEP 263 comment line.
func firstCodingComment(line string) (string, bool) {
	match := codingComment.FindStringSubmatch(line)
	if match == nil {
		return "", false
	}
	return match[1], true
}

// splitDeclarationLines returns at most count lines split on CRLF, CR and LF, and whether
// each line ended with a line break inside data.
func splitDeclarationLines(data []byte, count int) ([]string, []bool) {
	lines := make([]string, 0, count)
	terminated := make([]bool, 0, count)
	start := 0
	for index := 0; index < len(data) && len(lines) < count; index++ {
		switch data[index] {
		case '\r':
			lines = append(lines, string(data[start:index]))
			terminated = append(terminated, true)
			if index+1 < len(data) && data[index+1] == '\n' {
				index++
			}
			start = index + 1
		case '\n':
			lines = append(lines, string(data[start:index]))
			terminated = append(terminated, true)
			start = index + 1
		}
	}
	if len(lines) < count {
		lines = append(lines, string(data[start:]))
		terminated = append(terminated, false)
	}
	return lines, terminated
}

// jspPageEncoding reads pageEncoding, or the charset of contentType, from an <%@ page %>
// directive inside the prescan window.
func jspPageEncoding(data []byte) (string, bool) {
	if len(data) > maxDeclarationBytes {
		data = data[:maxDeclarationBytes]
	}
	match := jspPageDirective.FindSubmatch(data)
	if match == nil {
		return "", false
	}
	attributes := string(match[1])
	contentType := ""
	for {
		name, value, rest, ok := nextAttribute(attributes)
		if !ok {
			break
		}
		attributes = rest
		switch {
		case strings.EqualFold(name, "pageEncoding"):
			return value, value != ""
		case strings.EqualFold(name, "contentType"):
			contentType = value
		}
	}
	return charsetOfContentType(contentType)
}

// nextAttribute reads one name="value" attribute and returns the remaining text.
func nextAttribute(text string) (name, value, rest string, ok bool) {
	text = strings.TrimLeft(text, " \t\r\n\f,")
	if text == "" {
		return "", "", "", false
	}
	separator := strings.IndexAny(text, "= \t\r\n\f")
	if separator < 0 {
		return "", "", "", false
	}
	name = strings.TrimSpace(text[:separator])
	remainder := strings.TrimLeft(text[separator:], " \t\r\n\f")
	if !strings.HasPrefix(remainder, "=") {
		return "", "", "", false
	}
	remainder = strings.TrimLeft(remainder[len("="):], " \t\r\n\f")
	if remainder == "" {
		return "", "", "", false
	}
	quote := remainder[0]
	if quote != '"' && quote != '\'' {
		end := strings.IndexAny(remainder, " \t\r\n\f,")
		if end < 0 {
			return name, remainder, "", true
		}
		return name, remainder[:end], remainder[end:], true
	}
	end := strings.IndexByte(remainder[1:], quote)
	if end < 0 {
		return "", "", "", false
	}
	return name, remainder[1 : 1+end], remainder[2+end:], true
}

func isASCIISpace(value byte) bool {
	return value == ' ' || value == '\t' || value == '\r' || value == '\n' || value == '\f'
}
