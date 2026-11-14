package h2xconnect

import (
	"os"
	"strings"
)

// inherited records the GODEBUG value the process started with, before init appends the setting.
var inherited = os.Getenv("GODEBUG")

func init() {
	updated := enableExtendedConnect(inherited)
	if updated != inherited {
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
