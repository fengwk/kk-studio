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

func (r *Runtime) handleInvoke(ctx context.Context, e *protocol.Envelope) error {
	if e.InvocationId == nil || *e.InvocationId == "" {
		return r.sendError(nil, "invocationId is required")
	}
	id := *e.InvocationId
	start := r.journal.Start(id)
	if !start.Created {
		return r.replay(start.Entry)
	}
	invoke, ok := e.Payload.(*protocol.Envelope_Invoke)
	if !ok || invoke.Invoke == nil {
		return r.rejectCall(id, "invoke payload is required")
	}
	tier, tierErr := mapImageInputTier(invoke.Invoke.ImageInputTier)
	if tierErr != nil {
		return r.rejectCall(id, tierErr.Error())
	}
	budget := invoke.Invoke.GetMaxOutputBytes()
	if invoke.Invoke.MaxOutputBytes == nil || budget <= 0 {
		return r.rejectCall(id, "maxOutputBytes must be a positive integer")
	}
	capabilityID := invoke.Invoke.GetCapabilityId()
	if capabilityID == "" {
		return r.rejectCall(id, "capabilityId is required")
	}
	if invoke.Invoke.ExpectedFile != nil && capabilityID != "fs.edit" && capabilityID != "fs.write" {
		return r.rejectCall(id, "expectedFile is only valid for fs.edit and fs.write")
	}
	executor, ok := r.options.Capabilities[capabilityID]
	if !ok {
		return r.rejectCall(id, "unknown Environment capability: "+capabilityID)
	}
	arguments := []byte(invoke.Invoke.GetArgumentsJson())
	if len(arguments) == 0 || len(arguments) > maxArgumentsBytes {
		return r.rejectCall(id, "arguments must be a JSON object")
	}
	var parsed map[string]json.RawMessage
	if err := jsonv2.Unmarshal(arguments, &parsed); err != nil || parsed == nil {
		return r.rejectCall(id, "arguments must be a JSON object")
	}
	r.admissionMu.Lock()
	if r.admissionPaused {
		r.admissionMu.Unlock()
		return r.rejectCall(id, "environment configuration is switching; new work is not admitted")
	}
	if _, held := r.updateOps.Active(); held {
		r.admissionMu.Unlock()
		return r.rejectCall(id, "environment update admission is held")
	}
	r.callsMu.Lock()
	if len(r.calls) >= r.options.MaxConcurrentInvocations {
		r.callsMu.Unlock()
		r.admissionMu.Unlock()
		return r.rejectCall(id, "too many concurrent capability invocations")
	}
	millis := invoke.Invoke.GetTimeoutMillis()
	if millis < 0 || millis > int64((time.Duration(1<<63-1))/time.Millisecond) {
		r.callsMu.Unlock()
		r.admissionMu.Unlock()
		return r.rejectCall(id, "timeoutMillis must be a non-negative duration")
	}
	var callCtx context.Context
	var cancel context.CancelFunc
	if millis > 0 {
		callCtx, cancel = context.WithTimeout(ctx, time.Duration(millis)*time.Millisecond)
	} else {
		callCtx, cancel = context.WithCancel(ctx)
	}
	active := &runningCall{cancel: cancel, maxOutputBytes: budget}
	r.calls[id] = active
	r.wg.Add(1)
	r.callsMu.Unlock()
	r.admissionMu.Unlock()
	if err := r.sendStarted(id, false); err != nil {
		cancel()
		r.callsMu.Lock()
		delete(r.calls, id)
		r.callsMu.Unlock()
		r.wg.Done()
		return r.rejectCall(id, "control connection send failed")
	}
	r.mu.RLock()
	snapshot := r.config
	r.mu.RUnlock()
	call := CapabilityCall{InvocationID: id, CapabilityID: capabilityID, Arguments: arguments, TimeoutMillis: invoke.Invoke.GetTimeoutMillis(), MaxOutputBytes: budget, ImageInputTier: tier}
	if f := invoke.Invoke.ExpectedFile; f != nil {
		call.ExpectedFile = &ExpectedFile{Path: f.GetPath(), SHA256: f.GetSha256()}
	}
	resources := r.options.Resources
	capctx := CapabilityContext{Config: snapshot, Processes: r.options.Processes, FileChanged: r.options.FileChanged, Resources: resources, Progress: func(progress CapabilityResult) error { return r.sendProgress(id, progress) }, ConfirmExecutionExit: func() { r.noteExecutionExit(id) }}
	go r.execute(callCtx, id, call, executor, capctx, budget, active)
	return nil
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
	if !progress && budget <= 0 {
		return nil, errors.New("maximumResourceBytes must be positive")
	}
	var total int64
	for _, content := range result.Contents {
		var size int64
		switch content.Type {
		case "binary":
			size = int64(len(content.Bytes))
		case "resource":
			size = content.Size
			if size < 0 {
				return nil, errors.New("resource size must not be negative")
			}
		case "text", "json":
		default:
			return nil, errors.New("capability result content type is invalid")
		}
		if progress && content.Type != "text" && content.Type != "json" {
			return nil, errors.New("progress does not allow binary content")
		}
		if size > budget-total {
			actual := size
			if size <= maxSignedInt64-total {
				actual += total
			} else {
				actual = maxSignedInt64
			}
			return nil, errors.New(toolOutputExceededMessage(actual, budget))
		}
		total += size
	}
	out := &protocol.CapabilityResult{CallId: stringPtr(result.CallID), Error: boolPtr(result.Error), DetailsJson: stringPtr(string(details))}
	for _, content := range result.Contents {
		item := &protocol.Content{}
		switch content.Type {
		case "text":
			item.Body = &protocol.Content_Text{Text: &protocol.TextContent{Text: stringPtr(content.Text)}}
		case "json":
			if len(content.JSON) > maxContentJSONBytes {
				return nil, errors.New("capability JSON content exceeds 1 MiB")
			}
			var v any
			if err := jsonv2.Unmarshal(content.JSON, &v); err != nil {
				return nil, errors.New("capability JSON content is invalid")
			}
			item.Body = &protocol.Content_Json{Json: &protocol.JsonContent{Json: stringPtr(string(content.JSON))}}
		case "binary":
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
			item.Body = &protocol.Content_Resource{Resource: &protocol.ResourceContent{UploadId: stringPtr(uploaded.UploadID), MediaType: stringPtr(uploaded.MediaType), Name: uploaded.Name, Size: int64Ptr(uploaded.Size), Sha256: stringPtr(uploaded.SHA256), Preview: stringPtr(uploaded.Preview)}}
		case "resource":
			item.Body = &protocol.Content_Resource{Resource: &protocol.ResourceContent{UploadId: stringPtr(content.UploadID), MediaType: stringPtr(content.MediaType), Name: content.Name, Size: int64Ptr(content.Size), Sha256: stringPtr(content.SHA256), Preview: stringPtr(content.Preview)}}
		default:
			return nil, errors.New("capability result content type is invalid")
		}
		out.Contents = append(out.Contents, item)
	}
	return out, nil
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
		if !ok || entry.State != InvocationRunning {
			return nil
		}
		r.callsMu.Lock()
		_, active := r.calls[id]
		r.callsMu.Unlock()
		if !active {
			return nil
		}
		return r.replay(entry)
	}
	if !ok {
		return r.sendRecoverNotFound(id)
	}
	return r.replay(entry)
}

func (r *Runtime) replay(entry JournalEntry) error {
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
	frame := &protocol.Envelope{MessageType: protocol.MessageType_FAILED, InvocationId: stringPtr(id), Payload: &protocol.Envelope_Failed{Failed: &protocol.Failed{Message: stringPtr(msg)}}}
	sendErr := r.finish(id, protocol.MessageType_FAILED, frame.Payload, InvocationFailed)
	if r.journal.ConfirmExit(id) {
		exitErr := r.sendExecutionExited(id)
		if sendErr != nil {
			return sendErr
		}
		return exitErr
	}
	return sendErr
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
	if !r.journal.Complete(id, state, data) {
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
	if requested && !cleanupUnconfirmed && r.journal.ConfirmExit(id) {
		_ = r.sendExecutionExited(id)
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
