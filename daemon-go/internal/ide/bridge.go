package ide

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net"
	"net/http"
	"net/url"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf16"
	"unicode/utf8"
)

const (
	bridgeMaxBodyBytes         = 8 << 20
	bridgeMaxSnapshotDocuments = 256
	bridgeMaxSnapshotPathChars = 4096
	bridgeMaxCommandDocuments  = 256
	bridgeMaxViewHosts         = 256
	bridgeCommandTimeout       = 20 * time.Second
)

// BridgeDocument is one ordinary text buffer returned for backup or sent for recovery.
type BridgeDocument struct {
	Path string `json:"path"`
	Text string `json:"text"`
}

// BridgeCommand is one operation sent over the view's NDJSON control stream.
type BridgeCommand struct {
	Op        string           `json:"op"`
	Directory string           `json:"directory,omitempty"`
	Discard   bool             `json:"discard,omitempty"`
	MaxBytes  int64            `json:"maxBytes,omitempty"`
	Documents []BridgeDocument `json:"documents,omitempty"`
}

// BridgeRequestOptions fences directory transitions and commands to the expected binding.
type BridgeRequestOptions struct {
	ExpectedDirectory string
	ExpectedEpoch     int64
}

// BridgeResult is a safe response from the in-window extension host.
type BridgeResult struct {
	OK        bool             `json:"ok"`
	Code      string           `json:"code,omitempty"`
	Message   string           `json:"message,omitempty"`
	Directory string           `json:"directory,omitempty"`
	Clean     *bool            `json:"clean,omitempty"`
	Documents []BridgeDocument `json:"documents,omitempty"`
	Omitted   *int64           `json:"omitted,omitempty"`
	Complete  *bool            `json:"complete,omitempty"`
	Restored  *int64           `json:"restored,omitempty"`
	Skipped   *int64           `json:"skipped,omitempty"`
}

type bridgeView struct {
	workspacePath string
	key           string
	epoch         int64
	directory     string
	hosts         map[string]struct{}
	hostID        string
	stream        *bridgeStream
}

type bridgeStream struct {
	id           uint64
	hostID       string
	messages     chan []byte
	retired      chan struct{}
	retiredFrame bool
	once         sync.Once
}

func (s *bridgeStream) close(sendRetired bool) {
	s.once.Do(func() {
		s.retiredFrame = sendRetired
		close(s.retired)
	})
}

type bridgePending struct {
	viewID            string
	epoch             int64
	op                string
	stream            *bridgeStream
	hostID            string
	expectedDirectory string
	result            chan BridgeResult
	timer             *time.Timer
}

// Bridge is the authenticated, loopback-only HTTP/NDJSON connection to extension hosts.
type Bridge struct {
	URL      string
	Secret   string
	server   *http.Server
	listener net.Listener

	mu           sync.Mutex
	views        map[string]*bridgeView
	byKey        map[string]string
	pending      map[string]*bridgePending
	waiters      map[string]map[chan error]struct{}
	ptyListeners []func(string, int) error
	nextStreamID uint64
	closed       bool
	closeDone    chan struct{}
	closeErr     error
}

// NewBridge starts a token-protected HTTP service on an ephemeral loopback port.
func NewBridge() (*Bridge, error) {
	secret, err := randomToken()
	if err != nil {
		return nil, NewError("ide bridge could not be started", "UNAVAILABLE")
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, NewError("ide bridge could not be started", "UNAVAILABLE")
	}
	bridge := &Bridge{
		URL: "http://" + listener.Addr().String(), Secret: secret, listener: listener,
		views: make(map[string]*bridgeView), byKey: make(map[string]string),
		pending: make(map[string]*bridgePending), waiters: make(map[string]map[chan error]struct{}),
		closeDone: make(chan struct{}),
	}
	bridge.server = &http.Server{Handler: http.HandlerFunc(bridge.handle)}
	go func() { _ = bridge.server.Serve(listener) }()
	return bridge, nil
}

// Assign binds an exact workspace file and confirmed directory to a view epoch.
func (b *Bridge) Assign(viewID, workspacePath string, epoch int64, directory string) {
	key := b.pathKey(workspacePath)
	b.mu.Lock()
	defer b.mu.Unlock()
	if old := b.views[viewID]; old != nil {
		if old.stream != nil {
			old.stream.close(true)
		}
		b.rejectWaitersLocked(viewID, NewError("view binding changed", "VIEW_GONE"))
		if old.key != key {
			delete(b.byKey, old.key)
		}
		b.settleViewLocked(viewID, BridgeResult{Code: "VIEW_GONE", Message: "view binding changed"})
	}
	hosts := make(map[string]struct{})
	if old := b.views[viewID]; old != nil {
		hosts = old.hosts
	}
	b.views[viewID] = &bridgeView{workspacePath: workspacePath, key: key, epoch: epoch, directory: directory, hosts: hosts}
	b.byKey[key] = viewID
}

// Revoke removes only the matching epoch when one is supplied.
func (b *Bridge) Revoke(viewID string, expectedEpoch int64) {
	b.mu.Lock()
	defer b.mu.Unlock()
	view := b.views[viewID]
	if view == nil || expectedEpoch > 0 && view.epoch != expectedEpoch {
		return
	}
	b.settleViewLocked(viewID, BridgeResult{Code: "VIEW_GONE", Message: "ide window was closed"})
	b.rejectWaitersLocked(viewID, NewError("ide window was closed", "VIEW_GONE"))
	if view.stream != nil {
		view.stream.close(false)
	}
	delete(b.byKey, view.key)
	delete(b.views, viewID)
}

// IsConnected reports whether the current view has an admitted host stream.
func (b *Bridge) IsConnected(viewID string, epoch int64) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	view := b.views[viewID]
	return view != nil && view.stream != nil && (epoch == 0 || view.epoch == epoch)
}

// OnPTY registers a callback invoked only for a PTY belonging to an assigned workspace.
func (b *Bridge) OnPTY(listener func(string, int) error) {
	b.mu.Lock()
	b.ptyListeners = append(b.ptyListeners, listener)
	b.mu.Unlock()
}

// WaitForConnection waits for this view's window stream, or returns on cancellation/revocation.
func (b *Bridge) WaitForConnection(ctx context.Context, viewID string) error {
	b.mu.Lock()
	if view := b.views[viewID]; view != nil && view.stream != nil {
		b.mu.Unlock()
		return nil
	}
	if b.closed {
		b.mu.Unlock()
		return NewError("ide bridge closed", "UNAVAILABLE")
	}
	if err := ctx.Err(); err != nil {
		b.mu.Unlock()
		return ErrCancelled
	}
	waiter := make(chan error, 1)
	if b.waiters[viewID] == nil {
		b.waiters[viewID] = make(map[chan error]struct{})
	}
	b.waiters[viewID][waiter] = struct{}{}
	b.mu.Unlock()
	select {
	case err := <-waiter:
		return err
	case <-ctx.Done():
		b.mu.Lock()
		delete(b.waiters[viewID], waiter)
		if len(b.waiters[viewID]) == 0 {
			delete(b.waiters, viewID)
		}
		b.mu.Unlock()
		return ErrCancelled
	}
}

// Request sends one command and waits for its matching extension-host result.
func (b *Bridge) Request(ctx context.Context, viewID string, command BridgeCommand, options BridgeRequestOptions) (BridgeResult, error) {
	b.mu.Lock()
	view := b.views[viewID]
	if view == nil || view.stream == nil || view.hostID == "" {
		b.mu.Unlock()
		return BridgeResult{}, NewError("ide window is not connected", "UNAVAILABLE")
	}
	if options.ExpectedEpoch > 0 && view.epoch != options.ExpectedEpoch {
		b.mu.Unlock()
		return BridgeResult{}, NewError("view binding changed", "VIEW_GONE")
	}
	if err := ctx.Err(); err != nil {
		b.mu.Unlock()
		return BridgeResult{Message: "ide request was cancelled"}, nil
	}
	if len(command.Documents) > bridgeMaxCommandDocuments {
		b.mu.Unlock()
		return BridgeResult{}, NewError("too many recovery documents", "UNAVAILABLE")
	}
	if command.Op == "changeDirectory" && !validDirectoryExpectation(command.Directory, options.ExpectedDirectory) {
		b.mu.Unlock()
		return BridgeResult{}, NewError("expected directory is invalid", "UNAVAILABLE")
	}
	if command.Op == "changeDirectory" {
		for _, pending := range b.pending {
			if pending.viewID == viewID && pending.op == "changeDirectory" {
				b.mu.Unlock()
				return BridgeResult{}, NewError("a directory change is already pending", "BUSY")
			}
		}
	}
	id, err := randomUUID()
	if err != nil {
		b.mu.Unlock()
		return BridgeResult{}, NewError("ide bridge command failed", "UNAVAILABLE")
	}
	encoded, err := json.Marshal(struct {
		Type string `json:"type"`
		ID   string `json:"id"`
		BridgeCommand
	}{Type: "command", ID: id, BridgeCommand: command})
	if err != nil {
		b.mu.Unlock()
		return BridgeResult{}, NewError("ide bridge command failed", "UNAVAILABLE")
	}
	pending := &bridgePending{viewID: viewID, epoch: view.epoch, op: command.Op, stream: view.stream, hostID: view.hostID, expectedDirectory: options.ExpectedDirectory, result: make(chan BridgeResult, 1)}
	if command.Op != "closeView" || command.Discard {
		pending.timer = time.AfterFunc(bridgeCommandTimeout, func() { b.resolvePending(id, BridgeResult{Message: "ide window did not respond"}) })
	}
	b.pending[id] = pending
	stream := view.stream
	b.mu.Unlock()
	select {
	case stream.messages <- append(encoded, '\n'):
	case <-stream.retired:
		b.resolvePending(id, BridgeResult{Code: "VIEW_GONE", Message: "ide window was closed"})
	case <-ctx.Done():
		b.resolvePending(id, BridgeResult{Message: "ide request was cancelled"})
	}
	select {
	case result := <-pending.result:
		return result, nil
	case <-ctx.Done():
		b.resolvePending(id, BridgeResult{Message: "ide request was cancelled"})
		return <-pending.result, nil
	}
}

// Close terminates all streams and pending requests and is safe to call repeatedly.
func (b *Bridge) Close(ctx context.Context) error {
	b.mu.Lock()
	if b.closed {
		done := b.closeDone
		b.mu.Unlock()
		select {
		case <-ctx.Done():
			return ErrCancelled
		case <-done:
			return b.closeErr
		}
	}
	b.closed = true
	for _, view := range b.views {
		if view.stream != nil {
			view.stream.close(false)
		}
	}
	for id, pending := range b.pending {
		if pending.timer != nil {
			pending.timer.Stop()
		}
		pending.result <- BridgeResult{Message: "ide bridge closed"}
		delete(b.pending, id)
	}
	for viewID := range b.waiters {
		b.rejectWaitersLocked(viewID, NewError("ide bridge closed", "UNAVAILABLE"))
	}
	b.views = make(map[string]*bridgeView)
	b.byKey = make(map[string]string)
	b.ptyListeners = nil
	b.mu.Unlock()
	err := b.server.Close()
	b.mu.Lock()
	b.closeErr = err
	close(b.closeDone)
	b.mu.Unlock()
	return err
}

func (b *Bridge) handle(writer http.ResponseWriter, request *http.Request) {
	if !isBridgeLoopback(request.RemoteAddr) {
		bridgeRespond(writer, http.StatusForbidden, "forbidden")
		return
	}
	provided := strings.TrimPrefix(request.Header.Get("Authorization"), "Bearer ")
	if provided == request.Header.Get("Authorization") || len(provided) != len(b.Secret) || subtle.ConstantTimeCompare([]byte(provided), []byte(b.Secret)) != 1 {
		bridgeRespond(writer, http.StatusUnauthorized, "unauthorized")
		return
	}
	switch {
	case request.Method == http.MethodGet && request.URL.Path == "/bridge/stream":
		b.handleStream(writer, request)
	case request.Method == http.MethodPost && request.URL.Path == "/bridge/result":
		b.handleResult(writer, request)
	case request.Method == http.MethodPost && request.URL.Path == "/bridge/pty":
		b.handlePTY(writer, request)
	default:
		bridgeRespond(writer, http.StatusNotFound, "not found")
	}
}

func (b *Bridge) handleStream(writer http.ResponseWriter, request *http.Request) {
	workspacePath, ok := oneQuery(request.URL.Query(), "workspacePath")
	if !ok {
		bridgeRespond(writer, http.StatusBadRequest, "workspacePath is required")
		return
	}
	viewID := ""
	if key := b.pathKey(workspacePath); key != "" {
		b.mu.Lock()
		viewID = b.byKey[key]
		b.mu.Unlock()
	}
	if viewID == "" {
		bridgeRespond(writer, http.StatusForbidden, "workspace is not assigned")
		return
	}
	hostIDs := request.URL.Query()["hostId"]
	if len(hostIDs) != 1 || !validUUID(hostIDs[0]) {
		bridgeRespond(writer, http.StatusBadRequest, "hostId must be a canonical lowercase UUID")
		return
	}
	hostID := hostIDs[0]
	folders := request.URL.Query()["folder"]
	stream := &bridgeStream{messages: make(chan []byte, 16), retired: make(chan struct{})}
	b.mu.Lock()
	if b.closed {
		b.mu.Unlock()
		bridgeRespond(writer, http.StatusServiceUnavailable, "ide bridge closed")
		return
	}
	view := b.views[viewID]
	if view == nil {
		b.mu.Unlock()
		bridgeRespond(writer, http.StatusForbidden, "workspace is not assigned")
		return
	}
	_, knownHost := view.hosts[hostID]
	if hostID != view.hostID && knownHost {
		b.mu.Unlock()
		bridgeRespond(writer, http.StatusGone, "workspace host is retired")
		return
	}
	if !knownHost && len(view.hosts) >= bridgeMaxViewHosts {
		b.mu.Unlock()
		bridgeRespond(writer, http.StatusServiceUnavailable, "workspace host limit reached")
		return
	}
	for id, pending := range b.pending {
		if pending.viewID == viewID && pending.op == "changeDirectory" && pending.hostID == hostID {
			b.resolvePendingLocked(id, BridgeResult{Message: "directory change was not applied"})
			b.mu.Unlock()
			bridgeRespond(writer, http.StatusGone, "workspace host is retired")
			return
		}
	}
	var targetID string
	var target *bridgePending
	for id, pending := range b.pending {
		if pending.viewID == viewID && pending.epoch == view.epoch && pending.op == "changeDirectory" && pending.hostID != hostID && pending.expectedDirectory != "" && matchesBridgeDirectory(folders, pending.expectedDirectory) {
			targetID, target = id, pending
			break
		}
	}
	if !matchesBridgeDirectory(folders, view.directory) && target == nil {
		b.mu.Unlock()
		bridgeRespond(writer, http.StatusGone, "workspace folder is retired")
		return
	}
	previous, previousHost := view.stream, view.hostID
	b.nextStreamID++
	stream.id, stream.hostID = b.nextStreamID, hostID
	view.hosts[hostID] = struct{}{}
	view.hostID, view.stream = hostID, stream
	for id, pending := range b.pending {
		if pending.viewID == viewID && pending.stream != stream && id != targetID {
			message := "ide window closed"
			if pending.op == "changeDirectory" {
				message = "directory change was not applied"
			}
			b.resolvePendingLocked(id, BridgeResult{Message: message})
		}
	}
	if target != nil {
		b.resolvePendingLocked(targetID, BridgeResult{OK: true, Directory: target.expectedDirectory})
	}
	if previous != nil {
		previous.close(previousHost != hostID)
	}
	b.notifyConnectedLocked(viewID)
	b.mu.Unlock()

	writer.Header().Set("Content-Type", "application/x-ndjson")
	writer.Header().Set("Cache-Control", "no-store")
	writer.Header().Set("Connection", "keep-alive")
	writer.WriteHeader(http.StatusOK)
	flusher, ok := writer.(http.Flusher)
	if !ok {
		return
	}
	if _, err := io.WriteString(writer, "{\"type\":\"registered\",\"viewId\":"+strconvQuote(viewID)+"}\n"); err != nil {
		return
	}
	flusher.Flush()
	for {
		select {
		case message := <-stream.messages:
			if _, err := writer.Write(message); err != nil {
				return
			}
			flusher.Flush()
		case <-stream.retired:
			if stream.retiredFrame {
				_, _ = io.WriteString(writer, "{\"type\":\"retired\"}\n")
				flusher.Flush()
			}
			return
		case <-request.Context().Done():
			b.streamDisconnected(viewID, stream)
			return
		}
	}
}

func (b *Bridge) streamDisconnected(viewID string, stream *bridgeStream) {
	b.mu.Lock()
	defer b.mu.Unlock()
	view := b.views[viewID]
	if view != nil && view.stream == stream {
		view.stream = nil
		for _, pending := range b.pending {
			if pending.viewID == viewID && pending.stream == stream && pending.op == "changeDirectory" {
				view.hostID = ""
				break
			}
		}
	}
	for id, pending := range b.pending {
		if pending.viewID == viewID && pending.stream == stream && pending.op != "changeDirectory" {
			b.resolvePendingLocked(id, BridgeResult{Message: "ide window closed"})
		}
	}
}

func (b *Bridge) handleResult(writer http.ResponseWriter, request *http.Request) {
	data, status := bridgeReadBody(writer, request)
	if status != 0 {
		body := bridgeBodyError(status)
		if status == http.StatusRequestEntityTooLarge {
			status = http.StatusBadRequest
		}
		bridgeRespond(writer, status, body)
		return
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		if json.Valid(data) {
			bridgeRespond(writer, http.StatusBadRequest, "invalid result")
		} else {
			bridgeRespond(writer, http.StatusBadRequest, "invalid json")
		}
		return
	}
	if raw == nil {
		bridgeRespond(writer, http.StatusBadRequest, "invalid result")
		return
	}
	var id string
	if rawID, exists := raw["id"]; !exists || string(rawID) == "null" || json.Unmarshal(rawID, &id) != nil {
		bridgeRespond(writer, http.StatusBadRequest, "invalid result")
		return
	}
	b.mu.Lock()
	pending := b.pending[id]
	if pending != nil {
		view := b.views[pending.viewID]
		if view == nil || view.stream != pending.stream {
			b.mu.Unlock()
			bridgeRespond(writer, http.StatusNoContent, "")
			return
		}
	}
	result, err := bridgeResultFromJSON(raw)
	if err != nil {
		b.mu.Unlock()
		bridgeRespond(writer, http.StatusBadRequest, err.Error())
		return
	}
	if pending != nil && pending.op == "changeDirectory" && result.OK && (result.Directory == "" || !matchesBridgeDirectory([]string{result.Directory}, pending.expectedDirectory) || !matchesBridgeDirectory([]string{result.Directory}, b.views[pending.viewID].directory)) {
		b.resolvePendingLocked(id, BridgeResult{Message: "directory change was not applied"})
		b.mu.Unlock()
		bridgeRespond(writer, http.StatusNoContent, "")
		return
	}
	if pending != nil {
		b.resolvePendingLocked(id, result)
	}
	b.mu.Unlock()
	bridgeRespond(writer, http.StatusNoContent, "")
}

func (b *Bridge) handlePTY(writer http.ResponseWriter, request *http.Request) {
	data, status := bridgeReadBody(writer, request)
	if status != 0 {
		body := bridgeBodyError(status)
		if status == http.StatusRequestEntityTooLarge {
			status = http.StatusBadRequest
		}
		bridgeRespond(writer, status, body)
		return
	}
	var event struct {
		WorkspacePath string          `json:"workspacePath"`
		PID           json.RawMessage `json:"pid"`
	}
	if err := json.Unmarshal(data, &event); err != nil {
		bridgeRespond(writer, http.StatusBadRequest, "invalid json")
		return
	}
	pidValue, err := strconv.ParseFloat(string(event.PID), 64)
	if event.WorkspacePath == "" || err != nil || math.IsNaN(pidValue) || math.IsInf(pidValue, 0) || math.Trunc(pidValue) != pidValue || pidValue <= 0 || pidValue > float64(math.MaxInt) {
		bridgeRespond(writer, http.StatusBadRequest, "invalid pty event")
		return
	}
	b.mu.Lock()
	viewID := b.byKey[b.pathKey(event.WorkspacePath)]
	listeners := append([]func(string, int) error(nil), b.ptyListeners...)
	b.mu.Unlock()
	if viewID == "" {
		bridgeRespond(writer, http.StatusForbidden, "workspace is not assigned")
		return
	}
	for _, listener := range listeners {
		if err := listener(viewID, int(pidValue)); err != nil {
			message := "pty registration failed"
			var ideErr *Error
			if errors.As(err, &ideErr) {
				message = ideErr.Message
			}
			bridgeRespond(writer, http.StatusInternalServerError, message)
			return
		}
	}
	bridgeRespond(writer, http.StatusNoContent, "")
}

func (b *Bridge) resolvePending(id string, result BridgeResult) {
	b.mu.Lock()
	b.resolvePendingLocked(id, result)
	b.mu.Unlock()
}

func (b *Bridge) resolvePendingLocked(id string, result BridgeResult) {
	pending := b.pending[id]
	if pending == nil {
		return
	}
	delete(b.pending, id)
	if pending.timer != nil {
		pending.timer.Stop()
	}
	view := b.views[pending.viewID]
	if view == nil || view.epoch != pending.epoch {
		result = BridgeResult{Code: "VIEW_GONE", Message: "view binding changed"}
	} else if result.OK && pending.op == "changeDirectory" && pending.expectedDirectory != "" {
		view.directory = pending.expectedDirectory
	} else if !result.OK && pending.op == "changeDirectory" && result.Code != "DIRTY" && view.stream == pending.stream {
		view.hostID = ""
		stream := view.stream
		view.stream = nil
		stream.close(true)
	}
	pending.result <- result
}

func (b *Bridge) settleViewLocked(viewID string, result BridgeResult) {
	for id, pending := range b.pending {
		if pending.viewID == viewID {
			b.resolvePendingLocked(id, result)
		}
	}
}

func (b *Bridge) rejectWaitersLocked(viewID string, err error) {
	for waiter := range b.waiters[viewID] {
		waiter <- err
	}
	delete(b.waiters, viewID)
}
func (b *Bridge) notifyConnectedLocked(viewID string) {
	for waiter := range b.waiters[viewID] {
		waiter <- nil
	}
	delete(b.waiters, viewID)
}

func (b *Bridge) pathKey(path string) string {
	if path == "" {
		return ""
	}
	resolved, err := filepath.Abs(path)
	if err != nil {
		return ""
	}
	resolved = filepath.Clean(resolved)
	if runtime.GOOS == "windows" {
		resolved = strings.ToLower(resolved)
	}
	return resolved
}

func bridgeResultFromJSON(raw map[string]json.RawMessage) (BridgeResult, error) {
	result := BridgeResult{OK: rawBool(raw, "ok"), Code: rawString(raw, "code"), Message: rawString(raw, "message")}
	if result.Directory = rawString(raw, "directory"); result.Directory != "" && !utf8.ValidString(result.Directory) {
		return BridgeResult{}, errors.New("invalid result")
	}
	result.Clean, _ = rawBoolPointer(raw, "clean")
	result.Complete, _ = rawBoolPointer(raw, "complete")
	result.Omitted, _ = rawIntegerPointer(raw, "omitted")
	result.Restored, _ = rawIntegerPointer(raw, "restored")
	result.Skipped, _ = rawIntegerPointer(raw, "skipped")
	if documents, ok := raw["documents"]; ok {
		var input []json.RawMessage
		if err := json.Unmarshal(documents, &input); err != nil || input == nil || len(input) > bridgeMaxSnapshotDocuments {
			return BridgeResult{}, errors.New("invalid snapshot documents")
		}
		result.Documents = make([]BridgeDocument, 0, len(input))
		var total int64
		for _, encoded := range input {
			var fields map[string]json.RawMessage
			if err := json.Unmarshal(encoded, &fields); err != nil || fields == nil {
				return BridgeResult{}, errors.New("invalid snapshot document")
			}
			var path, text string
			pathRaw, hasPath := fields["path"]
			textRaw, hasText := fields["text"]
			if !hasPath || !hasText || string(pathRaw) == "null" || string(textRaw) == "null" || json.Unmarshal(pathRaw, &path) != nil || json.Unmarshal(textRaw, &text) != nil {
				return BridgeResult{}, errors.New("invalid snapshot document")
			}
			if path == "" || !filepath.IsAbs(path) || len(utf16.Encode([]rune(path))) > bridgeMaxSnapshotPathChars || hasControl(path) {
				return BridgeResult{}, errors.New("invalid snapshot path")
			}
			total += int64(len(text))
			if total > bridgeMaxBodyBytes {
				return BridgeResult{}, errors.New("snapshot exceeds bridge limit")
			}
			result.Documents = append(result.Documents, BridgeDocument{Path: path, Text: text})
		}
	}
	return result, nil
}

func rawString(raw map[string]json.RawMessage, key string) string {
	var value string
	_ = json.Unmarshal(raw[key], &value)
	return value
}
func rawBool(raw map[string]json.RawMessage, key string) bool {
	var value bool
	_ = json.Unmarshal(raw[key], &value)
	return value
}
func rawBoolPointer(raw map[string]json.RawMessage, key string) (*bool, bool) {
	encoded, ok := raw[key]
	if !ok || string(encoded) == "null" {
		return nil, false
	}
	var value bool
	if json.Unmarshal(encoded, &value) != nil {
		return nil, false
	}
	return &value, true
}
func rawIntegerPointer(raw map[string]json.RawMessage, key string) (*int64, bool) {
	encoded, ok := raw[key]
	if !ok || string(encoded) == "null" {
		return nil, false
	}
	value, err := strconv.ParseFloat(string(encoded), 64)
	if err != nil || math.IsNaN(value) || math.IsInf(value, 0) || math.Trunc(value) != value || value < 0 || value > 1<<53-1 {
		return nil, false
	}
	integer := int64(value)
	return &integer, true
}
func oneQuery(values url.Values, key string) (string, bool) {
	items, ok := values[key]
	return func() (string, bool) {
		if !ok || len(items) != 1 {
			return "", false
		}
		return items[0], true
	}()
}
func validUUID(value string) bool { _, err := ValidateViewID(value); return err == nil }
func randomUUID() (string, error) {
	data := make([]byte, 16)
	if _, err := rand.Read(data); err != nil {
		return "", err
	}
	data[6] = data[6]&0x0f | 0x40
	data[8] = data[8]&0x3f | 0x80
	encoded := hex.EncodeToString(data)
	return encoded[:8] + "-" + encoded[8:12] + "-" + encoded[12:16] + "-" + encoded[16:20] + "-" + encoded[20:], nil
}
func strconvQuote(value string) string { data, _ := json.Marshal(value); return string(data) }
func validDirectoryExpectation(directory, expected string) bool {
	return directory != "" && expected != "" && filepath.IsAbs(directory) && filepath.IsAbs(expected) && bridgePathKey(directory) == bridgePathKey(expected)
}
func matchesBridgeDirectory(folders []string, directory string) bool {
	return len(folders) == 1 && filepath.IsAbs(folders[0]) && bridgePathKey(folders[0]) == bridgePathKey(directory)
}
func bridgePathKey(path string) string {
	clean := filepath.Clean(path)
	if runtime.GOOS == "windows" {
		clean = strings.ToLower(clean)
	}
	return clean
}
func isBridgeLoopback(remote string) bool {
	host, _, err := net.SplitHostPort(remote)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}
func bridgeRespond(writer http.ResponseWriter, status int, body string) {
	writer.Header().Set("Content-Type", "text/plain")
	writer.Header().Set("Cache-Control", "no-store")
	writer.WriteHeader(status)
	if body != "" {
		_, _ = io.WriteString(writer, body)
	}
}
func bridgeReadBody(writer http.ResponseWriter, request *http.Request) ([]byte, int) {
	request.Body = http.MaxBytesReader(writer, request.Body, bridgeMaxBodyBytes)
	data, err := io.ReadAll(request.Body)
	if err == nil {
		return data, 0
	}
	var maxErr *http.MaxBytesError
	if errors.As(err, &maxErr) {
		return nil, http.StatusRequestEntityTooLarge
	}
	return nil, http.StatusBadRequest
}
func bridgeBodyError(status int) string {
	if status == http.StatusRequestEntityTooLarge {
		return "bridge request body too large"
	}
	return "invalid request body"
}
