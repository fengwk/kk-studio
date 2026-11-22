package ide

import (
	"strconv"
	"testing"
)

// Completed claim and view operations must not leave lock entries behind.
func TestServiceLocksReleaseUnusedKeys(t *testing.T) {
	var s Service
	for i := range 1024 {
		key := strconv.Itoa(i)
		unlockClaim := s.claimLocks.Lock(key)
		unlockView := s.viewLocks.Lock(key)
		unlockView()
		unlockClaim()
	}
	if claims, views := s.claimLocks.Len(), s.viewLocks.Len(); claims != 0 || views != 0 {
		t.Fatalf("retained %d claim locks and %d view locks", claims, views)
	}
}
