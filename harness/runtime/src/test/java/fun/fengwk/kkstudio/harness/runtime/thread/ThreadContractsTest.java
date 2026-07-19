package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        () ->
            new AgentThread(
                0,
                1,
                1,
                ThreadStatus.IDLE,
                0,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                0,
                NOW,
                NOW));
    AgentThread thread =
        new AgentThread(
            1,
            2,
            3,
            ThreadStatus.RUNNING,
            0,
            9L,
            "agent",
            "model",
            "default",
            false,
            "token",
            NOW.plusSeconds(1),
            0,
            NOW,
            NOW);
    assertTrue(thread.isProcessing(NOW));
    assertFalse(thread.isProcessing(NOW.plusSeconds(2)));
  }

  @Test
  void stopCarriesNetworkIdempotencyIdentityAndRejectsInvalidIdentity() {
    ThreadStop stop = new ThreadStop(1, 2, "stop-request-1", NOW);

    assertEquals(1L, stop.id());
    assertEquals(2L, stop.threadId());
    assertEquals("stop-request-1", stop.clientRequestId());
    assertEquals(NOW, stop.createdAt());
    assertThrows(IllegalArgumentException.class, () -> new ThreadStop(0, 2, "request", NOW));
    assertThrows(IllegalArgumentException.class, () -> new ThreadStop(1, 0, "request", NOW));
    assertThrows(IllegalArgumentException.class, () -> new ThreadStop(1, 2, " ", NOW));
  }

  @Test
  void threadStatusParsesPersistedValuesAndIdentifiesRunnableStates() {
    assertEquals(ThreadStatus.RUNNING, ThreadStatus.fromValue("running"));
    assertEquals(ThreadStatus.WAITING, ThreadStatus.fromValue("WAITING"));
    assertEquals("retrying", ThreadStatus.RETRYING.value());
    assertTrue(ThreadStatus.RUNNING.isRunnable());
    assertTrue(ThreadStatus.WAITING.isRunnable());
    assertTrue(ThreadStatus.RETRYING.isRunnable());
    assertFalse(ThreadStatus.IDLE.isRunnable());
    assertFalse(ThreadStatus.FAILED.isRunnable());
    assertThrows(IllegalArgumentException.class, () -> ThreadStatus.fromValue("paused"));
  }

  @Test
  void inputStatusParsesWireValuesAndEnumNames() {
    assertEquals(ThreadInputStatus.QUEUED, ThreadInputStatus.fromValue("queued"));
    assertEquals(ThreadInputStatus.CANCELLED, ThreadInputStatus.fromValue("CANCELLED"));
    assertEquals("applied", ThreadInputStatus.APPLIED.value());
    assertThrows(IllegalArgumentException.class, () -> ThreadInputStatus.fromValue("discarded"));
  }

  @Test
  void inputTypesSeparateMessagesFromConfigurationAndRequireWireValues() {
    assertEquals(ThreadInputType.USER_MESSAGE, ThreadInputType.fromValue("user_message"));
    assertEquals("set_model", ThreadInputType.SET_MODEL.value());
    assertTrue(ThreadInputType.USER_MESSAGE.isMessage());
    assertFalse(ThreadInputType.USER_MESSAGE.isConfig());
    assertFalse(ThreadInputType.SET_MODEL.isMessage());
    assertTrue(ThreadInputType.SET_MODEL.isConfig());
    assertThrows(IllegalArgumentException.class, () -> ThreadInputType.fromValue("USER_MESSAGE"));
  }
}
