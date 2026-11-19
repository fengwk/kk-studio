package ide

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
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
	sent      bool             // true once the bridge stream accepts the command frame
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
	workspacePath       string
	epoch               int64
	directory           string
	latestDirectoryOpID string
	hosts               map[string]struct{}
	hostID              string
	stream              *bridgeStream
}

type bridgeStream struct {
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
	notified          bool
}

// Bridge is the authenticated, loopback-only HTTP/NDJSON connection to extension hosts.
type Bridge struct {
	URL      string
	Secret   string
	server   *http.Server
	listener net.Listener

	mu                    sync.Mutex
	views                 map[string]*bridgeView
	pending               map[string]*bridgePending
	waiters               map[string]map[chan error]struct{}
	ptyListeners          []func(string, int) error
	onDirectoryReconciled func(string, int64, string, string)
	closed                bool
	closeDone             chan struct{}
	closeErr              error
	commandTimeout        time.Duration
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
		views:   make(map[string]*bridgeView),
		pending: make(map[string]*bridgePending), waiters: make(map[string]map[chan error]struct{}),
		closeDone: make(chan struct{}), commandTimeout: bridgeCommandTimeout,
	}
	bridge.server = &http.Server{Handler: http.HandlerFunc(bridge.handle)}
	go func() { _ = bridge.server.Serve(listener) }()
	return bridge, nil
}

// Assign binds a managed view identity and confirmed directory to a binding epoch.
func (b *Bridge) Assign(viewID, workspacePath string, epoch int64, directory string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if old := b.views[viewID]; old != nil {
		if old.stream != nil {
			old.stream.close(true)
		}
		b.rejectWaitersLocked(viewID, NewError("view binding changed", "VIEW_GONE"))
		b.settleViewLocked(viewID, BridgeResult{Code: "VIEW_GONE", Message: "view binding changed"})
	}
	hosts := make(map[string]struct{})
	if old := b.views[viewID]; old != nil {
		hosts = old.hosts
	}
	b.views[viewID] = &bridgeView{workspacePath: workspacePath, epoch: epoch, directory: directory, hosts: hosts}
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

// OnDirectoryReconciled receives successful late confirmations after the caller stopped waiting.
func (b *Bridge) OnDirectoryReconciled(listener func(string, int64, string, string)) {
	b.mu.Lock()
	b.onDirectoryReconciled = listener
	b.mu.Unlock()
}

// IsCurrentDirectoryOperation reports whether an operation still owns the confirmed bridge directory.
func (b *Bridge) IsCurrentDirectoryOperation(viewID string, epoch int64, operationID, directory string) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	view := b.views[viewID]
	return view != nil && view.epoch == epoch && view.latestDirectoryOpID == operationID && view.directory == directory
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
// The internal sent flag records whether the stream accepted the frame.
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
	if command.Op == "refreshExplorer" && view.directory != options.ExpectedDirectory {
		b.mu.Unlock()
		return BridgeResult{}, NewError("view directory changed", "VIEW_GONE")
	}
	if err := ctx.Err(); err != nil {
		b.mu.Unlock()
		code := "BUSY"
		if command.Op == "closeView" {
			code = "CANCELLED"
		}
		return BridgeResult{Code: code, Message: "ide request was cancelled"}, nil
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
	b.pending[id] = pending
	stream := view.stream
	b.mu.Unlock()
	delivered := false
	select {
	case stream.messages <- append(encoded, '\n'):
		delivered = true
	case <-stream.retired:
		b.resolvePending(id, BridgeResult{Code: "VIEW_GONE", Message: "ide window was closed"})
	case <-ctx.Done():
		code := "BUSY"
		if command.Op == "closeView" {
			code = "CANCELLED"
		}
		b.resolvePending(id, BridgeResult{Code: code, Message: "ide request was cancelled"})
	}
	if !delivered {
		return <-pending.result, nil
	}
	if command.Op != "closeView" || command.Discard {
		b.mu.Lock()
		if b.pending[id] == pending {
			pending.timer = time.AfterFunc(b.commandTimeout, func() {
				if command.Op == "changeDirectory" {
					b.markUnknown(id)
				} else {
					b.resolvePending(id, BridgeResult{Code: "OUTCOME_UNKNOWN", Message: "ide window did not respond"})
				}
			})
		}
		b.mu.Unlock()
	}
	select {
	case result := <-pending.result:
		result.sent = true
		return result, nil
	case <-ctx.Done():
		if command.Op == "changeDirectory" {
			b.markUnknown(id)
			result := <-pending.result
			result.sent = true
			return result, nil
		}
		b.resolvePending(id, BridgeResult{Code: "OUTCOME_UNKNOWN", Message: "ide request outcome is unknown"})
		result := <-pending.result
		result.sent = true
		return result, nil
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
		if !pending.notified {
			pending.result <- BridgeResult{Message: "ide bridge closed"}
		}
		delete(b.pending, id)
	}
	for viewID := range b.waiters {
		b.rejectWaitersLocked(viewID, NewError("ide bridge closed", "UNAVAILABLE"))
	}
	b.views = make(map[string]*bridgeView)
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
	viewID, ok := oneQuery(request.URL.Query(), "viewId")
	if !ok || !validUUID(viewID) {
		bridgeRespond(writer, http.StatusBadRequest, "viewId must be a canonical lowercase UUID")
		return
	}
	epochValues := request.URL.Query()["bindingEpoch"]
	if len(epochValues) != 1 {
		bridgeRespond(writer, http.StatusBadRequest, "bindingEpoch is required")
		return
	}
	epoch, err := parseBridgeEpoch(epochValues[0])
	if err != nil {
		bridgeRespond(writer, http.StatusBadRequest, "bindingEpoch must be a positive integer")
		return
	}
	hostIDs := request.URL.Query()["hostId"]
	if len(hostIDs) != 1 || !validUUID(hostIDs[0]) {
		bridgeRespond(writer, http.StatusBadRequest, "hostId must be a canonical lowercase UUID")
		return
	}
	stream, status, message := b.admitStream(viewID, epoch, hostIDs[0], request.URL.Query()["folder"])
	if stream == nil {
		bridgeRespond(writer, status, message)
		return
	}
	b.serveStream(writer, request, viewID, stream)
}

// admitStream registers the host stream against the managed view identity, or rejects it.
func (b *Bridge) admitStream(viewID string, epoch int64, hostID string, folders []string) (*bridgeStream, int, string) {
	stream := &bridgeStream{messages: make(chan []byte, 16), retired: make(chan struct{})}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed {
		return nil, http.StatusServiceUnavailable, "ide bridge closed"
	}
	view := b.views[viewID]
	if view == nil {
		return nil, http.StatusForbidden, "view is not assigned"
	}
	if view.epoch != epoch {
		slog.Warn("IDE bridge rejected retired binding", "viewId", viewID, "bindingEpoch", epoch, "hostId", hostID, "reason", "stale_epoch")
		return nil, http.StatusGone, "view binding is retired"
	}
	_, knownHost := view.hosts[hostID]
	if hostID != view.hostID && knownHost {
		return nil, http.StatusGone, "workspace host is retired"
	}
	if !knownHost && len(view.hosts) >= bridgeMaxViewHosts {
		return nil, http.StatusServiceUnavailable, "workspace host limit reached"
	}
	for id, pending := range b.pending {
		if pending.viewID == viewID && pending.op == "changeDirectory" && pending.hostID == hostID {
			b.resolvePendingLocked(id, BridgeResult{Message: "directory change was not applied"})
			return nil, http.StatusGone, "workspace host is retired"
		}
	}
	targetID, targetDirectory := b.reclaimDirectoryChangeLocked(viewID, view.epoch, hostID, folders)
	if !matchesBridgeDirectory(folders, view.directory) && targetID == "" {
		return nil, http.StatusGone, "workspace folder is retired"
	}
	b.installStreamLocked(viewID, view, stream, hostID, targetID, targetDirectory)
	return stream, 0, ""
}

// reclaimDirectoryChangeLocked finds the pending directory change this host now satisfies.
func (b *Bridge) reclaimDirectoryChangeLocked(viewID string, epoch int64, hostID string, folders []string) (string, string) {
	for id, pending := range b.pending {
		if pending.viewID == viewID && pending.epoch == epoch && pending.op == "changeDirectory" && pending.hostID != hostID && pending.expectedDirectory != "" && matchesBridgeDirectory(folders, pending.expectedDirectory) {
			return id, pending.expectedDirectory
		}
	}
	return "", ""
}

// installStreamLocked replaces the view's host stream and settles its superseded requests.
func (b *Bridge) installStreamLocked(viewID string, view *bridgeView, stream *bridgeStream, hostID, targetID, targetDirectory string) {
	previous, previousHost := view.stream, view.hostID
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
	if targetID != "" {
		b.resolvePendingLocked(targetID, BridgeResult{OK: true, Directory: targetDirectory})
	}
	if previous != nil {
		previous.close(previousHost != hostID)
	}
	b.notifyConnectedLocked(viewID)
}

// serveStream streams NDJSON frames until the window retires or disconnects.
// Every handler exit detaches its stream if it is still the view's active connection.
func (b *Bridge) serveStream(writer http.ResponseWriter, request *http.Request, viewID string, stream *bridgeStream) {
	defer b.streamDisconnected(viewID, stream)
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
	var identity struct {
		ID           string `json:"id"`
		ViewID       string `json:"viewId"`
		BindingEpoch string `json:"bindingEpoch"`
		HostID       string `json:"hostId"`
	}
	if err := json.Unmarshal(data, &identity); err != nil || identity.ID == "" || !validUUID(identity.ViewID) || !validUUID(identity.HostID) {
		bridgeRespond(writer, http.StatusBadRequest, "invalid result identity")
		return
	}
	epoch, err := parseBridgeEpoch(identity.BindingEpoch)
	if err != nil {
		bridgeRespond(writer, http.StatusBadRequest, "invalid result identity")
		return
	}
	b.mu.Lock()
	view := b.views[identity.ViewID]
	if view == nil || view.epoch != epoch || view.hostID != identity.HostID || view.stream == nil {
		b.mu.Unlock()
		bridgeRespond(writer, http.StatusNoContent, "")
		return
	}
	pending := b.pending[identity.ID]
	if pending != nil && (pending.viewID != identity.ViewID || pending.epoch != epoch || pending.hostID != identity.HostID || pending.stream != view.stream) {
		b.mu.Unlock()
		bridgeRespond(writer, http.StatusNoContent, "")
		return
	}
	result, err := bridgeResultFromJSON(raw)
	if err != nil {
		b.mu.Unlock()
		bridgeRespond(writer, http.StatusBadRequest, err.Error())
		return
	}
	if pending != nil && pending.op == "changeDirectory" && result.OK && (result.Directory == "" || !matchesBridgeDirectory([]string{result.Directory}, pending.expectedDirectory) || !matchesBridgeDirectory([]string{result.Directory}, b.views[pending.viewID].directory)) {
		b.resolvePendingLocked(identity.ID, BridgeResult{Message: "directory change was not applied"})
		b.mu.Unlock()
		bridgeRespond(writer, http.StatusNoContent, "")
		return
	}
	if pending != nil {
		b.resolvePendingLocked(identity.ID, result)
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
		ViewID       string          `json:"viewId"`
		BindingEpoch string          `json:"bindingEpoch"`
		HostID       string          `json:"hostId"`
		PID          json.RawMessage `json:"pid"`
	}
	if err := json.Unmarshal(data, &event); err != nil {
		bridgeRespond(writer, http.StatusBadRequest, "invalid json")
		return
	}
	epoch, epochErr := parseBridgeEpoch(event.BindingEpoch)
	pidValue, err := strconv.ParseFloat(string(event.PID), 64)
	if !validUUID(event.ViewID) || epochErr != nil || !validUUID(event.HostID) || err != nil || math.IsNaN(pidValue) || math.IsInf(pidValue, 0) || math.Trunc(pidValue) != pidValue || pidValue <= 0 || pidValue > float64(math.MaxInt) {
		bridgeRespond(writer, http.StatusBadRequest, "invalid pty event")
		return
	}
	b.mu.Lock()
	view := b.views[event.ViewID]
	viewID := ""
	if view != nil && view.epoch == epoch && view.hostID == event.HostID && view.stream != nil {
		viewID = event.ViewID
	}
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

func (b *Bridge) markUnknown(id string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	pending := b.pending[id]
	if pending == nil || pending.notified {
		return
	}
	pending.notified = true
	slog.Warn("IDE bridge directory outcome unknown", "viewId", pending.viewID, "bindingEpoch", pending.epoch, "hostId", pending.hostID, "reason", "caller_stopped_waiting", "operationId", id)
	pending.result <- BridgeResult{Code: "OUTCOME_UNKNOWN", Message: "directory change outcome is unknown"}
	if pending.timer != nil {
		pending.timer.Stop()
		pending.timer = nil
	}
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
		view.latestDirectoryOpID = id
		if pending.notified {
			slog.Info("IDE bridge directory outcome reconciled", "viewId", pending.viewID, "bindingEpoch", pending.epoch, "hostId", view.hostID, "reason", "target_host_confirmed", "operationId", id)
		}
		if pending.notified && b.onDirectoryReconciled != nil {
			listener := b.onDirectoryReconciled
			go listener(pending.viewID, pending.epoch, id, pending.expectedDirectory)
		}
	} else if !result.OK && result.Code != "OUTCOME_UNKNOWN" && pending.op == "changeDirectory" && result.Code != "DIRTY" && view.stream == pending.stream {
		view.hostID = ""
		stream := view.stream
		view.stream = nil
		stream.close(true)
	}
	if !pending.notified {
		pending.result <- result
	}
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
func parseBridgeEpoch(value string) (int64, error) {
	epoch, err := strconv.ParseInt(value, 10, 64)
	if err != nil || epoch <= 0 || strconv.FormatInt(epoch, 10) != value {
		return 0, errors.New("invalid binding epoch")
	}
	return epoch, nil
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
	if resolved, err := filepath.EvalSymlinks(clean); err == nil {
		clean = resolved
	}
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
