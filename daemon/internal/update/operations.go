package update

import (
	"context"
	"errors"
	"regexp"
	"sync"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

const maxTrackedOperations = 8

type Phase string

const (
	PhaseAccepted Phase = "UPDATE_ACCEPTED"
	PhasePrepared Phase = "UPDATE_PREPARED"
	PhaseFailed   Phase = "UPDATE_FAILED"
)

type Command struct {
	OperationID   string
	TargetVersion string
}

type Progress struct {
	DownloadedBytes int64
	TotalBytes      *int64
}

type Outcome struct {
	Phase         Phase
	Message       string
	ReleasePermit func() error
	WatchFailure  func(func(string)) func()
}

type Runner interface {
	Configured() bool
	BusyReason() string
	StopForUpdate(context.Context) error
	HandleUpdate(context.Context, Command, config.DaemonConfig, func(Progress)) (Outcome, error)
}

var (
	canonicalOperationID = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	targetVersionPattern = regexp.MustCompile(`^[0-9A-Za-z._+-]{1,64}$`)
)

func ValidateCommand(command Command) error {
	if !canonicalOperationID.MatchString(command.OperationID) || !targetVersionPattern.MatchString(command.TargetVersion) {
		return errors.New("invalid update command")
	}
	return nil
}

type Receipt struct {
	Command Command
	Phase   Phase
	Message string
}

type Action string

const (
	ActionAdmit        Action = "admit"
	ActionStatus       Action = "status"
	ActionReplayActive Action = "replay-active"
	ActionReplayCache  Action = "replay-cached"
	ActionReject       Action = "reject"
)

type Decision struct {
	Action  Action
	Receipt *Receipt
}

type activeOperation struct {
	receipt  Receipt
	prepared bool
}

// Operations applies update operation idempotency, one-at-a-time admission, and bounded receipt replay.
type Operations struct {
	mu      sync.Mutex
	active  *activeOperation
	results map[string]Receipt
	order   []string
}

func NewOperations() *Operations { return &Operations{results: make(map[string]Receipt)} }

// Decide enforces operation replay and target admission. The caller validates the wire command first.
func (o *Operations) Decide(command Command, currentVersion string, configured bool, busyReason string) (Decision, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	key := commandKey(command)
	if o.active != nil {
		active := o.active
		if active.receipt.Command.OperationID == command.OperationID {
			if commandKey(active.receipt.Command) != key {
				return Decision{}, errors.New("the same update operationId must not change its arguments")
			}
			if active.prepared {
				return Decision{Action: ActionReplayActive, Receipt: cloneReceipt(&active.receipt)}, nil
			}
			return Decision{Action: ActionStatus}, nil
		}
		if active.receipt.Command.TargetVersion == command.TargetVersion {
			return Decision{Action: ActionStatus}, nil
		}
		receipt := Receipt{Command: command, Phase: PhaseFailed, Message: "update is busy for target " + active.receipt.Command.TargetVersion}
		o.rememberLocked(receipt)
		return Decision{Action: ActionReject, Receipt: cloneReceipt(&receipt)}, nil
	}
	if receipt, exists := o.results[command.OperationID]; exists {
		if commandKey(receipt.Command) != key {
			return Decision{}, errors.New("the same update operationId must not change its arguments")
		}
		return Decision{Action: ActionReplayCache, Receipt: cloneReceipt(&receipt)}, nil
	}
	if command.TargetVersion == currentVersion {
		return Decision{Action: ActionStatus}, nil
	}
	if busyReason != "" {
		receipt := Receipt{Command: command, Phase: PhaseFailed, Message: "update is busy: " + busyReason}
		o.rememberLocked(receipt)
		return Decision{Action: ActionReject, Receipt: cloneReceipt(&receipt)}, nil
	}
	if !configured {
		receipt := Receipt{Command: command, Phase: PhaseFailed, Message: "managed update is not configured"}
		o.rememberLocked(receipt)
		return Decision{Action: ActionReject, Receipt: cloneReceipt(&receipt)}, nil
	}
	receipt := Receipt{Command: command, Phase: PhaseAccepted}
	o.active = &activeOperation{receipt: receipt}
	return Decision{Action: ActionAdmit, Receipt: cloneReceipt(&receipt)}, nil
}

// Complete records a runner result. PREPARED retains the admission hold until FailActive or shutdown.
func (o *Operations) Complete(operationID string, phase Phase, message string) (Receipt, bool) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.active == nil || o.active.receipt.Command.OperationID != operationID {
		return Receipt{}, false
	}
	receipt := Receipt{Command: o.active.receipt.Command, Phase: phase, Message: message}
	if phase == PhasePrepared {
		o.active.prepared = true
		o.active.receipt = receipt
		o.rememberLocked(receipt)
		return receipt, true
	}
	if phase != PhaseFailed {
		return Receipt{}, false
	}
	o.active = nil
	o.rememberLocked(receipt)
	return receipt, true
}

func (o *Operations) FailActive(message string) (Receipt, bool) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.active == nil {
		return Receipt{}, false
	}
	receipt := Receipt{Command: o.active.receipt.Command, Phase: PhaseFailed, Message: message}
	o.active = nil
	o.rememberLocked(receipt)
	return receipt, true
}

// ReceiptToResend prioritizes the active operation, then the newest cached receipt.
func (o *Operations) ReceiptToResend() (Receipt, bool) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.active != nil {
		return o.active.receipt, true
	}
	if len(o.order) == 0 {
		return Receipt{}, false
	}
	return o.results[o.order[len(o.order)-1]], true
}

func (o *Operations) Active() (Receipt, bool) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.active == nil {
		return Receipt{}, false
	}
	return o.active.receipt, true
}

func (o *Operations) rememberLocked(receipt Receipt) {
	id := receipt.Command.OperationID
	if _, exists := o.results[id]; exists {
		delete(o.results, id)
		for index, value := range o.order {
			if value == id {
				o.order = append(o.order[:index], o.order[index+1:]...)
				break
			}
		}
	}
	o.results[id] = receipt
	o.order = append(o.order, id)
	for len(o.order) > maxTrackedOperations {
		oldest := o.order[0]
		o.order = o.order[1:]
		delete(o.results, oldest)
	}
}

func commandKey(command Command) string { return command.TargetVersion }

func cloneReceipt(receipt *Receipt) *Receipt {
	if receipt == nil {
		return nil
	}
	copy := *receipt
	return &copy
}
