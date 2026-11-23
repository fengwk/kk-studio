//go:build windows

package native

import (
	"errors"
	"fmt"
	"golang.org/x/sys/windows"
	"os"
	"sort"
	"strings"
	"sync"
	"unicode/utf16"
	"unsafe"
)

type windowsHelperPipes struct {
	owner        windows.Handle
	commandInput windows.Handle
	output       windows.Handle
	errorOutput  windows.Handle
	ownerFile    *os.File
	inputFile    *os.File
	outputFile   *os.File
	errorFile    *os.File
	// childHandles are inherited by the helper and closed once it has started.
	childHandles []windows.Handle
	// supervisorHandles are inherited by the helper but stay open here, because the
	// supervisor owns them for the whole command lifetime.
	supervisorHandles []windows.Handle
}

func createWindowsHelperPipes() (*windowsHelperPipes, error) {
	return createWindowsHelperPipesWithAPI(nativeWindowsPipeAPI{})
}

func createWindowsHelperPipesWithAPI(api windowsPipeAPI) (*windowsHelperPipes, error) {
	pipes := &windowsHelperPipes{}
	ownerFile, ownerChild, err := windowsPipeWithAPI(true, api)
	if err != nil {
		return nil, err
	}
	pipes.ownerFile, pipes.owner = ownerFile, ownerChild
	inputFile, inputChild, err := windowsPipeWithAPI(true, api)
	if err != nil {
		pipes.close()
		return nil, err
	}
	pipes.inputFile, pipes.commandInput = inputFile, inputChild
	outputFile, outputChild, err := windowsPipeWithAPI(false, api)
	if err != nil {
		pipes.close()
		return nil, err
	}
	pipes.outputFile, pipes.output = outputFile, outputChild
	errorFile, errorChild, err := windowsPipeWithAPI(false, api)
	if err != nil {
		pipes.close()
		return nil, err
	}
	pipes.errorFile, pipes.errorOutput = errorFile, errorChild
	pipes.childHandles = []windows.Handle{ownerChild, inputChild, outputChild, errorChild}
	return pipes, nil
}

type windowsPipeAPI interface {
	CreatePipe(*windows.Handle, *windows.Handle, *windows.SecurityAttributes, uint32) error
	SetHandleInformation(windows.Handle, uint32, uint32) error
	CloseHandle(windows.Handle) error
	NewFile(uintptr, string) *os.File
}

type nativeWindowsPipeAPI struct{}

func (nativeWindowsPipeAPI) CreatePipe(readHandle, writeHandle *windows.Handle, attributes *windows.SecurityAttributes, size uint32) error {
	return windows.CreatePipe(readHandle, writeHandle, attributes, size)
}

func (nativeWindowsPipeAPI) SetHandleInformation(handle windows.Handle, mask, flags uint32) error {
	return windows.SetHandleInformation(handle, mask, flags)
}

func (nativeWindowsPipeAPI) CloseHandle(handle windows.Handle) error {
	return windows.CloseHandle(handle)
}

func (nativeWindowsPipeAPI) NewFile(handle uintptr, name string) *os.File {
	return os.NewFile(handle, name)
}

func windowsPipeWithAPI(parentWrites bool, api windowsPipeAPI) (*os.File, windows.Handle, error) {
	sa := windows.SecurityAttributes{Length: uint32(unsafe.Sizeof(windows.SecurityAttributes{})), InheritHandle: 1}
	var readHandle, writeHandle windows.Handle
	if err := api.CreatePipe(&readHandle, &writeHandle, &sa, 0); err != nil {
		return nil, 0, err
	}
	parent, child := readHandle, writeHandle
	if parentWrites {
		parent, child = writeHandle, readHandle
	}
	if err := api.SetHandleInformation(parent, windows.HANDLE_FLAG_INHERIT, 0); err != nil {
		_ = api.CloseHandle(readHandle)
		_ = api.CloseHandle(writeHandle)
		return nil, 0, err
	}
	file := api.NewFile(uintptr(parent), "native-pipe")
	if file == nil {
		_ = api.CloseHandle(readHandle)
		_ = api.CloseHandle(writeHandle)
		return nil, 0, errors.New("cannot wrap a native pipe handle")
	}
	return file, child, nil
}

// useNullStdin replaces the command-input pipe with the null device, so the supervised
// command reads end-of-file instead of waiting for input the daemon never writes.
func (p *windowsHelperPipes) useNullStdin() error {
	file, err := openNullStdin()
	if err != nil {
		return err
	}
	handle := windows.Handle(file.Fd())
	if err := windows.SetHandleInformation(handle, windows.HANDLE_FLAG_INHERIT, windows.HANDLE_FLAG_INHERIT); err != nil {
		_ = file.Close()
		return err
	}
	pipeHandle := p.commandInput
	_ = p.inputFile.Close()
	if err := windows.CloseHandle(pipeHandle); err != nil {
		_ = file.Close()
		return err
	}
	p.inputFile, p.commandInput = file, handle
	p.childHandles = dropWindowsHandle(p.childHandles, pipeHandle)
	p.supervisorHandles = append(p.supervisorHandles, handle)
	return nil
}

func dropWindowsHandle(handles []windows.Handle, target windows.Handle) []windows.Handle {
	kept := handles[:0]
	for _, handle := range handles {
		if handle != target {
			kept = append(kept, handle)
		}
	}
	return kept
}

// handlesForHelper returns every handle the helper inherits when it is created.
func (p *windowsHelperPipes) handlesForHelper() []windows.Handle {
	handles := make([]windows.Handle, 0, len(p.childHandles)+len(p.supervisorHandles))
	handles = append(handles, p.childHandles...)
	return append(handles, p.supervisorHandles...)
}

func (p *windowsHelperPipes) closeChildHandles() error {
	var failures []error
	open := make([]windows.Handle, 0, len(p.childHandles))
	for _, handle := range p.childHandles {
		if err := windows.CloseHandle(handle); err != nil {
			failures = append(failures, err)
			open = append(open, handle)
		}
	}
	p.childHandles = open
	return errors.Join(failures...)
}

func (p *windowsHelperPipes) close() {
	_ = p.closeChildHandles()
	for _, file := range []*os.File{p.ownerFile, p.inputFile, p.outputFile, p.errorFile} {
		if file != nil {
			_ = file.Close()
		}
	}
}

func windowsHelperCommandLine(executable, stateDir string) string {
	return ComposeCommandLine([]string{executable, "__native-helper", stateDir})
}

func startWindowsHelper(stateDir string, pipes *windowsHelperPipes, env []string) (windows.ProcessInformation, error) {
	executable, err := os.Executable()
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	app, err := windows.UTF16PtrFromString(executable)
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	line, err := windows.UTF16PtrFromString(windowsHelperCommandLine(executable, stateDir))
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	currentDir, err := os.Getwd()
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	currentDir16, err := windows.UTF16PtrFromString(currentDir)
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	environment := windowsEnvironmentBlock(env)
	attributes, err := windows.NewProcThreadAttributeList(1)
	if err != nil {
		return windows.ProcessInformation{}, err
	}
	defer attributes.Delete()
	inherited := pipes.handlesForHelper()
	if err := attributes.Update(windows.PROC_THREAD_ATTRIBUTE_HANDLE_LIST, unsafe.Pointer(&inherited[0]), uintptr(len(inherited))*unsafe.Sizeof(inherited[0])); err != nil {
		return windows.ProcessInformation{}, err
	}
	var startup windows.StartupInfoEx
	startup.Cb = uint32(unsafe.Sizeof(startup))
	startup.Flags = windows.STARTF_USESTDHANDLES
	startup.StdInput, startup.StdOutput, startup.StdErr = pipes.owner, pipes.output, pipes.errorOutput
	startup.ProcThreadAttributeList = attributes.List()
	var information windows.ProcessInformation
	flags := uint32(windows.EXTENDED_STARTUPINFO_PRESENT | windows.CREATE_UNICODE_ENVIRONMENT)
	if err := windows.CreateProcess(app, line, nil, nil, true, flags, &environment[0], currentDir16, &startup.StartupInfo, &information); err != nil {
		return windows.ProcessInformation{}, fmt.Errorf("cannot create the process scope helper: %w", err)
	}
	return information, nil
}

// helperEnvironment returns the environment the scope helper inherits and passes on to
// the command. Env is an overlay on the daemon environment by default and the whole
// environment in replacement mode; both are filtered identically. Replacement mode keeps
// SystemRoot because process creation depends on it.
func helperEnvironment(spec ProcessSpec) []string {
	base := os.Environ()
	if spec.ReplaceEnv {
		base = nil
	}
	env := mergeWindowsEnvironment(base, spec.Env)
	if spec.ReplaceEnv && !environmentHasKey(env, "SystemRoot") {
		if systemRoot := os.Getenv("SystemRoot"); systemRoot != "" {
			env = mergeWindowsEnvironment(env, []string{"SystemRoot=" + systemRoot})
		}
	}
	return env
}

func environmentHasKey(entries []string, key string) bool {
	for _, entry := range entries {
		if name, _, ok := strings.Cut(entry, "="); ok && strings.EqualFold(name, key) {
			return true
		}
	}
	return false
}

func mergeWindowsEnvironment(base, overrides []string) []string {
	values := make(map[string]string, len(base)+len(overrides))
	for _, entry := range append(append([]string(nil), base...), overrides...) {
		key, value, ok := strings.Cut(entry, "=")
		if !ok || key == "" || strings.ContainsRune(entry, '\x00') || strings.EqualFold(key, "NODE_CHANNEL_FD") {
			continue
		}
		values[strings.ToUpper(key)] = key + "=" + value
	}
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	result := make([]string, 0, len(keys))
	for _, key := range keys {
		result = append(result, values[key])
	}
	return result
}

func windowsEnvironmentBlock(entries []string) []uint16 {
	values := make(map[string]string, len(entries))
	for _, entry := range entries {
		key, value, ok := strings.Cut(entry, "=")
		if !ok || key == "" || strings.ContainsRune(entry, '\x00') || strings.ContainsRune(value, '\x00') || strings.ContainsRune(key, '=') || strings.EqualFold(key, "NODE_CHANNEL_FD") {
			continue
		}
		values[strings.ToUpper(key)] = key + "=" + value
	}
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	items := make([]string, 0, len(keys))
	for _, key := range keys {
		items = append(items, values[key])
	}
	block := strings.Join(items, "\x00") + "\x00\x00"
	return utf16.Encode([]rune(block))
}

type windowsManagedPipe struct {
	file      *os.File
	closeOnce sync.Once
	closeErr  error
}

func (p *windowsManagedPipe) Read(data []byte) (int, error) { return p.file.Read(data) }

func (p *windowsManagedPipe) Write(data []byte) (int, error) { return p.file.Write(data) }

func (p *windowsManagedPipe) Close() error {
	p.closeOnce.Do(func() { p.closeErr = p.file.Close() })
	return p.closeErr
}
