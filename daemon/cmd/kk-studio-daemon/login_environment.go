package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/native"
	"github.com/fengwk/kk-studio/daemon/internal/uuid"
)

const loginEnvironmentCommand = "__login-environment"

var errLoginEnvironmentCleanup = errors.New("Bash login environment: process tree cleanup could not be confirmed")

func loginEnvironmentMain(args []string) int {
	if len(args) != 1 {
		return 2
	}
	marker := loginEnvironmentMarker(args[0])
	if _, err := os.Stdout.Write(marker); err != nil {
		return 1
	}
	if _, err := io.WriteString(os.Stdout, strings.Join(os.Environ(), "\x00")); err != nil {
		return 1
	}
	if _, err := os.Stdout.Write(marker); err != nil {
		return 1
	}
	return 0
}

func loginEnvironmentMarker(nonce string) []byte {
	return []byte("\x00kk-studio-login-env:" + nonce + "\x00")
}

func loadLoginEnvironment(ctx context.Context, processes native.Supervisor, bash string) ([]string, error) {
	return probeLoginEnvironment(ctx, processes, bash, 10*time.Second)
}

func probeLoginEnvironment(ctx context.Context, processes native.Supervisor, bash string, timeout time.Duration) (_ []string, resultErr error) {
	executable, err := os.Executable()
	if err != nil {
		return nil, errors.New("Bash login environment: daemon executable is unavailable")
	}
	nonce := uuid.New()
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	// Git Bash translates PATH when exec'ing a native program. Pass the executable
	// as an argument, not interpolated shell text (the path may contain spaces).
	process, err := processes.Start(ctx, native.ProcessSpec{Executable: bash, Args: []string{"-lc", `exec "$1" "$2" "$3"`, "bash", filepath.ToSlash(executable), loginEnvironmentCommand, nonce}})
	if err != nil {
		return nil, errors.Join(loginEnvironmentProcessError(ctx, "could not start Bash"), loginEnvironmentScopeError(err))
	}
	defer func() {
		if resultErr != nil {
			cleanup, stop := context.WithTimeout(context.Background(), 10*time.Second)
			defer stop()
			if err := process.Terminate(cleanup, 0); err != nil {
				resultErr = errors.Join(resultErr, errLoginEnvironmentCleanup)
			}
		}
	}()
	// A login profile must see EOF if it tries to read stdin.
	if err := process.Stdin().Close(); err != nil {
		return nil, loginEnvironmentProcessError(ctx, "could not close Bash stdin")
	}
	// Drain both pipes before Wait, which closes them. Ignore stderr entirely;
	// none of the profile's output can enter an error or a log.
	type output struct {
		data []byte
		err  error
	}
	stdout := make(chan output, 1)
	stderr := make(chan struct{}, 1)
	go func() { data, err := io.ReadAll(process.Stdout()); stdout <- output{data, err} }()
	go func() { _, _ = io.Copy(io.Discard, process.Stderr()); stderr <- struct{}{} }()
	var captured output
	for stdout != nil || stderr != nil {
		select {
		case captured = <-stdout:
			stdout = nil
		case <-stderr:
			stderr = nil
		case <-ctx.Done():
			return nil, loginEnvironmentProcessError(ctx, "could not read Bash output")
		}
	}
	exit, err := process.Wait(ctx)
	if err != nil || ctx.Err() != nil {
		return nil, errors.Join(loginEnvironmentProcessError(ctx, "could not wait for Bash"), loginEnvironmentScopeError(err))
	}
	if exit.Code == nil {
		return nil, errors.New("Bash login environment: Bash did not exit normally")
	}
	if *exit.Code != 0 {
		return nil, fmt.Errorf("Bash login environment: Bash exited with code %d", *exit.Code)
	}
	if captured.err != nil {
		return nil, errors.New("Bash login environment: could not read Bash output")
	}
	return parseLoginEnvironment(captured.data, loginEnvironmentMarker(nonce))
}

func loginEnvironmentProcessError(ctx context.Context, message string) error {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return errors.New("Bash login environment: timed out")
	}
	if ctx.Err() != nil {
		return fmt.Errorf("Bash login environment: %w", ctx.Err())
	}
	return errors.New("Bash login environment: " + message)
}

func loginEnvironmentScopeError(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, native.ErrScopeNotConverged) {
		return errLoginEnvironmentCleanup
	}
	return nil
}

func parseLoginEnvironment(data, marker []byte) ([]string, error) {
	_, payload, found := bytes.Cut(data, marker)
	if !found {
		return nil, errors.New("Bash login environment: missing output marker")
	}
	payload, _, found = bytes.Cut(payload, marker)
	if !found {
		return nil, errors.New("Bash login environment: missing output marker")
	}
	environment := []string{}
	if len(payload) > 0 {
		environment = strings.Split(string(payload), "\x00")
	}
	environment = slices.DeleteFunc(environment, func(entry string) bool {
		key, _, _ := strings.Cut(entry, "=")
		if runtime.GOOS == "windows" {
			key = strings.ToUpper(key)
		}
		switch key {
		case "PWD", "OLDPWD", "SHLVL", "_":
			return true
		}
		return false
	})
	slices.Sort(environment)
	return environment, nil
}
