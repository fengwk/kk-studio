// Package h2xconnect prepares the http2xconnect GODEBUG value for Go 1.27.
// The runtime reads GODEBUG before package initialization, so the setting must be present
// before process startup to affect net/http. This package's init only updates the environment
// inherited by child processes; it cannot configure the current process. OriginalGODEBUG
// exposes the value to restore when a child should not inherit this setting.
// Delete this compatibility package when Go 1.28 provides public Extended CONNECT configuration.
package h2xconnect
