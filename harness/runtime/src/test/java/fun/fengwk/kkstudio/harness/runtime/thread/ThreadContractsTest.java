package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** Durable Thread 与 mailbox 值对象约束。 */
class ThreadContractsTest {
  private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

  @Test
  void agentThreadRequiresDurableStatusAndPositiveIdentity() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentThread(0, 1, 1, ThreadStatus.IDLE, 0, null, null, 0, NOW, NOW));
    AgentThread thread =
        new AgentThread(1, 2, 3, ThreadStatus.RUNNING, 0, "token", NOW.plusSeconds(1), 0, NOW, NOW);
    assertTrue(thread.isProcessing(NOW));
    assertFalse(thread.isProcessing(NOW.plusSeconds(2)));
  }

  @Test
  void inputResolutionFieldsFollowStateMachine() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadInput(
                1,
                2,
                1,
                ThreadInputType.USER_MESSAGE,
                "{}",
                "client-1",
                ThreadInputStatus.QUEUED,
                3L,
                NOW,
                null,
                NOW));
    ThreadInput cancelled =
        new ThreadInput(
            1,
            2,
            1,
            ThreadInputType.USER_MESSAGE,
            "{}",
            "client-1",
            ThreadInputStatus.CANCELLED,
            null,
            NOW,
            4L,
            NOW);
    assertTrue(cancelled.cancelled());
  }

  @Test
  void inputRequiresNonBlankClientMessageId() {
    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadInput(
                1,
                2,
                1,
                ThreadInputType.USER_MESSAGE,
                "{}",
                null,
                ThreadInputStatus.QUEUED,
                null,
                null,
                null,
                NOW));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadInput(
                1,
                2,
                1,
                ThreadInputType.USER_MESSAGE,
                "{}",
                " ",
                ThreadInputStatus.QUEUED,
                null,
                null,
                null,
                NOW));
  }

  @Test
  void stopRequiresNonBlankClientRequestId() {
    assertThrows(IllegalArgumentException.class, () -> new ThreadStop(1, 2, " ", NOW));
  }
}
