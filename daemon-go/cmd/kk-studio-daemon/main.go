package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

const usage = "Usage: kk-studio-daemon --config <absolute-path> | --check-config <absolute-path>\n"

type RuntimeRunner interface {
	Run(context.Context, config.DaemonConfig) error
}
type Dependencies struct{ Runtime RuntimeRunner }

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	code := RunCLI(ctx, os.Args[1:], Dependencies{}, os.Stdout, os.Stderr)
	os.Exit(code)
}

// RunCLI processes direct OS argument strings and confines diagnostics to safe text.
func RunCLI(ctx context.Context, args []string, deps Dependencies, stdout, stderr io.Writer) int {
	for _, arg := range args {
		if strings.IndexByte(arg, 0) >= 0 {
			fmt.Fprintln(stderr, "invalid daemon argument transport")
			return 2
		}
	}
	if len(args) == 1 {
		switch args[0] {
		case "--help", "-h":
			_, _ = io.WriteString(stdout, usage)
			return 0
		case "--version":
			fmt.Fprintf(stdout, "kk-studio-daemon %s\n", config.DaemonVersion)
			return 0
		}
	}
	if len(args) == 2 && args[0] == "--check-config" {
		if err := config.CheckConfig(args[1]); err != nil {
			fmt.Fprintf(stderr, "Invalid daemon configuration: %s\n", safeError(err))
			return 1
		}
		_, _ = io.WriteString(stdout, "Daemon configuration is valid\n")
		return 0
	}
	if len(args) != 2 || args[0] != "--config" {
		fmt.Fprintln(stderr, "Invalid daemon configuration: daemon: expected exactly --config <absolute-path>")
		return 1
	}
	cfg, err := config.LoadDaemonConfig(args[1])
	if err != nil {
		fmt.Fprintf(stderr, "Invalid daemon configuration: %s\n", safeError(err))
		return 1
	}
	if deps.Runtime == nil {
		fmt.Fprintln(stderr, "daemon failed: runtime is not configured")
		return 1
	}
	if err := deps.Runtime.Run(ctx, cfg); err != nil {
		fmt.Fprintln(stderr, "daemon failed: runtime execution failed")
		return 1
	}
	return 0
}

func safeError(err error) string {
	if err == nil {
		return "unknown"
	}
	if errors.Is(err, context.Canceled) {
		return "operation cancelled"
	}
	text := strings.NewReplacer("\r", " ", "\n", " ", "\x00", " ").Replace(err.Error())
	if strings.TrimSpace(text) == "" {
		return "unknown"
	}
	return text
}
