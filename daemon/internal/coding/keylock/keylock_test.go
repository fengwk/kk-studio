package keylock

import (
	"sync"
	"testing"
	"time"
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

// A registered waiter keeps the key alive after the holder unlocks; the last unlock reclaims it.
func TestLocksReclaimAfterHolderAndWaiterLeave(t *testing.T) {
	var locks Locks
	firstUnlock := locks.Lock("package/version")
	waiterAcquired := make(chan struct{})
	releaseWaiter := make(chan struct{})
	waiterDone := make(chan struct{})
	go func() {
		unlock := locks.Lock("package/version")
		close(waiterAcquired)
		<-releaseWaiter
		unlock()
		close(waiterDone)
	}()
	// Wait for registration, not for a fixed delay, before releasing the holder.
	timeout := time.NewTimer(5 * time.Second)
	defer timeout.Stop()
	retry := time.NewTicker(time.Millisecond)
	defer retry.Stop()
waitForRegistration:
	for {
		locks.mu.Lock()
		refs := locks.entries["package/version"].refs
		locks.mu.Unlock()
		if refs == 2 {
			break
		}
		select {
		case <-waiterAcquired:
			t.Error("waiter acquired before the holder released")
			break waitForRegistration
		case <-timeout.C:
			t.Error("waiter did not register")
			break waitForRegistration
		case <-retry.C:
		}
	}
	firstUnlock()
	<-waiterAcquired
	if n := locks.Len(); n != 1 {
		t.Errorf("key reclaimed while waiter held it: len=%d", n)
	}
	close(releaseWaiter)
	<-waiterDone
	if locks.Len() != 0 {
		t.Fatalf("key retained after holder and waiter left: len=%d", locks.Len())
	}
}
