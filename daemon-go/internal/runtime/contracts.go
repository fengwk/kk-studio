package runtime

import (
	"context"
	"encoding/json"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// CapabilityCall is one protocol invocation. Arguments contains the original JSON object;
// output limits belong to each INVOKE rather than to mutable session settings.
type CapabilityCall struct {
	InvocationID   string
	CapabilityID   string
	Arguments      json.RawMessage
	TimeoutMillis  int64
	MaxOutputBytes int64
	ExpectedFile   *ExpectedFile
}

// ExpectedFile identifies a user-approved file snapshot for fs.edit.
type ExpectedFile struct {
	Path   string
	SHA256 string
}

// ResultContent is one model-visible result item. Exactly one payload form is used.
type ResultContent struct {
	Type      string
	Text      string
	JSON      json.RawMessage
	Bytes     []byte
	MediaType string
	Name      *string
	UploadID  string
	Size      int64
	SHA256    string
	Preview   string
}

// CapabilityResult is the terminal or progress payload for an invocation.
type CapabilityResult struct {
	CallID   string
	Error    bool
	Details  json.RawMessage
	Contents []ResultContent
}

// ResourceUploader converts binary result contents to server-managed resource references.
type ResourceUploader interface {
	Upload(ctx context.Context, invocationID string, content ResultContent) (ResultContent, error)
}

// Progress reports a non-terminal capability result to runtime.
type Progress func(CapabilityResult) error

// CapabilityContext supplies cancellation and shared runtime services to an executor.
type CapabilityContext struct {
	Config               config.DaemonConfig
	Processes            native.Supervisor
	Resources            ResourceUploader
	Progress             Progress
	ConfirmExecutionExit func()
}

// CapabilityExecutor is implemented by coding, LSP, IDE, and skill capability providers.
type CapabilityExecutor func(context.Context, CapabilityCall, CapabilityContext) (CapabilityResult, error)

// CapabilityProvider exposes the capability map owned by a feature package.
type CapabilityProvider interface {
	Capabilities() map[string]CapabilityExecutor
}

// Connection exchanges decoded control envelopes with the runtime. Transport implements it.
type Connection interface {
	Receive(context.Context) (*protocol.Envelope, error)
	Send(context.Context, *protocol.Envelope) error
	Close() error
}
