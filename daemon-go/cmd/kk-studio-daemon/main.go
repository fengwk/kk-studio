package main

import (
	"fmt"
	"os"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
)

func main() {
	args := os.Args[1:]
	if code, handled := runInternalCommand(args); handled {
		os.Exit(code)
	}
	if err := run(args); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func runInternalCommand(args []string) (int, bool) {
	if len(args) == 0 {
		return 0, false
	}
	switch args[0] {
	case "__native-helper":
		return native.HelperMain(args[1:]), true
	case "__lock-helper":
		return native.LockHelperMain(args[1:]), true
	default:
		return 0, false
	}
}

func run(args []string) error {
	if len(args) == 1 && (args[0] == "--help" || args[0] == "-h") {
		fmt.Fprintln(os.Stdout, "Usage: kk-studio-daemon --config <absolute-path> | --check-config <absolute-path>")
		return nil
	}
	if len(args) == 1 && args[0] == "--version" {
		fmt.Fprintf(os.Stdout, "kk-studio-daemon %s\n", config.DaemonVersion)
		return nil
	}
	if len(args) == 2 && (args[0] == "--config" || args[0] == "--check-config") {
		return fmt.Errorf("%s is not implemented in the Go daemon skeleton", args[0])
	}
	return fmt.Errorf("daemon: expected exactly --config <absolute-path>")
}
