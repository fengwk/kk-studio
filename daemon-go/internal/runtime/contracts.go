package runtime

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

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
	ImageInputTier ImageInputTier
	ExpectedFile   *ExpectedFile
}

// ImageInputTier is the image scaling tier carried by each INVOKE; it selects the box that
// local images are scaled into before they leave the daemon.
type ImageInputTier string

const (
	ImageInputTierP720     ImageInputTier = "P720"
	ImageInputTierP1080    ImageInputTier = "P1080"
	ImageInputTierOriginal ImageInputTier = "ORIGINAL"
)

// ExpectedFile identifies a user-approved file snapshot for a write or edit.
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

// ResourceUploadError carries an explicit cleanup fact across the uploader boundary. Cause is
// retained for local inspection but is deliberately excluded from Error to avoid leaking tickets.
type ResourceUploadError struct {
	Stage            string
	Category         string
	CleanupConfirmed bool
	Cause            error
}

func (e *ResourceUploadError) Error() string {
	if e.Category == "ticket-failed" {
		return "resource upload ticket failed"
	}
	return fmt.Sprintf("resource upload failed during %s (%s); cleanupConfirmed=%t", e.Stage, e.Category, e.CleanupConfirmed)
}

func (e *ResourceUploadError) Unwrap() error { return e.Cause }

// Progress reports a non-terminal capability result to runtime.
type Progress func(CapabilityResult) error

// CapabilityContext supplies cancellation and shared runtime services to an executor.
type CapabilityContext struct {
	Config               config.DaemonConfig
	Processes            native.Supervisor
	Resources            ResourceUploader
	Progress             Progress
	FileChanged          func(string)
	ConfirmExecutionExit func()
}

// CapabilityExecutor is implemented by coding, LSP, IDE, and skill capability providers.
type CapabilityExecutor func(context.Context, CapabilityCall, CapabilityContext) (CapabilityResult, error)

// CapabilityProvider exposes the capability map owned by a feature package.
type CapabilityProvider interface {
	Capabilities() map[string]CapabilityExecutor
}

// IDEService handles protocol IDE commands and leases without coupling runtime to the IDE package.
type IDEService interface {
	HandleCommand(context.Context, *protocol.IdeCommand) *protocol.IdeResult
	ApplyLease(*protocol.IdeLease, time.Time)
}

// Connection exchanges decoded control envelopes with the runtime. Transport implements it.
type Connection interface {
	Receive(context.Context) (*protocol.Envelope, error)
	Send(context.Context, *protocol.Envelope) error
	Close() error
}

// ConnectionFactory owns control-channel connection attempts; Runtime applies retry and generation policy.
type ConnectionFactory interface {
	Connect(context.Context) (Connection, error)
}

// ErrRegistrationRejected marks an authentication rejection that must not be retried.
var ErrRegistrationRejected = errors.New("daemon registration rejected")
