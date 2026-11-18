package lsp

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/native"
)

func fakeNPM(t *testing.T, fail bool) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "npm")
	body := "#!/bin/sh\nset -eu\n"
	if fail {
		body += "exit 1\n"
	} else {
		body += "if [ -n \"${TS_INSTALL_COUNTER:-}\" ]; then printf x >> \"$TS_INSTALL_COUNTER\"; fi\n"
		body += "sleep 0.15\n"
		body += "mkdir -p node_modules/typescript-language-server/lib node_modules/typescript node_modules/.bin\n"
		body += "printf '{\"version\":\"4.3.4\"}' > node_modules/typescript-language-server/package.json\n"
		body += "printf '{\"version\":\"5.8.3\"}' > node_modules/typescript/package.json\n"
		body += "printf 'export {};\\n' > node_modules/typescript-language-server/lib/cli.mjs\n"
		body += "printf '#!/bin/sh\\nexit 0\\n' > node_modules/.bin/typescript-language-server\n"
		body += "chmod 755 node_modules/.bin/typescript-language-server\n"
	}
	if err := os.WriteFile(path, []byte(body), 0700); err != nil {
		t.Fatal(err)
	}
	return path
}

// Concurrent callers must publish one immutable copy under the shared library lock.
func TestTypeScriptLibrary_ConcurrentInstallAndResolveProfile(t *testing.T) {
	requireLinuxTSProfile(t)
	root := t.TempDir()
	counter := filepath.Join(t.TempDir(), "npm-invocations")
	previousCounter, counterWasSet := os.LookupEnv("TS_INSTALL_COUNTER")
	if err := os.Setenv("TS_INSTALL_COUNTER", counter); err != nil {
		t.Fatal(err)
	}
	defer func() {
		if counterWasSet {
			_ = os.Setenv("TS_INSTALL_COUNTER", previousCounter)
		} else {
			_ = os.Unsetenv("TS_INSTALL_COUNTER")
		}
	}()
	library := TypeScriptLibrary{Directory: root, NPM: fakeNPM(t, false)}
	var wg sync.WaitGroup
	paths := make([]string, 2)
	errs := make([]error, 2)
	for i := range paths {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			paths[index], errs[index] = library.Install(context.Background())
		}(i)
	}
	wg.Wait()
	for i, err := range errs {
		if err != nil {
			t.Fatalf("install %d: %v", i, err)
		}
		if paths[i] != filepath.Join(root, typescriptProfileID) {
			t.Fatalf("install path %q", paths[i])
		}
	}
	if paths[0] != paths[1] {
		t.Fatalf("concurrent install paths differ: %q and %q", paths[0], paths[1])
	}
	invocations, err := os.ReadFile(counter)
	if err != nil || string(invocations) != "x" {
		t.Fatalf("npm invocations = %q, error = %v; want one execution", invocations, err)
	}
	profile, err := TypeScriptServerConfig(root)
	if err != nil {
		t.Fatal(err)
	}
	if profile.ID != typescriptProfileID || len(profile.Command) != 3 || profile.Command[0] == "" || profile.Command[1] != filepath.Join(paths[0], "node_modules", "typescript-language-server", "lib", "cli.mjs") || profile.Command[2] != "--stdio" {
		t.Fatalf("unexpected profile: %#v", profile)
	}
	wantExtensions := []string{".ts", ".tsx", ".js", ".jsx", ".mts", ".cts", ".mjs", ".cjs"}
	if strings.Join(profile.Extensions, ",") != strings.Join(wantExtensions, ",") {
		t.Fatalf("profile extensions = %v", profile.Extensions)
	}
	for _, marker := range []string{"tsconfig.json", "jsconfig.json", "package.json"} {
		if !contains(profile.RootMarkers, marker) {
			t.Fatalf("profile does not recognize root marker %q", marker)
		}
	}
}

// A failed or mis-versioned package remains private staging material, never a published profile.
func TestTypeScriptLibrary_FailedInstallDoesNotPublish(t *testing.T) {
	requireLinuxTSProfile(t)
	for _, tc := range []struct {
		name string
		fake string
	}{
		{name: "npm failure", fake: "failure"},
		{name: "validation failure", fake: "wrong-version"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			root := t.TempDir()
			var npm string
			if tc.fake == "failure" {
				npm = fakeNPM(t, true)
			} else {
				npm = fakeNPMWrongVersion(t)
			}
			if _, err := (TypeScriptLibrary{Directory: root, NPM: npm}).Install(context.Background()); err == nil {
				t.Fatal("expected install failure")
			}
			if _, err := os.Lstat(filepath.Join(root, typescriptProfileID)); !errors.Is(err, os.ErrNotExist) {
				t.Fatalf("failed install published a profile: %v", err)
			}
			entries, err := os.ReadDir(root)
			if err != nil {
				t.Fatal(err)
			}
			for _, entry := range entries {
				if strings.HasPrefix(entry.Name(), ".typescript-staging-") {
					t.Fatalf("failed install left staging directory %q", entry.Name())
				}
			}
		})
	}
}

func TestTypeScriptLibrary_RejectsInvalidSetupAndCancelledLockWait(t *testing.T) {
	requireLinuxTSProfile(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := (TypeScriptLibrary{Directory: t.TempDir(), NPM: fakeNPM(t, false)}).Install(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled install error = %v", err)
	}
	if _, err := (TypeScriptLibrary{Directory: "relative", NPM: fakeNPM(t, false)}).Install(context.Background()); err == nil {
		t.Fatal("relative library directory was accepted")
	}
	if _, err := (TypeScriptLibrary{Directory: t.TempDir(), NPM: filepath.Join(t.TempDir(), "missing-npm")}).Install(context.Background()); err == nil {
		t.Fatal("missing npm executable was accepted")
	}
	file := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(file, []byte("file"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := (TypeScriptLibrary{Directory: file, NPM: fakeNPM(t, false)}).Install(context.Background()); err == nil {
		t.Fatal("file was accepted as a library directory")
	}

	badLockRoot := t.TempDir()
	lockTarget := filepath.Join(t.TempDir(), "lock-target")
	if err := os.WriteFile(lockTarget, []byte("lock"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(lockTarget, filepath.Join(badLockRoot, native.InstallLockFileName)); err != nil {
		t.Fatal(err)
	}
	if _, err := (TypeScriptLibrary{Directory: badLockRoot, NPM: fakeNPM(t, false)}).Install(context.Background()); err == nil {
		t.Fatal("symlinked library lock was accepted")
	}

	root := t.TempDir()
	lock, err := native.AcquireDirectoryLock(root, native.InstallLockFileName)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := lock.Close(); err != nil {
			t.Errorf("library lock release failed: %v", err)
		}
	}()
	waitCtx, stop := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer stop()
	if _, err := (TypeScriptLibrary{Directory: root, NPM: fakeNPM(t, false)}).Install(waitCtx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("cancelled lock wait error = %v", err)
	}
}

func TestTypeScriptServerConfig_RequiresInstalledMaterialAndNode(t *testing.T) {
	requireLinuxTSProfile(t)
	if _, err := TypeScriptServerConfig("relative"); err == nil {
		t.Fatal("relative library directory was accepted")
	}
	if _, err := TypeScriptServerConfig(filepath.Join(t.TempDir(), "missing")); err == nil {
		t.Fatal("missing library directory was accepted")
	}
	root := t.TempDir()
	if _, err := (TypeScriptLibrary{Directory: root, NPM: fakeNPM(t, false)}).Install(context.Background()); err != nil {
		t.Fatal(err)
	}
	oldPath, wasSet := os.LookupEnv("PATH")
	t.Cleanup(func() {
		if wasSet {
			_ = os.Setenv("PATH", oldPath)
		} else {
			_ = os.Unsetenv("PATH")
		}
	})
	if err := os.Setenv("PATH", t.TempDir()); err != nil {
		t.Fatal(err)
	}
	if _, err := TypeScriptServerConfig(root); err == nil || !strings.Contains(err.Error(), "Node.js 18 or later") {
		t.Fatalf("missing Node.js error = %v", err)
	}
	for _, version := range []string{"v17.9.0", "node-version"} {
		nodeDir := t.TempDir()
		node := filepath.Join(nodeDir, "node")
		if err := os.WriteFile(node, []byte("#!/bin/sh\nprintf '%s\\n' '"+version+"'\n"), 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.Setenv("PATH", nodeDir); err != nil {
			t.Fatal(err)
		}
		if _, err := TypeScriptServerConfig(root); err == nil || !strings.Contains(err.Error(), "Node.js 18 or later") {
			t.Fatalf("Node.js %q error = %v", version, err)
		}
	}
}

func TestTypeScriptLibrary_RejectsIncompleteAndCorruptInstalledProfiles(t *testing.T) {
	requireLinuxTSProfile(t)
	for _, tc := range []struct {
		name              string
		includeBinary     bool
		includeEntrypoint bool
	}{
		{name: "missing executable", includeEntrypoint: true},
		{name: "missing entrypoint", includeBinary: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			root := t.TempDir()
			if _, err := (TypeScriptLibrary{Directory: root, NPM: fakeNPMIncomplete(t, tc.includeBinary, tc.includeEntrypoint)}).Install(context.Background()); err == nil {
				t.Fatal("incomplete package material was published")
			}
			if _, err := os.Lstat(filepath.Join(root, typescriptProfileID)); !errors.Is(err, os.ErrNotExist) {
				t.Fatalf("incomplete package was published: %v", err)
			}
		})
	}

	root := t.TempDir()
	profile, err := (TypeScriptLibrary{Directory: root, NPM: fakeNPM(t, false)}).Install(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	packagePath := filepath.Join(profile, "node_modules", "typescript", "package.json")
	if err := os.WriteFile(packagePath, []byte(`{"version":"5.8.4"}`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := (TypeScriptLibrary{Directory: root, NPM: fakeNPM(t, false)}).Install(context.Background()); err == nil {
		t.Fatal("corrupt installed version was overwritten")
	}
	data, err := os.ReadFile(packagePath)
	if err != nil || string(data) != `{"version":"5.8.4"}` {
		t.Fatalf("installed profile changed after rejected upgrade: %q, %v", data, err)
	}
}

func fakeNPMIncomplete(t *testing.T, includeBinary, includeEntrypoint bool) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "npm")
	body := "#!/bin/sh\nset -eu\nmkdir -p node_modules/typescript-language-server/lib node_modules/typescript node_modules/.bin\n"
	body += "printf '{\"version\":\"4.3.4\"}' > node_modules/typescript-language-server/package.json\n"
	body += "printf '{\"version\":\"5.8.3\"}' > node_modules/typescript/package.json\n"
	if includeEntrypoint {
		body += "printf 'export {};\\n' > node_modules/typescript-language-server/lib/cli.mjs\n"
	}
	if includeBinary {
		body += "printf '#!/bin/sh\\nexit 0\\n' > node_modules/.bin/typescript-language-server\nchmod 755 node_modules/.bin/typescript-language-server\n"
	}
	if err := os.WriteFile(path, []byte(body), 0700); err != nil {
		t.Fatal(err)
	}
	return path
}

func fakeNPMWrongVersion(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "npm")
	body := "#!/bin/sh\nset -eu\nmkdir -p node_modules/typescript-language-server/lib node_modules/typescript node_modules/.bin\n"
	body += "printf '{\"version\":\"4.3.3\"}' > node_modules/typescript-language-server/package.json\n"
	body += "printf '{\"version\":\"5.8.3\"}' > node_modules/typescript/package.json\n"
	body += "printf 'export {};\\n' > node_modules/typescript-language-server/lib/cli.mjs\n"
	body += "printf '#!/bin/sh\\nexit 0\\n' > node_modules/.bin/typescript-language-server\nchmod 755 node_modules/.bin/typescript-language-server\n"
	if err := os.WriteFile(path, []byte(body), 0700); err != nil {
		t.Fatal(err)
	}
	return path
}

// Installed materials are shared, but workspace sessions retain distinct pool identities.
func TestTypeScriptProfile_TwoWorkspaceSessionsStayIsolated(t *testing.T) {
	requireLinuxTSProfile(t)
	root := t.TempDir()
	if _, err := (TypeScriptLibrary{Directory: root, NPM: fakeNPM(t, false)}).Install(context.Background()); err != nil {
		t.Fatal(err)
	}
	profile, err := TypeScriptServerConfig(root)
	if err != nil {
		t.Fatal(err)
	}
	firstEnvironment := NewFacade(nil, PoolOptions{})
	secondEnvironment := NewFacade(nil, PoolOptions{})
	defer firstEnvironment.Close()
	defer secondEnvironment.Close()
	declaration := config.LSPServerConfig{ID: profile.ID, Command: profile.Command, Extensions: profile.Extensions, RootMarkers: profile.RootMarkers, FirstMatchMarkers: profile.FirstMatchMarkers}
	for _, facade := range []*Facade{firstEnvironment, secondEnvironment} {
		prepared, err := facade.Prepare([]config.LSPServerConfig{declaration})
		if err != nil {
			t.Fatal(err)
		}
		if err := prepared.Publish(); err != nil {
			t.Fatal(err)
		}
	}
	firstService, releaseFirst := firstEnvironment.currentService()
	defer releaseFirst()
	secondService, releaseSecond := secondEnvironment.currentService()
	defer releaseSecond()
	if firstService == nil || secondService == nil || firstService == secondService || firstService.Pool == secondService.Pool {
		t.Fatal("separate Environment facades shared an LSP runtime session")
	}
	workspaceRoots := []string{filepath.Join(t.TempDir(), "one"), filepath.Join(t.TempDir(), "two")}
	var keys []string
	for _, workspace := range workspaceRoots {
		if err := os.MkdirAll(workspace, 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(workspace, "tsconfig.json"), []byte("{}"), 0600); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(workspace, "index.ts"), []byte("export const value = 1;"), 0600); err != nil {
			t.Fatal(err)
		}
		declaration := config.LSPServerConfig{ID: profile.ID, Command: profile.Command, Extensions: profile.Extensions, RootMarkers: profile.RootMarkers, FirstMatchMarkers: profile.FirstMatchMarkers}
		discovery, err := NewDiscovery([]config.LSPServerConfig{declaration}, ResolverOptions{})
		if err != nil {
			t.Fatal(err)
		}
		server, err := discovery.RequireServer(filepath.Join(workspace, "index.ts"))
		if err != nil {
			t.Fatal(err)
		}
		root, err := discovery.WorkspaceRoot(server, filepath.Join(workspace, "index.ts"))
		if err != nil {
			t.Fatal(err)
		}
		keys = append(keys, poolKey(root, server))
	}
	if keys[0] == keys[1] {
		t.Fatal("different workspace sessions resolved to the same pool key")
	}
}

// Opt-in integration verifies that the pinned packages install and the resulting command handshakes.
func TestTypeScriptProfile_RealInstallAndLSPHandshake(t *testing.T) {
	requireLinuxTSProfile(t)
	if os.Getenv("KK_STUDIO_TEST_TS_LSP") != "1" {
		t.Skip("set KK_STUDIO_TEST_TS_LSP=1 to install pinned npm packages and run the real server")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	libraryDir := t.TempDir()
	if _, err := (TypeScriptLibrary{Directory: libraryDir}).Install(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(libraryDir, typescriptProfileID, ".npm-cache")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("npm cache escaped staging directory: %v", err)
	}
	profile, err := TypeScriptServerConfig(libraryDir)
	if err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	resolver := NewExecutableResolver(ResolverOptions{})
	executable, ok := resolver.Resolve(profile.Command[0])
	if !ok {
		t.Fatal("installed language server executable did not resolve")
	}
	server := ServerConfig{ID: profile.ID, Command: profile.Command, Extensions: profile.Extensions, RootMarkers: profile.RootMarkers, FirstMatchMarkers: profile.FirstMatchMarkers}
	processes := &execHelperSupervisor{}
	closed := false
	defer func() {
		if !closed {
			if err := processes.Close(context.Background()); err != nil {
				t.Errorf("server cleanup failed: %v", err)
			}
		}
	}()
	client, err := Launch(ctx, server, root, executable, processes)
	if err != nil {
		var bootstrap *LspBootstrapError
		if errors.As(err, &bootstrap) {
			t.Fatalf("server launch failed: %v; cause: %v", err, bootstrap.Cause)
		}
		t.Fatal(err)
	}
	if err := client.Initialize(ctx, 60*time.Second); err != nil {
		t.Fatal(err)
	}
	if err := client.Stop(time.Second); err != nil {
		t.Fatal(err)
	}
	if _, err := processes.processes[0].Wait(ctx); err != nil {
		t.Fatal(err)
	}
	if err := processes.Close(ctx); err != nil {
		t.Fatal(err)
	}
	closed = true
}

func requireLinuxTSProfile(t *testing.T) {
	t.Helper()
	if runtime.GOOS != "linux" || runtime.GOARCH != "amd64" {
		t.Skip("TypeScript LSP profile is verified only on linux/amd64")
	}
}

func contains(items []string, wanted string) bool {
	for _, item := range items {
		if item == wanted {
			return true
		}
	}
	return false
}
