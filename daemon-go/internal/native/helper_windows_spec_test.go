//go:build windows

package native

import (
	"testing"

	"golang.org/x/sys/windows"
)

// TestCreateSuspendedWindowsCommand_RejectsMissingStandardHandles ensures invalid stdio never reaches CreateProcess.
func TestCreateSuspendedWindowsCommand_RejectsMissingStandardHandles(t *testing.T) {
	for _, test := range []struct {
		name   string
		stdout windows.Handle
		stderr windows.Handle
	}{
		{name: "zero stdout", stdout: 0, stderr: 21},
		{name: "invalid stderr", stdout: 20, stderr: windows.InvalidHandle},
	} {
		t.Run(test.name, func(t *testing.T) {
			api := &fakeWindowsCommandAPI{stdout: test.stdout, stderr: test.stderr}
			if _, err := createSuspendedWindowsCommandWithAPI(api, 10, &launchSpec{Args: []string{"tool.exe"}}, 19); err == nil {
				t.Fatal("CreateProcess accepted an invalid standard handle")
			}
			if api.attributes != nil {
				t.Fatal("attribute setup began despite an invalid standard handle")
			}
		})
	}
}
