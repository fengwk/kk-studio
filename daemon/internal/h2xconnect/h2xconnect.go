package h2xconnect

import (
	"os"
	"strings"
)

// inherited records the GODEBUG value the process started with, before init appends the setting.
var inherited = os.Getenv("GODEBUG")

func init() {
	updated := WithSetting(inherited)
	if updated != inherited {
		_ = os.Setenv("GODEBUG", updated)
	}
}

// WithSetting returns a GODEBUG value that enables http2xconnect without discarding the settings it
// already contains.
func WithSetting(value string) string {
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

// WithEnvironment returns environment with GODEBUG enabled for http2xconnect, replacing its existing
// GODEBUG entry in place and appending one when it has none.
func WithEnvironment(environment []string) []string {
	result := make([]string, 0, len(environment)+1)
	found := false
	for _, entry := range environment {
		if strings.HasPrefix(entry, "GODEBUG=") {
			found = true
			result = append(result, "GODEBUG="+WithSetting(strings.TrimPrefix(entry, "GODEBUG=")))
			continue
		}
		result = append(result, entry)
	}
	if !found {
		result = append(result, "GODEBUG=http2xconnect=1")
	}
	return result
}
