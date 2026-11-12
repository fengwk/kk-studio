//go:build windows

package native

import (
	"errors"
	"os"
	"strconv"
	"testing"

	"golang.org/x/sys/windows"
)

type failingWindowsPipeAPI struct {
	nativeWindowsPipeAPI
	createCount int
	failCreate  int
	setErr      error
	wrapFile    bool
	files       []*os.File
}

func (api *failingWindowsPipeAPI) CreatePipe(readHandle, writeHandle *windows.Handle, attributes *windows.SecurityAttributes, size uint32) error {
	api.createCount++
	if api.createCount == api.failCreate {
		return errors.New("injected pipe creation failure")
	}
	return api.nativeWindowsPipeAPI.CreatePipe(readHandle, writeHandle, attributes, size)
}

func (api *failingWindowsPipeAPI) SetHandleInformation(handle windows.Handle, mask, flags uint32) error {
	if api.setErr != nil {
		return api.setErr
	}
	return api.nativeWindowsPipeAPI.SetHandleInformation(handle, mask, flags)
}

func (api *failingWindowsPipeAPI) NewFile(handle uintptr, name string) *os.File {
	if !api.wrapFile {
		return nil
	}
	file := api.nativeWindowsPipeAPI.NewFile(handle, name)
	if file != nil {
		api.files = append(api.files, file)
	}
	return file
}

// TestWindowsHelperPipes_PartialCreationFailures checks each startup failure closes previously acquired handles.
func TestWindowsHelperPipes_PartialCreationFailures(t *testing.T) {
	for failAt := 1; failAt <= 4; failAt++ {
		t.Run(strconv.Itoa(failAt), func(t *testing.T) {
			api := &failingWindowsPipeAPI{failCreate: failAt, wrapFile: true}
			pipes, err := createWindowsHelperPipesWithAPI(api)
			if pipes != nil || err == nil {
				t.Fatalf("pipe creation result = %#v, %v", pipes, err)
			}
			for _, file := range api.files {
				if _, err := file.Stat(); err == nil {
					t.Fatalf("previous pipe endpoint %q remains open", file.Name())
				}
			}
		})
	}
}

// TestWindowsPipe_ClosesBothHandlesAfterConfigurationOrWrappingFailure checks both pre-publication cleanup paths.
func TestWindowsPipe_ClosesBothHandlesAfterConfigurationOrWrappingFailure(t *testing.T) {
	for _, test := range []struct {
		name string
		api  *failingWindowsPipeAPI
	}{
		{name: "handle configuration", api: &failingWindowsPipeAPI{setErr: errors.New("injected handle configuration failure")}},
		{name: "file wrapping", api: &failingWindowsPipeAPI{}},
	} {
		t.Run(test.name, func(t *testing.T) {
			file, child, err := windowsPipeWithAPI(true, test.api)
			if file != nil || child != 0 || err == nil {
				t.Fatalf("pipe result = %v, %d, %v", file, child, err)
			}
		})
	}
}
