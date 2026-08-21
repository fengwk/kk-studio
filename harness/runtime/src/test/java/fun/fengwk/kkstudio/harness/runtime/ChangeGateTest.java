package fun.fengwk.kkstudio.harness.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/** ChangeGate 限时等待的边界语义：非正 timeout 立即检查、超大 timeout 不因溢出提前返回、无 busy-loop。 */
class ChangeGateTest {

  @Test
  void awaitChangeDoesNotWaitForNonPositiveTimeout() throws Exception {
    ChangeGate gate = new ChangeGate();
    ChangeGate.State since = gate.snapshot();
    long start = System.nanoTime();
    assertFalse(gate.awaitChange(since, 0L), "no signal yet");
    assertFalse(gate.awaitChange(since, -1L), "no signal yet");
    assertTrue(
        Duration.ofNanos(System.nanoTime() - start).toMillis() < 100,
        "non-positive timeout must not wait");
    gate.revision();
    // 已发生的 signal 在非正 timeout 的立即检查中必须可见（不能直接返回 false）。
    assertTrue(gate.awaitChange(since, 0L));
    assertTrue(gate.awaitChange(since, -1L));
    assertTrue(gate.awaitChange(since, Long.MIN_VALUE));
  }

  @Test
  void awaitChangeWithOverflowTimeoutStillWakesOnSignal() throws Exception {
    ChangeGate gate = new ChangeGate();
    ChangeGate.State since = gate.snapshot();
    Thread signaller =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    Thread.sleep(50);
                    gate.revision();
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                });
    // 修复前 deadline 溢出为负会立即返回 false；饱和加法后必须等到 signal 才返回 true。
    assertTrue(gate.awaitChange(since, Long.MAX_VALUE), "overflow must not cause immediate false");
    signaller.join();
  }

  @Test
  void awaitChangeWakesOnAnyCategoryAndMergesSignals() throws Exception {
    ChangeGate gate = new ChangeGate();
    ChangeGate.State since = gate.snapshot();
    gate.descendants();
    assertTrue(gate.awaitChange(since, 0L), "descendants signal must wake");
    since = gate.snapshot();
    gate.cancel();
    assertTrue(gate.awaitChange(since, 0L), "cancel signal must wake");
  }

  @Test
  void awaitChangeTimesOutWithoutSignalAndPropagatesInterruption() throws Exception {
    ChangeGate gate = new ChangeGate();
    ChangeGate.State since = gate.snapshot();
    long start = System.nanoTime();
    assertFalse(gate.awaitChange(since, 50_000_000L), "must time out without signal");
    assertTrue(
        Duration.ofNanos(System.nanoTime() - start).toMillis() >= 40,
        "must actually wait for the timeout");
    Thread waiter =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    gate.awaitChange(gate.snapshot(), 60_000_000_000L);
                  } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                  }
                });
    Thread.sleep(50);
    waiter.interrupt();
    waiter.join(2_000);
    assertTrue(waiter.isInterrupted(), "interruption must propagate to the caller");
  }
}
