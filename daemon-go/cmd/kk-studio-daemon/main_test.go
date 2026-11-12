package main

import "testing"

// NAT-N-001, NAT-N-002
func TestRunInternalCommand_DispatchesNativeHelpers(t *testing.T) {
	for _, args := range [][]string{{"__native-helper"}, {"__lock-helper"}} {
		code, handled := runInternalCommand(args)
		if !handled {
			t.Fatalf("command %q was not dispatched", args[0])
		}
		if code != 2 {
			t.Fatalf("command %q exit code = %d, want invalid-argument code 2", args[0], code)
		}
	}
}

func TestRunInternalCommand_LeavesPublicCommandsAlone(t *testing.T) {
	for _, args := range [][]string{nil, {"--version"}} {
		if code, handled := runInternalCommand(args); handled || code != 0 {
			t.Fatalf("command %q result = %d, %t", args, code, handled)
		}
	}
}
