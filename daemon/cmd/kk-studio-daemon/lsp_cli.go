package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"path/filepath"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/lsp"
	"github.com/fengwk/kk-studio/daemon/internal/native"
)

func runLSPCLI(ctx context.Context, args []string, stdout, stderr io.Writer) (bool, int) {
	if len(args) == 0 {
		return false, 0
	}
	switch args[0] {
	case "--lsp-profiles":
		if len(args) != 1 {
			fmt.Fprintln(stderr, "Usage: kk-studio-daemon --lsp-profiles")
			return true, 1
		}
		fmt.Fprintf(stdout, "%s\tLinux/amd64; installation requires npm; runtime requires Node.js 18 or later\n", lsp.TypeScriptProfileID)
		return true, 0
	case "--install-lsp", "--lsp-config":
		if len(args) != 3 {
			fmt.Fprintf(stderr, "Usage: kk-studio-daemon %s <profile> <absolute-library>\n", args[0])
			return true, 1
		}
	default:
		return false, 0
	}
	if args[1] != lsp.TypeScriptProfileID {
		fmt.Fprintln(stderr, "LSP profile is not available")
		return true, 1
	}
	if !filepath.IsAbs(args[2]) {
		fmt.Fprintln(stderr, "LSP library directory must be an absolute path")
		return true, 1
	}
	environment, err := lspLoginEnvironment(ctx)
	if err != nil {
		fmt.Fprintf(stderr, "LSP login environment unavailable: %s\n", safeError(err))
		return true, 1
	}
	if args[0] == "--install-lsp" {
		installed, err := (lsp.TypeScriptLibrary{Directory: args[2], Environment: environment}).Install(ctx)
		if err != nil {
			fmt.Fprintf(stderr, "LSP installation failed: %s\n", safeError(err))
			return true, 1
		}
		fmt.Fprintf(stdout, "Installed %s at %s\n", lsp.TypeScriptProfileID, installed)
		return true, 0
	}
	server, err := lsp.TypeScriptServerConfig(args[2], environment)
	if err != nil {
		fmt.Fprintf(stderr, "LSP configuration unavailable: %s\n", safeError(err))
		return true, 1
	}
	output := struct {
		Servers map[string]any `json:"servers"`
	}{Servers: map[string]any{server.ID: struct {
		Command           []string `json:"command"`
		Extensions        []string `json:"extensions"`
		RootMarkers       []string `json:"rootMarkers"`
		FirstMatchMarkers []string `json:"firstMatchMarkers"`
	}{server.Command, server.Extensions, server.RootMarkers, server.FirstMatchMarkers}}}
	encoder := json.NewEncoder(stdout)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(output); err != nil {
		fmt.Fprintln(stderr, "LSP configuration could not be written")
		return true, 1
	}
	return true, 0
}

// lspLoginEnvironment resolves the Bash login environment once for one LSP CLI command.
// The supervisor only serves the login-shell probe and is always closed before returning.
func lspLoginEnvironment(ctx context.Context) ([]string, error) {
	bash, err := config.ResolveBash(nil)
	if err != nil {
		return nil, fmt.Errorf("Bash executable is unavailable: %w", err)
	}
	processes, err := native.NewSupervisor()
	if err != nil {
		return nil, fmt.Errorf("process supervision is unavailable: %w", err)
	}
	environment, resolveErr := loadLoginEnvironment(ctx, processes, bash)
	closeErr := processes.Close(context.Background())
	if closeErr != nil {
		closeErr = fmt.Errorf("process supervision shutdown failed: %w", closeErr)
	}
	if err := errors.Join(resolveErr, closeErr); err != nil {
		return nil, err
	}
	return environment, nil
}
