//go:build linux || darwin

package native

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"syscall"
	"time"
)

// helperEnvironment returns the environment the scope helper inherits and passes on to
// the command. Env is an overlay on the daemon environment by default and the whole
// environment in replacement mode; both are filtered identically.
func helperEnvironment(spec ProcessSpec) []string {
	base := os.Environ()
	if spec.ReplaceEnv {
		base = nil
	}
	return mergeEnvironment(base, spec.Env)
}

func mergeEnvironment(base, overrides []string) []string {
	values := make(map[string]string, len(base)+len(overrides))
	for _, entry := range append(append([]string(nil), base...), overrides...) {
		key, value, ok := strings.Cut(entry, "=")
		if !ok || key == "" || strings.ContainsRune(entry, '\x00') {
			continue
		}
		if key == "NODE_CHANNEL_FD" {
			continue
		}
		values[key] = value
	}
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	result := make([]string, 0, len(keys))
	for _, key := range keys {
		result = append(result, key+"="+values[key])
	}
	return result
}

func completeHelperCommandLifecycle(publishExit func() error, converge func() error, reapAdopted func() error, publishCleanup func(string) error, publishFailure func()) int {
	if err := publishExit(); err != nil {
		_ = converge()
		_ = publishCleanup("false")
		publishFailure()
		return 1
	}
	if err := converge(); err != nil {
		_ = publishCleanup("false")
		publishFailure()
		return 1
	}
	if err := reapAdopted(); err != nil {
		_ = publishCleanup("false")
		publishFailure()
		return 1
	}
	if err := publishCleanup("true"); err != nil {
		return 1
	}
	return 0
}

func helperMain(args []string) int {
	return helperMainWithSubreaper(args, runtime.GOOS, enableChildSubreaper)
}

func helperPrepareLaunch(args []string, goos string, enableSubreaper func() error) (string, *launchSpec, int) {
	if len(args) != 1 || !filepath.IsAbs(args[0]) || hasControl(args[0]) {
		_, _ = fmt.Fprintln(os.Stderr, "helper-launch-failure")
		return "", nil, 2
	}
	dir := args[0]
	if goos == "linux" {
		if err := enableSubreaper(); err != nil {
			publishQuietly(dir, errorFile, "helper-posix-failure")
			return dir, nil, 1
		}
	}
	if err := verifyPrivateDirectory(dir); err != nil {
		publishQuietly(dir, errorFile, "helper-launch-failure")
		return dir, nil, 1
	}
	launch, err := readLaunch(dir)
	if err != nil || launch == nil {
		publishQuietly(dir, errorFile, "helper-launch-failure")
		return dir, nil, 1
	}
	return dir, launch, 0
}

func helperAwaitPermit(dir string) (bool, <-chan struct{}, int) {
	terminated := make(chan os.Signal, 1)
	signal.Notify(terminated, syscall.SIGTERM)
	defer signal.Stop(terminated)
	if err := publishText(dir, scopeFile, fmt.Sprint(os.Getpid())); err != nil {
		publishQuietly(dir, errorFile, "helper-posix-failure")
		return false, nil, 1
	}
	owner := os.NewFile(3, "owner-lifecycle")
	if owner == nil {
		publishQuietly(dir, errorFile, "helper-posix-failure")
		return false, nil, 1
	}
	ownerClosed := make(chan struct{})
	go func() { _, _ = io.Copy(io.Discard, owner); close(ownerClosed) }()
	deadline := time.NewTimer(scopeStartBudget)
	defer deadline.Stop()
	ticker := time.NewTicker(scopePollInterval)
	defer ticker.Stop()
	for {
		permit, permitErr := readState(dir, permitFile)
		if permitErr == nil {
			if strings.TrimSpace(string(permit)) == "true" {
				signal.Stop(terminated)
				return true, ownerClosed, 0
			}
			publishQuietly(dir, errorFile, "helper-permit-failure")
			if err := convergeSessionExcept(context.Background(), os.Getpid(), 0, os.Getpid()); err == nil {
				_ = publishText(dir, cleanupFile, "true")
			} else {
				publishQuietly(dir, cleanupFile, "false")
			}
			return false, nil, 1
		}
		if !errors.Is(permitErr, os.ErrNotExist) {
			publishQuietly(dir, errorFile, "helper-posix-failure")
			return false, nil, 1
		}
		select {
		case <-ownerClosed:
			if err := convergeSessionExcept(context.Background(), os.Getpid(), 0, os.Getpid()); err != nil {
				publishQuietly(dir, cleanupFile, "false")
				return false, nil, 1
			}
			if err := publishText(dir, cleanupFile, "true"); err != nil {
				return false, nil, 1
			}
			return false, nil, 0
		case <-terminated:
			if err := convergeSessionExcept(context.Background(), os.Getpid(), 0, os.Getpid()); err != nil {
				publishQuietly(dir, cleanupFile, "false")
				return false, nil, 1
			}
			if err := publishText(dir, cleanupFile, "true"); err != nil {
				return false, nil, 1
			}
			return false, nil, 0
		case <-deadline.C:
			publishQuietly(dir, errorFile, "helper-permit-failure")
			if err := convergeSessionExcept(context.Background(), os.Getpid(), 0, os.Getpid()); err != nil {
				publishQuietly(dir, cleanupFile, "false")
				return false, nil, 1
			}
			_ = publishText(dir, cleanupFile, "true")
			return false, nil, 1
		case <-ticker.C:
		}
	}
}

func helperStartCommand(dir string, launch *launchSpec) (*exec.Cmd, int) {
	if len(launch.Args) == 0 {
		publishQuietly(dir, errorFile, "helper-posix-failure")
		publishQuietly(dir, cleanupFile, "true")
		return nil, 1
	}
	command := exec.Command(launch.Args[0], launch.Args[1:]...)
	command.Dir = launch.Cwd
	command.Env = mergeEnvironment(os.Environ(), nil)
	command.Stdin, command.Stdout, command.Stderr = os.Stdin, os.Stdout, os.Stderr
	if err := command.Start(); err != nil {
		publishQuietly(dir, errorFile, "helper-posix-failure")
		publishQuietly(dir, cleanupFile, "true")
		return nil, 1
	}
	return command, 0
}

func helperWaitCommand(command *exec.Cmd, ownerClosed <-chan struct{}) error {
	commandDone := make(chan error, 1)
	go func() { commandDone <- command.Wait() }()
	select {
	case waitErr := <-commandDone:
		return waitErr
	case <-ownerClosed:
		_ = convergeSessionExcept(context.Background(), os.Getpid(), helperGracePeriod, os.Getpid())
		return <-commandDone
	}
}

func helperPublishExit(dir string, waitErr error) (func() error, error) {
	report := exitReport{}
	if waitErr == nil {
		code := 0
		report.ExitCode = &code
	} else {
		var exitErr *exec.ExitError
		if errors.As(waitErr, &exitErr) {
			status, ok := exitErr.Sys().(syscall.WaitStatus)
			if ok && status.Signaled() {
				signal := status.Signal().String()
				report.Signal = &signal
			} else {
				code := exitErr.ExitCode()
				report.ExitCode = &code
			}
		}
	}
	payload, err := json.Marshal(report)
	if err != nil {
		return nil, err
	}
	return func() error { return publishText(dir, exitFile, string(payload)) }, nil
}

func helperMainWithSubreaper(args []string, goos string, enableSubreaper func() error) int {
	dir, launch, code := helperPrepareLaunch(args, goos, enableSubreaper)
	if code != 0 {
		return code
	}
	permitted, ownerClosed, code := helperAwaitPermit(dir)
	if code != 0 || !permitted {
		return code
	}
	command, code := helperStartCommand(dir, launch)
	if code != 0 {
		return code
	}
	waitErr := helperWaitCommand(command, ownerClosed)
	publishExit, err := helperPublishExit(dir, waitErr)
	if err != nil {
		publishQuietly(dir, errorFile, "helper-posix-failure")
		return 1
	}
	return completeHelperCommandLifecycle(
		publishExit,
		func() error {
			return convergeSessionExcept(context.Background(), os.Getpid(), helperGracePeriod, os.Getpid())
		},
		func() error {
			// The command PID is exclusively owned by exec.Cmd.Wait; never let Wait4 race it.
			return convergeAdoptedChildren(context.Background(), os.Getpid(), helperGracePeriod, []int{command.Process.Pid})
		},
		func(value string) error { return publishText(dir, cleanupFile, value) },
		func() { publishQuietly(dir, errorFile, "helper-posix-failure") },
	)
}
