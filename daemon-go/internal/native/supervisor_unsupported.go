//go:build !linux && !darwin && !windows

package native

import (
	"fmt"
	"os"
	"runtime"
)

func NewSupervisor() (Supervisor, error) {
	return newUnsupportedSupervisor(runtime.GOOS)
}

func helperMain(args []string) int {
	_ = args
	_, _ = fmt.Fprintln(os.Stderr, "helper-launch-failure")
	return 1
}
