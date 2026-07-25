package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** ThreadInput 字段约束、appliedAt 状态不变量与分类辅助方法测试。 */
class ThreadInputTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant APPLIED = NOW.plusSeconds(1);

  /** 仅用于测试的最小 ThreadInputPayload。 */
  private record TestPayload(ThreadInputType type) implements ThreadInputPayload {}

  @Test
  void appliedInputRequiresAppliedAt() {
    ThreadInputPayload payload = new TestPayload(ThreadInputType.USER_MESSAGE);
    ThreadInput input =
        new ThreadInput(
            1L,
            1L,
            1L,
            ThreadInputType.USER_MESSAGE,
            payload,
            "key",
            InputStatus.APPLIED,
            NOW,
            APPLIED);

    assertEquals(APPLIED, input.appliedAt());
    assertTrue(input.isTerminal());
    assertFalse(input.isQueued());
  }

  @Test
  void cancelledInputForbidsAppliedAt() {
    ThreadInputPayload payload = new TestPayload(ThreadInputType.USER_MESSAGE);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadInput(
                1L,
                1L,
                1L,
                ThreadInputType.USER_MESSAGE,
                payload,
                "key",
                InputStatus.CANCELLED,
                NOW,
                APPLIED));
  }

  @Test
  void appliedInputRejectsMissingAppliedAt() {
    ThreadInputPayload payload = new TestPayload(ThreadInputType.USER_MESSAGE);
    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadInput(
                1L,
                1L,
                1L,
                ThreadInputType.USER_MESSAGE,
                payload,
                "key",
                InputStatus.APPLIED,
                NOW,
                null));
  }

  @Test
  void queuedInputForbidsAppliedAt() {
    ThreadInputPayload payload = new TestPayload(ThreadInputType.USER_MESSAGE);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadInput(
                1L,
                1L,
                1L,
                ThreadInputType.USER_MESSAGE,
                payload,
                "key",
                InputStatus.QUEUED,
                NOW,
                APPLIED));
  }

  @Test
  void queuedInputAllowsNullAppliedAt() {
    ThreadInputPayload payload = new TestPayload(ThreadInputType.USER_MESSAGE);
    ThreadInput input =
        new ThreadInput(
            1L,
            1L,
            1L,
            ThreadInputType.USER_MESSAGE,
            payload,
            "key",
            InputStatus.QUEUED,
            NOW,
            null);

    assertTrue(input.isQueued());
    assertFalse(input.isTerminal());
    assertNull(input.appliedAt());
  }

  @Test
  void cancelledInputAllowsNullAppliedAt() {
    ThreadInputPayload payload = new TestPayload(ThreadInputType.USER_MESSAGE);
    ThreadInput input =
        new ThreadInput(
            1L,
            1L,
            1L,
            ThreadInputType.USER_MESSAGE,
            payload,
            "key",
            InputStatus.CANCELLED,
            NOW,
            null);

    assertTrue(input.isTerminal());
    assertNull(input.appliedAt());
  }

  @Test
  void appliedInputRejectsAppliedAtBeforeCreatedAt() {
    ThreadInputPayload payload = new TestPayload(ThreadInputType.USER_MESSAGE);
    Instant before = NOW.minusSeconds(1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadInput(
                1L,
                1L,
                1L,
                ThreadInputType.USER_MESSAGE,
                payload,
                "key",
                InputStatus.APPLIED,
                NOW,
                before));
  }

  @Test
  void rejectsNonPositiveIds() {
    ThreadInputPayload payload = new TestPayload(ThreadInputType.USER_MESSAGE);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadInput(
                0L,
                1L,
                1L,
                ThreadInputType.USER_MESSAGE,
                payload,
                "key",
                InputStatus.QUEUED,
                NOW,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadInput(
                1L,
                0L,
                1L,
                ThreadInputType.USER_MESSAGE,
                payload,
                "key",
                InputStatus.QUEUED,
                NOW,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadInput(
                1L,
                1L,
                0L,
                ThreadInputType.USER_MESSAGE,
                payload,
                "key",
                InputStatus.QUEUED,
                NOW,
                null));
  }

  @Test
  void rejectsBlankIdempotencyKey() {
    ThreadInputPayload payload = new TestPayload(ThreadInputType.USER_MESSAGE);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadInput(
                1L,
                1L,
                1L,
                ThreadInputType.USER_MESSAGE,
                payload,
                "",
                InputStatus.QUEUED,
                NOW,
                null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadInput(
                1L,
                1L,
                1L,
                ThreadInputType.USER_MESSAGE,
                payload,
                "  ",
                InputStatus.QUEUED,
                NOW,
                null));
    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadInput(
                1L,
                1L,
                1L,
                ThreadInputType.USER_MESSAGE,
                payload,
                null,
                InputStatus.QUEUED,
                NOW,
                null));
  }

  @Test
  void rejectsPayloadTypeMismatch() {
    ThreadInputPayload payload = new TestPayload(ThreadInputType.USER_MESSAGE);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadInput(
                1L,
                1L,
                1L,
                ThreadInputType.SET_AGENT,
                payload,
                "key",
                InputStatus.QUEUED,
                NOW,
                null));
  }
}
