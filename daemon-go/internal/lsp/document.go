package lsp

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
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
		return c.Notify(context.Background(), "textDocument/didOpen", map[string]any{"textDocument": map[string]any{"uri": uri, "languageId": language, "version": 1, "text": decoded.Text}})
	}
	version := old.version + 1
	c.documents[abs] = document{text: decoded.Text, version: version}
	c.mu.Unlock()
	if err := c.Notify(context.Background(), "textDocument/didChange", map[string]any{"textDocument": map[string]any{"uri": uri, "version": version}, "contentChanges": []map[string]string{{"text": decoded.Text}}}); err != nil {
		return err
	}
	return c.Notify(context.Background(), "textDocument/didSave", map[string]any{"textDocument": map[string]string{"uri": uri}})
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

func (c *Client) Position(path string, line, character int) (map[string]int, error) {
	if err := c.Sync(path); err != nil {
		return nil, err
	}
	abs, _ := filepath.Abs(path)
	c.mu.RLock()
	doc := c.documents[abs]
	encoding := c.positionEncoding
	c.mu.RUnlock()
	return documentPosition(doc.text, line, character, encoding)
}

func documentPosition(document string, line, character int, encoding string) (map[string]int, error) {
	lines := strings.Split(document, "\n")
	if line < 1 || line > len(lines) {
		return nil, &LspError{Message: fmt.Sprintf("line %d is beyond the end of the synchronized document", line)}
	}
	text := strings.TrimSuffix(lines[line-1], "\r")
	runes := []rune(text)
	if character < 0 || character > len(runes) {
		return nil, &LspError{Message: fmt.Sprintf("character %d is beyond line %d of the synchronized document (%d code points)", character, line, len(runes))}
	}
	offset := 0
	for _, r := range runes[:character] {
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
	return map[string]int{"line": line - 1, "character": offset}, nil
}
func (c *Client) Definition(ctx context.Context, path string, line, character int) ([]string, error) {
	if !c.Supports("textDocument/definition") {
		return nil, &LspError{Message: fmt.Sprintf("LSP server '%s' does not advertise go-to-definition support.", c.server.ID)}
	}
	pos, err := c.Position(path, line, character)
	if err != nil {
		return nil, err
	}
	var result json.RawMessage
	if err = c.Call(ctx, "textDocument/definition", map[string]any{"textDocument": map[string]string{"uri": pathURI(path)}, "position": pos}, &result); err != nil {
		return nil, err
	}
	return formatLocations(result)
}
func (c *Client) WorkspaceSymbols(ctx context.Context, query string, limit int) ([]string, error) {
	if !c.Supports("workspace/symbol") {
		return nil, &LspError{Message: fmt.Sprintf("LSP server '%s' does not advertise workspace symbol support.", c.server.ID)}
	}
	var result json.RawMessage
	if err := c.Call(ctx, "workspace/symbol", map[string]string{"query": query}, &result); err != nil {
		return nil, err
	}
	return formatSymbols(result, limit)
}

var jdtURI = regexp.MustCompile(`jdt://\S+`)

func (c *Client) JavaDecompile(ctx context.Context, target string) (string, error) {
	var result string
	var err error
	if match := jdtURI.FindString(target); match != "" {
		if !c.jdtls {
			return "", &LspError{Message: "lsp_java_decompile is only supported by jdtls."}
		}
		err = c.Call(ctx, "java/classFileContents", map[string]string{"uri": match}, &result)
	} else {
		localPath := strings.TrimSpace(target)
		if strings.HasPrefix(localPath, "file:") {
			parsed, e := url.Parse(localPath)
			if e != nil || parsed.Scheme != "file" || parsed.Path == "" {
				return "", &LspError{Message: "invalid class target URI: " + target}
			}
			decoded, e := url.PathUnescape(parsed.Path)
			if e != nil {
				return "", &LspError{Message: "invalid class target URI: " + target}
			}
			localPath = filepath.FromSlash(decoded)
		} else if !filepath.IsAbs(localPath) {
			return "", &LspError{Message: "target must be an absolute class path, a file: URI, or a jdt:// URI: " + target}
		}
		info, e := os.Stat(localPath)
		if e != nil || !info.Mode().IsRegular() {
			return "", &LspError{Message: "target is not a readable class file: " + localPath}
		}
		err = c.Call(ctx, "workspace/executeCommand", map[string]any{"command": "java.decompile", "arguments": []string{pathURI(localPath)}}, &result)
	}
	if err != nil {
		return "", err
	}
	if strings.TrimSpace(result) == "" {
		return "", &LspError{Message: "Could not load or decompile class for target: " + target}
	}
	return result, nil
}

func formatLocations(raw json.RawMessage) ([]string, error) {
	if len(raw) == 0 || string(raw) == "null" {
		return []string{"No results found"}, nil
	}
	var values []json.RawMessage
	if raw[0] == '[' {
		if err := json.Unmarshal(raw, &values); err != nil {
			return nil, err
		}
	} else {
		values = []json.RawMessage{raw}
	}
	out := make([]string, 0, len(values))
	for _, v := range values {
		var item map[string]json.RawMessage
		if json.Unmarshal(v, &item) != nil {
			continue
		}
		uri := readString(item["uri"])
		rangeData := item["range"]
		if uri == "" {
			uri = readString(item["targetUri"])
			rangeData = item["targetSelectionRange"]
			if len(rangeData) == 0 {
				rangeData = item["targetRange"]
			}
		}
		var r struct{ Start struct{ Line, Character int } }
		_ = json.Unmarshal(rangeData, &r)
		out = append(out, locationPath(uri)+fmt.Sprintf(":%d:%d", r.Start.Line+1, r.Start.Character))
	}
	if len(out) == 0 {
		return []string{"No results found"}, nil
	}
	return out, nil
}
func readString(raw json.RawMessage) string { var s string; _ = json.Unmarshal(raw, &s); return s }
func locationPath(value string) string {
	u, err := url.Parse(value)
	if err == nil && u.Scheme == "file" {
		path, e := url.PathUnescape(u.Path)
		if e == nil {
			return filepath.FromSlash(path)
		}
	}
	return value
}
func formatSymbols(raw json.RawMessage, limit int) ([]string, error) {
	if limit == 0 {
		return []string{"No symbols found"}, nil
	}
	if len(raw) == 0 || string(raw) == "null" {
		return []string{"No symbols found"}, nil
	}
	var values []map[string]json.RawMessage
	if err := json.Unmarshal(raw, &values); err != nil {
		return nil, err
	}
	kinds := []string{"File", "Module", "Namespace", "Package", "Class", "Method", "Property", "Field", "Constructor", "Enum", "Interface", "Function", "Variable", "Constant", "String", "Number", "Boolean", "Array", "Object", "Key", "Null", "EnumMember", "Struct", "Event", "Operator", "TypeParameter"}
	out := make([]string, 0, len(values))
	for _, v := range values {
		var name string
		_ = json.Unmarshal(v["name"], &name)
		var kind string
		if json.Unmarshal(v["kind"], &kind) != nil {
			var n int
			if json.Unmarshal(v["kind"], &n) == nil && n >= 1 && n <= len(kinds) {
				kind = kinds[n-1]
			} else {
				kind = "unknown"
			}
		}
		uri := readString(v["uri"])
		if uri == "" {
			var loc map[string]json.RawMessage
			_ = json.Unmarshal(v["location"], &loc)
			uri = readString(loc["uri"])
		}
		if uri == "" {
			uri = "unknown"
		}
		out = append(out, fmt.Sprintf("%s (%s) - %s", name, kind, uri))
		if limit >= 0 && len(out) >= limit {
			break
		}
	}
	if len(out) == 0 {
		return []string{"No symbols found"}, nil
	}
	return out, nil
}
