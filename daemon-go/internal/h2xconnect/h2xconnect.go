package h2xconnect

import (
	"os"
	"strings"
)

var original = os.Getenv("GODEBUG")

func init() {
	updated := enableExtendedConnect(original)
	if updated != original {
		_ = os.Setenv("GODEBUG", updated)
	}
}

func enableExtendedConnect(value string) string {
	for _, item := range strings.Split(value, ",") {
		if item == "http2xconnect=1" {
			return value
		}
	}
	if value == "" {
		return "http2xconnect=1"
	}
	return value + ",http2xconnect=1"
}

// OriginalGODEBUG returns the environment value that must be restored in child processes.
func OriginalGODEBUG() string {
	return original
}
