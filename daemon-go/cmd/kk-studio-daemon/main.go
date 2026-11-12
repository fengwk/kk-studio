package main

import (
	"fmt"
	"os"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
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
