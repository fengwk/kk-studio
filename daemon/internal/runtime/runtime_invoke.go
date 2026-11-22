package runtime

import (
	"context"
	"encoding/json"
	jsonv2 "encoding/json/v2"
	"errors"
	"fmt"
	"strings"
	"time"

	"google.golang.org/protobuf/proto"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

type runningCall struct {
	cancel         context.CancelFunc
	maxOutputBytes int64
	progressError  string
}

func (r *Runtime) handleInvoke(ctx context.Context, e *protocol.Envelope, ready bool) error {
	if e.InvocationId == nil || *e.InvocationId == "" {
		return r.sendError(nil, "invocationId is required")
	}
	id := *e.InvocationId
	start := r.journal.Start(id)
	if start.Err != nil {
		return fmt.Errorf("invocation journal could not record receipt: %w", start.Err)
	}
	if !start.Created {
		return r.replay(start.Entry)
	}
	if !ready {
		return r.rejectCall(id, "INVOKE requires a READY bound connection")
	}
	call, executor, admitted, err := r.admitInvoke(id, e)
	if !admitted {
		return err
	}
	callCtx, active, admitted, err := r.reserveInvoke(ctx, id, call)
	if !admitted {
		return err
	}
	if err := r.sendStarted(id, false); err != nil {
		active.cancel()
		r.releaseInvoke(id)
		return r.rejectCall(id, "control connection send failed")
	}
	capctx := r.capabilityContext(id)
	go r.execute(callCtx, id, call, executor, capctx, call.MaxOutputBytes, active)
	return nil
}

// admitInvoke validates one INVOKE frame and resolves the capability it addresses. Every rejection
// is reported to the client and ends the journal entry it started.
func (r *Runtime) admitInvoke(id string, e *protocol.Envelope) (CapabilityCall, CapabilityExecutor, bool, error) {
	invoke, ok := e.Payload.(*protocol.Envelope_Invoke)
	if !ok || invoke.Invoke == nil {
		return CapabilityCall{}, nil, false, r.rejectCall(id, "invoke payload is required")
	}
	request := invoke.Invoke
	tier, err := mapImageInputTier(request.ImageInputTier)
	if err != nil {
		return CapabilityCall{}, nil, false, r.rejectCall(id, err.Error())
	}
	budget := request.GetMaxOutputBytes()
	if request.MaxOutputBytes == nil || budget <= 0 {
		return CapabilityCall{}, nil, false, r.rejectCall(id, "maxOutputBytes must be a positive integer")
	}
	capabilityID := request.GetCapabilityId()
	if capabilityID == "" {
		return CapabilityCall{}, nil, false, r.rejectCall(id, "capabilityId is required")
	}
	if request.ExpectedFile != nil && capabilityID != "fs.edit" && capabilityID != "fs.write" {
		return CapabilityCall{}, nil, false, r.rejectCall(id, "expectedFile is only valid for fs.edit and fs.write")
	}
	executor, ok := r.options.Capabilities[capabilityID]
	if !ok {
		return CapabilityCall{}, nil, false, r.rejectCall(id, "unknown Environment capability: "+capabilityID)
	}
	arguments := []byte(request.GetArgumentsJson())
	if len(arguments) == 0 || len(arguments) > maxArgumentsBytes {
		return CapabilityCall{}, nil, false, r.rejectCall(id, "arguments must be a JSON object")
	}
	var parsed map[string]json.RawMessage
	if err := jsonv2.Unmarshal(arguments, &parsed); err != nil || parsed == nil {
		return CapabilityCall{}, nil, false, r.rejectCall(id, "arguments must be a JSON object")
	}
	call := CapabilityCall{InvocationID: id, CapabilityID: capabilityID, Arguments: arguments, TimeoutMillis: request.GetTimeoutMillis(), MaxOutputBytes: budget, ImageInputTier: tier}
	if f := request.ExpectedFile; f != nil {
		call.ExpectedFile = &ExpectedFile{Path: f.GetPath(), SHA256: f.GetSha256()}
	}
	return call, executor, true, nil
}

// reserveInvoke applies the admission and concurrency gates, then registers the running call so a
// CANCEL or a shutdown can reach its context.
func (r *Runtime) reserveInvoke(ctx context.Context, id string, call CapabilityCall) (context.Context, *runningCall, bool, error) {
	r.admissionMu.Lock()
	if r.admissionPaused {
		r.admissionMu.Unlock()
		return nil, nil, false, r.rejectCall(id, "environment configuration is switching; new work is not admitted")
	}
	if _, held := r.updateOps.Active(); held {
		r.admissionMu.Unlock()
		return nil, nil, false, r.rejectCall(id, "environment update admission is held")
	}
	r.callsMu.Lock()
	if len(r.calls) >= r.options.MaxConcurrentInvocations {
		r.callsMu.Unlock()
		r.admissionMu.Unlock()
		return nil, nil, false, r.rejectCall(id, "too many concurrent capability invocations")
	}
	millis := call.TimeoutMillis
	if millis < 0 || millis > int64((time.Duration(1<<63-1))/time.Millisecond) {
		r.callsMu.Unlock()
		r.admissionMu.Unlock()
		return nil, nil, false, r.rejectCall(id, "timeoutMillis must be a non-negative duration")
	}
	var callCtx context.Context
	var cancel context.CancelFunc
	if millis > 0 {
		callCtx, cancel = context.WithTimeout(ctx, time.Duration(millis)*time.Millisecond)
	} else {
		callCtx, cancel = context.WithCancel(ctx)
	}
	active := &runningCall{cancel: cancel, maxOutputBytes: call.MaxOutputBytes}
	r.calls[id] = active
	r.wg.Add(1)
	r.callsMu.Unlock()
	r.admissionMu.Unlock()
	return callCtx, active, true, nil
}

// releaseInvoke forgets a call whose STARTED frame could not be delivered.
func (r *Runtime) releaseInvoke(id string) {
	r.callsMu.Lock()
	delete(r.calls, id)
	r.callsMu.Unlock()
	r.wg.Done()
}

// capabilityContext snapshots the applied configuration and the injected execution boundaries.
func (r *Runtime) capabilityContext(id string) CapabilityContext {
	r.mu.RLock()
	snapshot := r.config
	r.mu.RUnlock()
	return CapabilityContext{Config: snapshot, Processes: r.options.Processes, FileChanged: r.options.FileChanged, Resources: r.options.Resources, Progress: func(progress CapabilityResult) error { return r.sendProgress(id, progress) }, ConfirmExecutionExit: func() { r.noteExecutionExit(id) }}
}

func (r *Runtime) execute(ctx context.Context, id string, call CapabilityCall, executor CapabilityExecutor, capctx CapabilityContext, budget int64, active *runningCall) {
	defer r.wg.Done()
	defer active.cancel()
	defer r.publishRequestedExit(id)
	defer func() { r.callsMu.Lock(); delete(r.calls, id); r.callsMu.Unlock() }()
	result, err := invokeCapability(ctx, call, capctx, executor)
	r.callsMu.Lock()
	progressError := active.progressError
	r.callsMu.Unlock()
	if progressError != "" {
		_ = r.finish(id, protocol.MessageType_FAILED, &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: stringPtr(progressError)}}, InvocationFailed)
		return
	}
	if err != nil {
		if ctx.Err() != nil {
			reason := "cancelled"
			if errors.Is(ctx.Err(), context.DeadlineExceeded) {
				reason = fmt.Sprintf("capability execution timed out after %dms", call.TimeoutMillis)
				_ = r.finish(id, protocol.MessageType_FAILED, &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: stringPtr(reason)}}, InvocationFailed)
			} else {
				_ = r.finish(id, protocol.MessageType_CANCELLED, &protocol.Envelope_Cancelled{Cancelled: &protocol.Cancelled{Reason: stringPtr(reason)}}, InvocationCancelled)
			}
		} else {
			_ = r.finish(id, protocol.MessageType_FAILED, &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: stringPtr(safeCapabilityError(err))}}, InvocationFailed)
		}
		return
	}
	if message, failed := failedCapabilityResultMessage(result); failed {
		_ = r.finish(id, protocol.MessageType_FAILED, &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: stringPtr(message)}}, InvocationFailed)
		return
	}
	encoded, encodeErr := r.encodeResult(ctx, id, result, budget, false)
	if encodeErr != nil {
		var uploadFailure *resourceUploadFailure
		if errors.As(encodeErr, &uploadFailure) && !uploadFailure.cleanupConfirmed {
			r.callsMu.Lock()
			r.exitCleanupUnconfirmed[id] = true
			r.callsMu.Unlock()
		}
		_ = r.finish(id, protocol.MessageType_FAILED, &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: stringPtr(encodeErr.Error())}}, InvocationFailed)
		return
	}
	if err := r.finish(id, protocol.MessageType_COMPLETED, &protocol.Envelope_Completed{Completed: &protocol.Completed{Result: encoded}}, InvocationCompleted); err != nil {
		return
	}
}

func failedCapabilityResultMessage(result CapabilityResult) (string, bool) {
	if !result.Error {
		return "", false
	}
	var details struct {
		Code string `json:"code"`
	}
	if err := json.Unmarshal(result.Details, &details); err != nil || details.Code != "FAILED" {
		return "", false
	}
	for _, content := range result.Contents {
		if content.Type == "text" && content.Text != "" {
			return content.Text, true
		}
	}
	return "capability execution failed", true
}

func (r *Runtime) encodeResult(ctx context.Context, id string, result CapabilityResult, budget int64, progress bool) (*protocol.CapabilityResult, error) {
	if result.CallID != id {
		return nil, errors.New("capability result callId does not match invocationId")
	}
	details := result.Details
	if len(details) == 0 {
		details = []byte("{}")
	}
	if len(details) > maxDetailsBytes {
		return nil, errors.New("capability details JSON exceeds 1 MiB")
	}
	var parsed map[string]json.RawMessage
	if err := jsonv2.Unmarshal(details, &parsed); err != nil || parsed == nil {
		return nil, errors.New("capability details is not a JSON object")
	}
	if len(result.Contents) > maxResultContents {
		return nil, errors.New("capability result contains too many content items")
	}
	if err := checkResultBudget(result.Contents, budget, progress); err != nil {
		return nil, err
	}
	out := &protocol.CapabilityResult{CallId: stringPtr(result.CallID), Error: boolPtr(result.Error), DetailsJson: stringPtr(string(details))}
	for _, content := range result.Contents {
		item, err := r.encodeContent(ctx, id, content, progress)
		if err != nil {
			return nil, err
		}
		out.Contents = append(out.Contents, item)
	}
	return out, nil
}

// checkResultBudget rejects results whose payload and already emitted progress exceed the
// invocation's declared output budget.
func checkResultBudget(contents []ResultContent, budget int64, progress bool) error {
	var total int64
	for _, content := range contents {
		var size int64
		switch content.Type {
		case "binary":
			size = int64(len(content.Bytes))
		case "resource":
			size = content.Size
			if size < 0 {
				return errors.New("resource size must not be negative")
			}
		case "text", "json":
		default:
			return errors.New("capability result content type is invalid")
		}
		if progress && content.Type != "text" && content.Type != "json" {
			return errors.New("progress does not allow binary content")
		}
		if size > budget-total {
			actual := size
			if size <= maxSignedInt64-total {
				actual += total
			} else {
				actual = maxSignedInt64
			}
			return errors.New(toolOutputExceededMessage(actual, budget))
		}
		total += size
	}
	return nil
}

// encodeContent converts one result item to its wire form, uploading binary payloads and
// reporting a cleanup fact the runtime must remember for the execution-exit frame.
func (r *Runtime) encodeContent(ctx context.Context, id string, content ResultContent, progress bool) (*protocol.Content, error) {
	switch content.Type {
	case "text":
		return &protocol.Content{Body: &protocol.Content_Text{Text: &protocol.TextContent{Text: stringPtr(content.Text)}}}, nil
	case "json":
		if len(content.JSON) > maxContentJSONBytes {
			return nil, errors.New("capability JSON content exceeds 1 MiB")
		}
		var v any
		if err := jsonv2.Unmarshal(content.JSON, &v); err != nil {
			return nil, errors.New("capability JSON content is invalid")
		}
		return &protocol.Content{Body: &protocol.Content_Json{Json: &protocol.JsonContent{Json: stringPtr(string(content.JSON))}}}, nil
	case "binary":
		return r.uploadContent(ctx, id, content, progress)
	case "resource":
		return &protocol.Content{Body: &protocol.Content_Resource{Resource: &protocol.ResourceContent{UploadId: stringPtr(content.UploadID), MediaType: stringPtr(content.MediaType), Name: content.Name, Size: int64Ptr(content.Size), Sha256: stringPtr(content.SHA256), Preview: stringPtr(content.Preview)}}}, nil
	default:
		return nil, errors.New("capability result content type is invalid")
	}
}

func (r *Runtime) uploadContent(ctx context.Context, id string, content ResultContent, progress bool) (*protocol.Content, error) {
	if progress {
		return nil, errors.New("progress does not allow binary content")
	}
	if r.options.Resources == nil {
		return nil, errors.New("resource upload is not configured")
	}
	uploaded, err := r.options.Resources.Upload(ctx, id, content)
	if err != nil {
		var uploadErr *ResourceUploadError
		cleanupConfirmed := errors.As(err, &uploadErr) && uploadErr.CleanupConfirmed
		return nil, &resourceUploadFailure{cleanupConfirmed: cleanupConfirmed}
	}
	return &protocol.Content{Body: &protocol.Content_Resource{Resource: &protocol.ResourceContent{UploadId: stringPtr(uploaded.UploadID), MediaType: stringPtr(uploaded.MediaType), Name: uploaded.Name, Size: int64Ptr(uploaded.Size), Sha256: stringPtr(uploaded.SHA256), Preview: stringPtr(uploaded.Preview)}}}, nil
}

func (r *Runtime) handleCancel(e *protocol.Envelope) error {
	if e.InvocationId == nil {
		return nil
	}
	id := *e.InvocationId
	r.callsMu.Lock()
	active := r.calls[id]
	r.callsMu.Unlock()
	if active != nil {
		active.cancel()
		return nil
	}
	entry, ok := r.journal.Get(id)
	if ok && entry.State != InvocationRunning {
		r.mu.RLock()
		ready := r.ready
		r.mu.RUnlock()
		if ready {
			return r.replay(entry)
		}
	}
	return nil
}

func (r *Runtime) handleRecover(e *protocol.Envelope) error {
	if e.InvocationId == nil {
		return nil
	}
	id := *e.InvocationId
	r.mu.RLock()
	ready := r.ready
	r.mu.RUnlock()
	entry, ok := r.journal.Get(id)
	if !ready {
		if ok && entry.State == InvocationRunning {
			r.callsMu.Lock()
			_, active := r.calls[id]
			r.callsMu.Unlock()
			if active {
				return r.replay(entry)
			}
		}
		if err := r.sendBoundError(nil, "RECOVER requires a READY bound connection; reconnect and retry recovery"); err != nil {
			return err
		}
		return errors.New("RECOVER requires a READY bound connection")
	}
	if !ok {
		return r.sendRecoverNotFound(id)
	}
	return r.replay(entry)
}

func (r *Runtime) handleExecutionExitAck(e *protocol.Envelope) error {
	r.mu.RLock()
	ready := r.ready
	r.mu.RUnlock()
	if !ready {
		return errors.New("EXECUTION_EXIT_ACK requires a READY bound connection")
	}
	if e.InvocationId == nil || *e.InvocationId == "" {
		return errors.New("EXECUTION_EXIT_ACK invocationId is required")
	}
	_, err := r.journal.AcknowledgeExit(*e.InvocationId)
	return err
}

func (r *Runtime) replay(entry JournalEntry) error {
	if entry.Recovered && (entry.State == InvocationRunning || len(entry.Terminal) == 0) {
		if err := r.sendRecoverNotFound(entry.InvocationID); err != nil {
			return err
		}
		if entry.Exited && !entry.Acknowledged {
			return r.sendExecutionExited(entry.InvocationID)
		}
		return nil
	}
	if entry.State == InvocationRunning {
		return r.sendStarted(entry.InvocationID, true)
	}
	var frame protocol.Envelope
	if err := proto.Unmarshal(entry.Terminal, &frame); err != nil {
		return errors.New("journal terminal frame is invalid")
	}
	if err := r.sendBound(&frame, false); err != nil {
		return err
	}
	if entry.Exited {
		return r.sendExecutionExited(entry.InvocationID)
	}
	return nil
}

func (r *Runtime) rejectCall(id, msg string) error {
	frame := &protocol.Envelope{MessageType: protocol.MessageType_FAILED, EnvironmentId: stringPtr(r.options.EnvironmentID), InvocationId: stringPtr(id), Payload: &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: stringPtr(msg)}}}
	data, err := proto.Marshal(frame)
	if err != nil {
		return err
	}
	completed, err := r.journal.CompleteWithExitDurable(id, InvocationFailed, data, true)
	if err != nil {
		return err
	}
	if !completed {
		return nil
	}
	if err := r.sendBound(frame, false); err != nil {
		return err
	}
	return r.sendExecutionExited(id)
}

func (r *Runtime) finish(id string, kind protocol.MessageType, payload any, state InvocationState) error {
	// A delivered terminal ends protected execution immediately; update admission must
	// never observe a call whose result the client has already received.
	r.callsMu.Lock()
	delete(r.calls, id)
	r.callsMu.Unlock()
	frame := &protocol.Envelope{MessageType: kind, EnvironmentId: stringPtr(r.options.EnvironmentID), InvocationId: stringPtr(id)}
	switch value := payload.(type) {
	case *protocol.Envelope_Failed:
		frame.Payload = value
	case *protocol.Envelope_Cancelled:
		frame.Payload = value
	case *protocol.Envelope_Completed:
		frame.Payload = value
	default:
		return errors.New("unsupported terminal payload")
	}
	data, err := proto.Marshal(frame)
	if err != nil {
		return err
	}
	completed, err := r.journal.CompleteDurable(id, state, data, false)
	if err != nil {
		return err
	}
	if !completed {
		return nil
	}
	return r.sendBound(frame, false)
}

func (r *Runtime) sendStarted(id string, replayed bool) error {
	started := &protocol.Started{}
	if replayed {
		started.Replayed = boolPtr(true)
	}
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_STARTED, InvocationId: stringPtr(id), Payload: &protocol.Envelope_Started{Started: started}}, false)
}

func (r *Runtime) sendProgress(id string, result CapabilityResult) error {
	entry, ok := r.journal.Get(id)
	if !ok || entry.State != InvocationRunning {
		return nil
	}
	r.callsMu.Lock()
	active := r.calls[id]
	r.callsMu.Unlock()
	if active == nil {
		return nil
	}
	encoded, err := r.encodeResult(context.Background(), id, result, active.maxOutputBytes, true)
	if err != nil {
		r.callsMu.Lock()
		active.progressError = err.Error()
		r.callsMu.Unlock()
		active.cancel()
		return err
	}
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_PROGRESS, InvocationId: stringPtr(id), Payload: &protocol.Envelope_Progress{Progress: &protocol.Progress{Result: encoded}}}, false)
}

func (r *Runtime) sendRecoverNotFound(id string) error {
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_RECOVER_NOT_FOUND, InvocationId: stringPtr(id), Payload: &protocol.Envelope_RecoverNotFound{RecoverNotFound: &protocol.RecoverNotFound{}}}, false)
}

func (r *Runtime) sendExecutionExited(id string) error {
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_EXECUTION_EXITED, InvocationId: stringPtr(id), Payload: &protocol.Envelope_ExecutionExited{ExecutionExited: &protocol.ExecutionExited{}}}, false)
}

func (r *Runtime) sendError(id *string, message string) error {
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_ERROR, InvocationId: id, Payload: &protocol.Envelope_Error{Error: &protocol.Error{Message: stringPtr(message)}}}, true)
}

func (r *Runtime) sendBoundError(id *string, message string) error {
	return r.sendBound(&protocol.Envelope{MessageType: protocol.MessageType_ERROR, InvocationId: id, Payload: &protocol.Envelope_Error{Error: &protocol.Error{Message: stringPtr(message)}}}, false)
}

func (r *Runtime) noteExecutionExit(id string) {
	r.callsMu.Lock()
	r.exitRequested[id] = true
	r.callsMu.Unlock()
}

func (r *Runtime) publishRequestedExit(id string) {
	r.callsMu.Lock()
	requested := r.exitRequested[id]
	cleanupUnconfirmed := r.exitCleanupUnconfirmed[id]
	delete(r.exitRequested, id)
	delete(r.exitCleanupUnconfirmed, id)
	r.callsMu.Unlock()
	if requested && !cleanupUnconfirmed {
		confirmed, err := r.journal.ConfirmExitDurable(id)
		if err != nil {
			if r.options.ReportError != nil {
				r.options.ReportError(fmt.Errorf("invocation exit proof could not be persisted: %w", err))
			}
			return
		}
		if confirmed {
			_ = r.sendExecutionExited(id)
		}
	}
}

func (r *Runtime) cancelAndWait() {
	r.callsMu.Lock()
	for _, call := range r.calls {
		call.cancel()
	}
	r.callsMu.Unlock()
	r.wg.Wait()
}

func invokeCapability(ctx context.Context, call CapabilityCall, capabilityContext CapabilityContext, executor CapabilityExecutor) (result CapabilityResult, err error) {
	defer func() {
		if recover() != nil {
			result = CapabilityResult{}
			err = errors.New("capability execution failed")
		}
	}()
	return executor(ctx, call, capabilityContext)
}

func safeCapabilityError(err error) string {
	if err == nil || strings.TrimSpace(err.Error()) == "" {
		return "capability execution failed"
	}
	return err.Error()
}

func mapImageInputTier(value *protocol.ImageInputTier) (ImageInputTier, error) {
	if value == nil || *value == protocol.ImageInputTier_IMAGE_INPUT_TIER_UNSPECIFIED {
		return "", errors.New("imageInputTier is required")
	}
	switch *value {
	case protocol.ImageInputTier_P720:
		return ImageInputTierP720, nil
	case protocol.ImageInputTier_P1080:
		return ImageInputTierP1080, nil
	case protocol.ImageInputTier_ORIGINAL:
		return ImageInputTierOriginal, nil
	default:
		return "", errors.New("imageInputTier is invalid")
	}
}
