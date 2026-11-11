//go:build windows

package native

import (
	"fmt"
	"os"
	"runtime"
)

func NewSupervisor() (Supervisor, error) {
	return nil, fmt.Errorf("the native process supervisor does not support %s", runtime.GOOS)
}

func helperMain(args []string) int {
	_ = args
	_, _ = fmt.Fprintln(os.Stderr, "helper-win32-failure")
	return 1
}
