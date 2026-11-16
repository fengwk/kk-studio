package config

import "strings"

// lspServerFields is one declared language server as both the config file and the environment
// snapshot describe it, before validation.
type lspServerFields struct {
	id                string
	command           []string
	extensions        []string
	rootMarkers       []string
	firstMatchMarkers []string
}

// normalize lower-cases the extensions and unifies the marker separators, so the rules below see
// the values the applied snapshot keeps.
func (f *lspServerFields) normalize() {
	f.extensions = normalizeExtensions(f.extensions)
	f.rootMarkers = normalizeMarkers(f.rootMarkers)
	f.firstMatchMarkers = normalizeMarkers(f.firstMatchMarkers)
}

// lspServerViolation names the rule a declared server breaks, so each caller can map it to its
// own error vocabulary.
type lspServerViolation int

const (
	lspServerOK lspServerViolation = iota
	lspServerInvalidLists
	lspServerInvalidExtension
	lspServerInvalidMarker
)

// check reports the first rule the normalized server breaks. The identifier rule is left to the
// caller because a config file checks it against the declaring key and the environment snapshot
// against the message field.
func (f lspServerFields) check() lspServerViolation {
	if !validStringList(f.command, true) || !validStringList(f.extensions, true) || !validStringList(f.rootMarkers, false) || !validStringList(f.firstMatchMarkers, false) {
		return lspServerInvalidLists
	}
	for _, extension := range f.extensions {
		if len(extension) < 2 || !strings.HasPrefix(extension, ".") || hasControl(extension) || strings.ContainsAny(extension, `/\\`) {
			return lspServerInvalidExtension
		}
	}
	for _, marker := range append(append([]string(nil), f.rootMarkers...), f.firstMatchMarkers...) {
		if !validRelativePath(marker) {
			return lspServerInvalidMarker
		}
	}
	return lspServerOK
}
