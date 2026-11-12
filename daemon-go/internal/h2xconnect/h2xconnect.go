package h2xconnect

import (
	"os"
	"strings"
)

var original = os.Getenv("GODEBUG")

func init() {
	for _, item := range strings.Split(original, ",") {
		if item == "http2xconnect=1" {
			return
		}
	}
	if original == "" {
		_ = os.Setenv("GODEBUG", "http2xconnect=1")
	} else {
		_ = os.Setenv("GODEBUG", original+",http2xconnect=1")
	}
}

// OriginalGODEBUG returns the environment value that must be restored in child processes.
func OriginalGODEBUG() string {
	return original
}
