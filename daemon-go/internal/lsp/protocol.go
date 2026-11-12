package lsp

import (
	"bytes"
	"encoding/json"
)

type Position struct {
	Character int `json:"character"`
	Line      int `json:"line"`
}

type Range struct {
	End   *Position `json:"end,omitempty"`
	Start *Position `json:"start,omitempty"`
}

type Location struct {
	Range *Range `json:"range,omitempty"`
	URI   string `json:"uri"`
}

type LocationLink struct {
	OriginSelectionRange *Range `json:"originSelectionRange,omitempty"`
	TargetRange          Range  `json:"targetRange"`
	TargetSelectionRange Range  `json:"targetSelectionRange"`
	TargetURI            string `json:"targetUri"`
}

type DefinitionItem struct {
	Location *Location
	Link     *LocationLink
}

func (item *DefinitionItem) UnmarshalJSON(data []byte) error {
	var shape struct {
		Range                json.RawMessage `json:"range"`
		TargetRange          json.RawMessage `json:"targetRange"`
		TargetSelectionRange json.RawMessage `json:"targetSelectionRange"`
		TargetURI            json.RawMessage `json:"targetUri"`
		URI                  json.RawMessage `json:"uri"`
	}
	if err := json.Unmarshal(data, &shape); err != nil {
		return err
	}
	if shape.TargetURI != nil {
		link := &LocationLink{TargetURI: decodeJSONString(shape.TargetURI)}
		link.TargetRange = decodeRange(shape.TargetRange)
		selection := shape.TargetSelectionRange
		if len(selection) == 0 || bytes.Equal(bytes.TrimSpace(selection), []byte("null")) {
			selection = shape.TargetRange
		}
		link.TargetSelectionRange = decodeRange(selection)
		item.Link = link
		return nil
	}
	if shape.URI != nil {
		item.Location = &Location{URI: decodeJSONString(shape.URI), Range: decodeRangePtr(shape.Range)}
	}
	return nil
}

type DefinitionResult []DefinitionItem

func (result *DefinitionResult) UnmarshalJSON(data []byte) error {
	trimmed := bytes.TrimSpace(data)
	if bytes.Equal(trimmed, []byte("null")) {
		*result = nil
		return nil
	}
	var values []json.RawMessage
	if len(trimmed) > 0 && trimmed[0] == '[' {
		if err := json.Unmarshal(trimmed, &values); err != nil {
			return err
		}
	} else {
		values = []json.RawMessage{trimmed}
	}
	items := make([]DefinitionItem, 0, len(values))
	for _, value := range values {
		var item DefinitionItem
		if err := json.Unmarshal(value, &item); err == nil && (item.Location != nil || item.Link != nil) {
			items = append(items, item)
		}
	}
	*result = items
	return nil
}

func decodeRange(raw json.RawMessage) Range {
	var value Range
	if len(raw) == 0 || bytes.Equal(bytes.TrimSpace(raw), []byte("null")) || json.Unmarshal(raw, &value) != nil {
		return Range{}
	}
	return value
}

func decodeRangePtr(raw json.RawMessage) *Range {
	if len(raw) == 0 || bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
		return nil
	}
	var value Range
	if json.Unmarshal(raw, &value) != nil {
		return nil
	}
	return &value
}

func decodeJSONString(raw json.RawMessage) string {
	var value string
	_ = json.Unmarshal(raw, &value)
	return value
}

type TextDocumentIdentifier struct {
	URI string `json:"uri"`
}

type TextDocumentItem struct {
	LanguageID string `json:"languageId"`
	Text       string `json:"text"`
	URI        string `json:"uri"`
	Version    int    `json:"version"`
}

type VersionedTextDocumentIdentifier struct {
	URI     string `json:"uri"`
	Version int    `json:"version"`
}

type ContentChangeEvent struct {
	Text string `json:"text"`
}

type DidOpenTextDocumentParams struct {
	TextDocument TextDocumentItem `json:"textDocument"`
}

type DidChangeTextDocumentParams struct {
	ContentChanges []ContentChangeEvent            `json:"contentChanges"`
	TextDocument   VersionedTextDocumentIdentifier `json:"textDocument"`
}

type DidSaveTextDocumentParams struct {
	TextDocument TextDocumentIdentifier `json:"textDocument"`
}

type DefinitionParams struct {
	Position     Position               `json:"position"`
	TextDocument TextDocumentIdentifier `json:"textDocument"`
}

type WorkspaceSymbolParams struct {
	Query string `json:"query"`
}

type ExecuteCommandParams struct {
	Arguments []string `json:"arguments"`
	Command   string   `json:"command"`
}

type WorkspaceFolder struct {
	Name string `json:"name"`
	URI  string `json:"uri"`
}

type GeneralClientCapabilities struct {
	PositionEncodings []string `json:"positionEncodings"`
}

type WorkspaceClientCapabilities struct {
	ApplyEdit        bool `json:"applyEdit"`
	Configuration    bool `json:"configuration"`
	WorkspaceFolders bool `json:"workspaceFolders"`
}

type TextDocumentSyncClientCapabilities struct {
	DidSave           bool `json:"didSave"`
	WillSave          bool `json:"willSave"`
	WillSaveWaitUntil bool `json:"willSaveWaitUntil"`
}

type DefinitionClientCapabilities struct {
	LinkSupport bool `json:"linkSupport"`
}

type TextDocumentClientCapabilities struct {
	Definition      DefinitionClientCapabilities       `json:"definition"`
	Synchronization TextDocumentSyncClientCapabilities `json:"synchronization"`
}

type ClientCapabilities struct {
	General      GeneralClientCapabilities      `json:"general"`
	TextDocument TextDocumentClientCapabilities `json:"textDocument"`
	Workspace    WorkspaceClientCapabilities    `json:"workspace"`
}

type ExtendedClientCapabilities struct {
	ClassFileContentsSupport bool `json:"classFileContentsSupport"`
}

type InitializationClientCapabilities struct {
	ExtendedClientCapabilities ExtendedClientCapabilities `json:"extendedClientCapabilities"`
}

type InitializeParams struct {
	Capabilities          ClientCapabilities                `json:"capabilities"`
	InitializationOptions *InitializationClientCapabilities `json:"initializationOptions,omitempty"`
	ProcessID             int                               `json:"processId"`
	RootPath              string                            `json:"rootPath"`
	RootURI               string                            `json:"rootUri"`
	WorkspaceFolders      []WorkspaceFolder                 `json:"workspaceFolders"`
}

type CapabilitySupport bool

func (support *CapabilitySupport) UnmarshalJSON(data []byte) error {
	trimmed := bytes.TrimSpace(data)
	if bytes.Equal(trimmed, []byte("null")) {
		*support = false
		return nil
	}
	var enabled bool
	if err := json.Unmarshal(trimmed, &enabled); err == nil {
		*support = CapabilitySupport(enabled)
		return nil
	}
	*support = true
	return nil
}

type ServerCapabilities struct {
	DefinitionProvider      CapabilitySupport `json:"definitionProvider"`
	Experimental            json.RawMessage   `json:"experimental,omitempty"`
	PositionEncoding        string            `json:"positionEncoding"`
	WorkspaceSymbolProvider CapabilitySupport `json:"workspaceSymbolProvider"`
}

type InitializeResult struct {
	Capabilities ServerCapabilities `json:"capabilities"`
}

type InitializedParams struct{}

type WorkspaceConfigurationItem struct{}

type WorkspaceConfigurationParams struct {
	Items []json.RawMessage `json:"items"`
}

type WorkspaceFoldersParams struct{}

type WorkspaceFoldersResult []WorkspaceFolder

type WorkspaceConfigurationResult []WorkspaceConfigurationItem

type WorkspaceApplyEditResult struct {
	Applied bool `json:"applied"`
}

type CancelRequestParams struct {
	ID uint64 `json:"id"`
}

type SymbolKind struct {
	Number   int
	String   string
	IsNumber bool
}

func (kind *SymbolKind) UnmarshalJSON(data []byte) error {
	trimmed := bytes.TrimSpace(data)
	if len(trimmed) > 0 && trimmed[0] == '"' {
		return json.Unmarshal(trimmed, &kind.String)
	}
	kind.IsNumber = true
	if err := json.Unmarshal(trimmed, &kind.Number); err != nil {
		kind.IsNumber = false
	}
	return nil
}

type SymbolLocation struct {
	Range *Range `json:"range,omitempty"`
	URI   string `json:"uri"`
}

type SymbolInformation struct {
	Kind     SymbolKind     `json:"kind"`
	Location SymbolLocation `json:"location"`
	Name     string         `json:"name"`
}

type WorkspaceSymbol = SymbolInformation

type WorkspaceSymbolsResult []WorkspaceSymbol

func (result *WorkspaceSymbolsResult) UnmarshalJSON(data []byte) error {
	trimmed := bytes.TrimSpace(data)
	if bytes.Equal(trimmed, []byte("null")) {
		*result = nil
		return nil
	}
	var values []json.RawMessage
	if err := json.Unmarshal(trimmed, &values); err != nil {
		return err
	}
	items := make([]WorkspaceSymbol, 0, len(values))
	for _, value := range values {
		trimmedValue := bytes.TrimSpace(value)
		if len(trimmedValue) == 0 || trimmedValue[0] != '{' {
			continue
		}
		var item WorkspaceSymbol
		if err := json.Unmarshal(trimmedValue, &item); err == nil {
			items = append(items, item)
		}
	}
	*result = items
	return nil
}
