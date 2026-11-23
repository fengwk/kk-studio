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
	"github.com/fengwk/kk-studio/daemon/internal/h2xconnect"
	"github.com/fengwk/kk-studio/daemon/internal/native"
)

const usage = "Usage: kk-studio-daemon --config <absolute-path> | --check-config <absolute-path> | --lsp-profiles | --install-lsp <profile> <absolute-library> | --lsp-config <profile> <absolute-library> | ide-recovery list|export|archive\n"

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
	if shouldBootstrapH2XConnect(args) {
		bootstrapH2XConnect(args)
	}
	if len(args) > 0 && args[0] == h2xconnectBootstrapArg {
		args = args[1:]
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	code := RunCLI(ctx, args, Dependencies{Runtime: productionRuntime{}, HelperMain: runNativeHelper}, os.Stdout, os.Stderr)
	os.Exit(code)
}

func shouldBootstrapH2XConnect(args []string) bool {
	return len(args) == 0 || (args[0] != h2xconnectBootstrapArg && !strings.HasPrefix(args[0], "__"))
}

func bootstrapH2XConnect(args []string) {
	executable, err := os.Executable()
	if err != nil {
		fmt.Fprintln(os.Stderr, "daemon failed: executable path is unavailable")
		os.Exit(1)
	}
	childArgs := append([]string{h2xconnectBootstrapArg}, args...)
	command := exec.Command(executable, childArgs...)
	command.Env = h2xconnect.WithEnvironment(os.Environ())
	command.Stdin, command.Stdout, command.Stderr = os.Stdin, os.Stdout, os.Stderr
	if err := command.Start(); err != nil {
		fmt.Fprintln(os.Stderr, "daemon failed: bootstrap process could not be started")
		os.Exit(1)
	}
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
	forwardDone := make(chan struct{})
	go forwardSignals(signals, forwardDone, func(received os.Signal) {
		_ = command.Process.Signal(received)
	})
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

// forwardSignals relays every received signal to the bootstrap child until the child exits.
func forwardSignals(signals <-chan os.Signal, childExited <-chan struct{}, forward func(os.Signal)) {
	for {
		select {
		case received := <-signals:
			forward(received)
		case <-childExited:
			return
		}
	}
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
	case loginEnvironmentCommand:
		return loginEnvironmentMain(args[1:]), true
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
	if len(args) > 0 && strings.HasPrefix(args[0], "__") {
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
	if handled, code := runLSPCLI(ctx, args, stdout, stderr); handled {
		return code
	}
	if handled, code := runIDERecoveryCLI(args, stdout, stderr); handled {
		return code
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
