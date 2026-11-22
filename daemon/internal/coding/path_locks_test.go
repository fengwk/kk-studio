package coding

import (
	"path/filepath"
	"strconv"
	"testing"
)

// Distinct completed writes or edits must not leave path lock entries behind.
func TestPathLocksReleaseUnusedKeys(t *testing.T) {
	dir := t.TempDir()
	for i := range 1024 {
		unlock := lockPath(filepath.Join(dir, strconv.Itoa(i)))
		unlock()
	}
	if got := pathLocks.Len(); got != 0 {
		t.Fatalf("retained %d path locks", got)
	}
}
