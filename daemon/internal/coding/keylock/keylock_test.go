package keylock

import (
	"sync"
	"testing"
)

// Contenders must share the same mutex even as keys are reclaimed and reused.
func TestLocksSerializeContendersAndReclaimKey(t *testing.T) {
	const workers, iterations = 16, 100
	var locks Locks
	var wg sync.WaitGroup
	count := 0
	start := make(chan struct{})
	for range workers {
		wg.Go(func() {
			<-start
			for range iterations {
				unlock := locks.Lock("shared")
				count++ // Also checked by the race detector.
				unlock()
			}
		})
	}
	close(start)
	wg.Wait()
	if count != workers*iterations || locks.Len() != 0 {
		t.Fatalf("count=%d, retained keys=%d", count, locks.Len())
	}
}
