package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"syscall"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
)

const usage = "Usage: kk-studio-daemon --config <absolute-path> | --check-config <absolute-path>\n"

type RuntimeRunner interface {
	Run(context.Context, config.DaemonConfig) error
}
type Dependencies struct {
	Runtime    RuntimeRunner
	HelperMain func([]string) int
}

const h2xconnectBootstrapArg = "__bootstrap-h2xconnect"

func main() {
	args := os.Args[1:]
	if len(args) == 0 || args[0] != h2xconnectBootstrapArg {
		bootstrapH2XConnect(args)
	}
	args = args[1:]
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	code := RunCLI(ctx, args, Dependencies{Runtime: productionRuntime{}, HelperMain: runNativeHelper}, os.Stdout, os.Stderr)
	os.Exit(code)
}

func bootstrapH2XConnect(args []string) {
	executable, err := os.Executable()
	if err != nil {
		fmt.Fprintln(os.Stderr, "daemon failed: executable path is unavailable")
		os.Exit(1)
	}
	childArgs := append([]string{h2xconnectBootstrapArg}, args...)
	command := exec.Command(executable, childArgs...)
	command.Env = withH2XConnect(os.Environ())
	command.Stdin, command.Stdout, command.Stderr = os.Stdin, os.Stdout, os.Stderr
	if err := command.Start(); err != nil {
		fmt.Fprintln(os.Stderr, "daemon failed: bootstrap process could not be started")
		os.Exit(1)
	}
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
	forwardDone := make(chan struct{})
	go func() {
		select {
		case received := <-signals:
			_ = command.Process.Signal(received)
		case <-forwardDone:
		}
	}()
	waitErr := command.Wait()
	close(forwardDone)
	signal.Stop(signals)
	if waitErr != nil {
		var exitError *exec.ExitError
		if errors.As(waitErr, &exitError) {
			os.Exit(exitError.ExitCode())
		}
		fmt.Fprintln(os.Stderr, "daemon failed: bootstrap process could not be started")
		os.Exit(1)
	}
	os.Exit(0)
}

func withH2XConnect(environment []string) []string {
	result := make([]string, 0, len(environment)+1)
	found := false
	for _, entry := range environment {
		if strings.HasPrefix(entry, "GODEBUG=") {
			found = true
			value := strings.TrimPrefix(entry, "GODEBUG=")
			if !hasH2XConnect(value) {
				if value != "" {
					value += ","
				}
				value += "http2xconnect=1"
			}
			result = append(result, "GODEBUG="+value)
		} else {
			result = append(result, entry)
		}
	}
	if !found {
		result = append(result, "GODEBUG=http2xconnect=1")
	}
	return result
}

func hasH2XConnect(value string) bool {
	for _, option := range strings.Split(value, ",") {
		if option == "http2xconnect=1" {
			return true
		}
	}
	return false
}

func runNativeHelper(args []string) int {
	if code, handled := runInternalCommand(args); handled {
		return code
	}
	return 1
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

// RunCLI processes direct OS argument strings and confines diagnostics to safe text.
func RunCLI(ctx context.Context, args []string, deps Dependencies, stdout, stderr io.Writer) int {
	for _, arg := range args {
		if strings.IndexByte(arg, 0) >= 0 {
			fmt.Fprintln(stderr, "invalid daemon argument transport")
			return 2
		}
	}
	if len(args) > 0 && (strings.HasPrefix(args[0], "--internal-") || strings.HasPrefix(args[0], "__")) {
		if deps.HelperMain == nil {
			fmt.Fprintln(stderr, "daemon: internal helper is unavailable")
			return 1
		}
		return deps.HelperMain(args)
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
