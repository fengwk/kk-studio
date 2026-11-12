// Package h2xconnect enables RFC 8441 Extended CONNECT for the Go 1.27 standard-library
// HTTP/2 server. Go 1.27 snapshots GODEBUG during net/http initialization, so this package
// must initialize before net/http; its import path sorts before net/... in importing packages.
// It also exposes the original GODEBUG value for restoring the child-process environment.
// Delete this compatibility package when Go 1.28 provides public Extended CONNECT configuration.
package h2xconnect
