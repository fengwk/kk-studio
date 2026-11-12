package lsp

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"unicode/utf8"
)

func (c *Client) Sync(path string) error {
	abs, err := filepath.Abs(path)
	if err != nil {
		return &LspError{Message: "LSP request failed: the source file could not be read", Cause: err}
	}
	data, err := os.ReadFile(abs)
	if err != nil {
		return &LspError{Message: "LSP request failed: the source file could not be read", Cause: err}
	}
	decoded, err := DecodeTextFile(data)
	if err != nil {
		return &LspError{Message: "LSP request failed: the source file is not valid text", Cause: err}
	}
	uri := pathURI(abs)
	c.mu.Lock()
	old, opened := c.documents[abs]
	if opened && old.text == decoded.Text {
		c.mu.Unlock()
		return nil
	}
	language := languageID(filepath.Ext(abs), c.server.ID)
	if !opened {
		c.documents[abs] = document{text: decoded.Text, version: 1}
		c.mu.Unlock()
		return c.Notify(context.Background(), "textDocument/didOpen", DidOpenTextDocumentParams{TextDocument: TextDocumentItem{LanguageID: language, Text: decoded.Text, URI: uri, Version: 1}})
	}
	version := old.version + 1
	c.documents[abs] = document{text: decoded.Text, version: version}
	c.mu.Unlock()
	if err := c.Notify(context.Background(), "textDocument/didChange", DidChangeTextDocumentParams{ContentChanges: []ContentChangeEvent{{Text: decoded.Text}}, TextDocument: VersionedTextDocumentIdentifier{URI: uri, Version: version}}); err != nil {
		return err
	}
	return c.Notify(context.Background(), "textDocument/didSave", DidSaveTextDocumentParams{TextDocument: TextDocumentIdentifier{URI: uri}})
}
func languageID(ext, fallback string) string {
	switch strings.ToLower(ext) {
	case ".ts", ".mts", ".cts":
		return "typescript"
	case ".tsx":
		return "typescriptreact"
	case ".js", ".mjs", ".cjs":
		return "javascript"
	case ".jsx":
		return "javascriptreact"
	case ".java":
		return "java"
	case ".go":
		return "go"
	case ".py", ".pyi":
		return "python"
	case ".c", ".h":
		return "c"
	case ".cpp", ".cc", ".hpp":
		return "cpp"
	}
	return fallback
}
func (c *Client) Refresh(path string) {
	abs, err := filepath.Abs(path)
	if err != nil {
		return
	}
	c.mu.RLock()
	_, ok := c.documents[abs]
	c.mu.RUnlock()
	if ok && c.IsAlive() {
		_ = c.Sync(abs)
	}
}

func (c *Client) Position(path string, line, character int) (Position, error) {
	if err := c.Sync(path); err != nil {
		return Position{}, err
	}
	abs, _ := filepath.Abs(path)
	c.mu.RLock()
	doc := c.documents[abs]
	encoding := c.positionEncoding
	c.mu.RUnlock()
	return documentPosition(doc.text, line, character, encoding)
}

func documentPosition(document string, line, character int, encoding string) (Position, error) {
	lines := strings.Split(document, "\n")
	if line < 1 || line > len(lines) {
		return Position{}, &LspError{Message: fmt.Sprintf("line %d is beyond the end of the synchronized document", line)}
	}
	text := strings.TrimSuffix(lines[line-1], "\r")
	runes := []rune(text)
	if character < 1 || character-1 > len(runes) {
		return Position{}, &LspError{Message: fmt.Sprintf("character %d is beyond line %d of the synchronized document (%d code points)", character, line, len(runes))}
	}
	offset := 0
	for _, r := range runes[:character-1] {
		switch encoding {
		case "utf-8":
			offset += utf8.RuneLen(r)
		case "utf-32":
			offset++
		default:
			if r > 0xffff {
				offset += 2
			} else {
				offset++
			}
		}
	}
	return Position{Line: line - 1, Character: offset}, nil
}
func (c *Client) Definition(ctx context.Context, path string, line, character int, tracking ...func()) ([]string, error) {
	if !c.Supports("textDocument/definition") {
		return nil, &LspError{Message: fmt.Sprintf("LSP server '%s' does not advertise go-to-definition support.", c.server.ID)}
	}
	pos, err := c.Position(path, line, character)
	if err != nil {
		return nil, err
	}
	var result DefinitionResult
	onSent, onFinished := trackingCallbacks(tracking)
	if err = c.call(ctx, "textDocument/definition", DefinitionParams{TextDocument: TextDocumentIdentifier{URI: pathURI(path)}, Position: pos}, &result, onSent, onFinished); err != nil {
		return nil, err
	}
	return c.formatDefinitionLocations(result), nil
}
func (c *Client) WorkspaceSymbols(ctx context.Context, query string, limit int, tracking ...func()) ([]string, error) {
	if !c.Supports("workspace/symbol") {
		return nil, &LspError{Message: fmt.Sprintf("LSP server '%s' does not advertise workspace symbol support.", c.server.ID)}
	}
	var result WorkspaceSymbolsResult
	onSent, onFinished := trackingCallbacks(tracking)
	if err := c.call(ctx, "workspace/symbol", WorkspaceSymbolParams{Query: query}, &result, onSent, onFinished); err != nil {
		return nil, err
	}
	return formatSymbols(result, limit)
}
func trackingCallbacks(callbacks []func()) (func(), func()) {
	if len(callbacks) == 0 {
		return nil, nil
	}
	var finished func()
	if len(callbacks) > 1 {
		finished = callbacks[1]
	}
	return callbacks[0], finished
}

var jdtURI = regexp.MustCompile(`jdt://\S+`)

func (c *Client) JavaDecompile(ctx context.Context, target string, tracking ...func()) (string, error) {
	onSent, onFinished := trackingCallbacks(tracking)
	var result string
	var err error
	if match := jdtURI.FindString(target); match != "" {
		if !c.jdtls {
			return "", &LspError{Message: "lsp_java_decompile is only supported by jdtls."}
		}
		err = c.call(ctx, "java/classFileContents", TextDocumentIdentifier{URI: match}, &result, onSent, onFinished)
	} else {
		localPath := strings.TrimSpace(target)
		if strings.HasPrefix(localPath, "file:") {
			parsed, e := url.Parse(localPath)
			if e != nil || parsed.Scheme != "file" || parsed.Path == "" {
				return "", &LspError{Message: "invalid class target URI: " + target}
			}
			localPath, e = fileURIPath(parsed)
			if e != nil {
				return "", &LspError{Message: "invalid class target URI: " + target}
			}
		} else if !filepath.IsAbs(localPath) {
			return "", &LspError{Message: "target must be an absolute class path, a file: URI, or a jdt:// URI: " + target}
		}
		info, e := os.Stat(localPath)
		if e != nil || !info.Mode().IsRegular() {
			return "", &LspError{Message: "target is not a readable class file: " + localPath}
		}
		err = c.call(ctx, "workspace/executeCommand", ExecuteCommandParams{Command: "java.decompile", Arguments: []string{pathURI(localPath)}}, &result, onSent, onFinished)
	}
	if err != nil {
		return "", err
	}
	if strings.TrimSpace(result) == "" {
		return "", &LspError{Message: "Could not load or decompile class for target: " + target}
	}
	return result, nil
}

func formatLocations(result DefinitionResult) []string {
	return formatLocationsWithColumn(result, func(_ string, _ int, character int) int { return character + 1 })
}

func (c *Client) formatDefinitionLocations(result DefinitionResult) []string {
	return formatLocationsWithColumn(result, func(uri string, line, character int) int {
		return c.definitionColumn(uri, line, character)
	})
}

func (c *Client) definitionColumn(uri string, line, character int) int {
	fallback := character + 1
	parsed, err := url.Parse(uri)
	if err != nil || parsed.Scheme != "file" {
		return fallback
	}
	path, err := fileURIPath(parsed)
	if err != nil {
		return fallback
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return fallback
	}
	decoded, err := DecodeTextFile(data)
	if err != nil {
		return fallback
	}
	lines := strings.Split(decoded.Text, "\n")
	if line < 0 || line >= len(lines) {
		return fallback
	}
	text := strings.TrimSuffix(lines[line], "\r")
	c.mu.RLock()
	encoding := c.positionEncoding
	c.mu.RUnlock()
	column, ok := documentColumn(text, character, encoding)
	if !ok {
		return fallback
	}
	return column
}

func documentColumn(text string, offset int, encoding string) (int, bool) {
	if offset < 0 {
		return 0, false
	}
	column := 1
	for _, r := range text {
		if offset == 0 {
			return column, true
		}
		width := 1
		switch encoding {
		case "utf-8":
			width = utf8.RuneLen(r)
		case "utf-32":
		default:
			if r > 0xffff {
				width = 2
			}
		}
		if offset < width {
			return 0, false
		}
		offset -= width
		column++
	}
	return column, offset == 0
}

func formatLocationsWithColumn(result DefinitionResult, column func(string, int, int) int) []string {
	out := make([]string, 0, len(result))
	for _, item := range result {
		var uri string
		var locationRange *Range
		if item.Link != nil {
			uri = item.Link.TargetURI
			locationRange = &item.Link.TargetSelectionRange
		} else if item.Location != nil {
			uri = item.Location.URI
			locationRange = item.Location.Range
		} else {
			continue
		}
		out = append(out, locationTextWithColumn(uri, locationRange, column))
	}
	if len(out) == 0 {
		return []string{"No results found"}
	}
	return out
}

func locationTextWithColumn(uri string, locationRange *Range, column func(string, int, int) int) string {
	if uri == "" {
		return "unknown"
	}
	location := locationPath(uri)
	if locationRange == nil || locationRange.Start == nil {
		return location
	}
	line := locationRange.Start.Line
	return fmt.Sprintf("%s:%d:%d", location, line+1, column(uri, line, locationRange.Start.Character))
}
func locationPath(value string) string {
	u, err := url.Parse(value)
	if err == nil && u.Scheme == "file" {
		path, e := fileURIPath(u)
		if e == nil {
			return path
		}
	}
	return value
}

func fileURIPath(uri *url.URL) (string, error) {
	if uri.Scheme != "file" || uri.Path == "" {
		return "", errors.New("invalid file URI")
	}
	path, err := url.PathUnescape(uri.Path)
	if err != nil {
		return "", err
	}
	if runtime.GOOS == "windows" && len(path) >= 3 && path[0] == '/' && path[2] == ':' {
		path = path[1:]
	}
	return filepath.FromSlash(path), nil
}
func formatSymbols(result WorkspaceSymbolsResult, limit int) ([]string, error) {
	if limit <= 0 || len(result) == 0 {
		return []string{"No symbols found"}, nil
	}
	kinds := []string{"File", "Module", "Namespace", "Package", "Class", "Method", "Property", "Field", "Constructor", "Enum", "Interface", "Function", "Variable", "Constant", "String", "Number", "Boolean", "Array", "Object", "Key", "Null", "EnumMember", "Struct", "Event", "Operator", "TypeParameter"}
	out := make([]string, 0, min(len(result), limit))
	for _, item := range result {
		name := item.Name
		if name == "" {
			name = "unknown"
		}
		kind := item.Kind.String
		if kind == "" {
			if item.Kind.IsNumber && item.Kind.Number >= 1 && item.Kind.Number <= len(kinds) {
				kind = kinds[item.Kind.Number-1]
			} else {
				kind = "unknown"
			}
		}
		uri := item.Location.URI
		if uri == "" {
			uri = "unknown"
		}
		out = append(out, fmt.Sprintf("%s (%s) - %s", name, kind, uri))
		if len(out) == limit {
			break
		}
	}
	if len(out) == 0 {
		return []string{"No symbols found"}, nil
	}
	return out, nil
}
