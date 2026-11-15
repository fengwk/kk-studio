// Package native provides OS-specific process-tree supervision, locks, and private files.
// Capability and IDE packages consume its process contract; platform-specific behavior
// belongs in files selected by GOOS rather than callers.
package native
