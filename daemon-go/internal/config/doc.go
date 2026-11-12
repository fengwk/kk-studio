// Package config owns daemon configuration parsing, host/platform facts, and the release version.
// It is the boundary between untrusted startup input and immutable runtime configuration;
// callers consume validated values and must not duplicate parsing rules.
package config
