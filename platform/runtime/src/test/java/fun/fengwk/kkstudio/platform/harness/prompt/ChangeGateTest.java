package fun.fengwk.kkstudio.platform.harness.prompt;

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
    gate.version();
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
                    gate.version();
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                });
    // deadline 使用饱和加法，超大 timeout 仍等待 signal 而不是立即超时。
    assertTrue(gate.awaitChange(since, Long.MAX_VALUE), "overflow must not cause immediate false");
    signaller.join();
  }

  @Test
  void awaitChangeWakesOnlyAfterVersionSignal() throws Exception {
    ChangeGate gate = new ChangeGate();
    ChangeGate.State since = gate.snapshot();
    assertFalse(gate.awaitChange(since, 0L), "no signal yet");
    gate.version();
    assertTrue(gate.awaitChange(since, 0L), "version signal must wake");
    // 已消费的信号不应重复唤醒：同一个 since 为过去态，新快照与即时检查必须回到 false。
    ChangeGate.State current = gate.snapshot();
    assertFalse(gate.awaitChange(current, 0L), "no further signal yet");
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
