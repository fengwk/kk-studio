package runtime

import (
	"errors"
	"io"
	"os"
	"sync"
)

type shutdownFailureTracker struct {
	mu       sync.Mutex
	failures []error
	writer   io.Writer
}

type unknownShutdownFailure struct{}

type shutdownFailureGroup struct {
	failures []error
}

func (unknownShutdownFailure) Error() string { return "shutdown action failed with a non-error value" }

func (g *shutdownFailureGroup) Error() string { return "daemon could not release every resource" }

func (g *shutdownFailureGroup) Unwrap() []error {
	return append([]error(nil), g.failures...)
}

func aggregateShutdownFailures(failures []error) error {
	if len(failures) == 0 {
		return nil
	}
	return &shutdownFailureGroup{failures: append([]error(nil), failures...)}
}

func newShutdownFailureTracker(writer io.Writer) *shutdownFailureTracker {
	if writer == nil {
		writer = os.Stderr
	}
	return &shutdownFailureTracker{writer: writer}
}

func (s *shutdownFailureTracker) release(phase string, action func() error) {
	failure := callShutdownAction(action)
	if failure != nil {
		s.record(phase, failure)
	}
}

func (s *shutdownFailureTracker) record(phase string, failure any) {
	s.mu.Lock()
	defer s.mu.Unlock()

	failureType, failureErr := normalizeShutdownFailure(failure)
	s.failures = append(s.failures, failureErr)
	if err := writeShutdownDiagnostic(s.writer, phase, failureType); err != nil {
		s.failures = append(s.failures, err)
	}
}

func (s *shutdownFailureTracker) collect(phase string, failure any) {
	s.mu.Lock()
	defer s.mu.Unlock()

	failureType, _ := normalizeShutdownFailure(failure)
	if err := writeShutdownDiagnostic(s.writer, phase, failureType); err != nil {
		s.failures = append(s.failures, err)
	}
}

func (s *shutdownFailureTracker) snapshot() []error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]error(nil), s.failures...)
}

func normalizeShutdownFailure(failure any) (string, error) {
	if err, ok := failure.(error); ok {
		return "Error", err
	}
	return "UnknownError", unknownShutdownFailure{}
}

func callShutdownAction(action func() error) (failure any) {
	defer func() {
		if recovered := recover(); recovered != nil {
			failure = recovered
		}
	}()
	return action()
}

func writeShutdownDiagnostic(writer io.Writer, phase, failureType string) (writeErr error) {
	defer func() {
		if recover() != nil {
			writeErr = errors.New("shutdown diagnostic writer panicked")
		}
	}()
	_, writeErr = io.WriteString(writer, "daemon "+phase+" failed: "+failureType+"\n")
	return writeErr
}
