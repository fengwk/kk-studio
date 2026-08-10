package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** ThreadState durable 字段与不变量校验。 */
class ThreadStateTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void acceptsValidDurableFields() {
    ThreadState state = state(7L, 42L, true, 3L, 5L, CREATED);

    assertEquals(7L, state.id());
    assertEquals(42L, state.headEntryId());
    assertTrue(state.yoloEnabled());
    assertEquals(3L, state.nextCommandSequence());
    assertEquals(5L, state.revision());
    assertEquals(CREATED, state.createdAt());
    assertEquals(CREATED, state.updatedAt());

    ThreadState yoloDisabled = state(8L, 43L, false, 1L, 0L, CREATED.plusSeconds(2));
    assertFalse(yoloDisabled.yoloEnabled());
  }

  @Test
  void rejectsInvalidIdentityAndSequence() {
    assertThrows(IllegalArgumentException.class, () -> state(0L, 42L, false, 1L, 0L, CREATED));
    assertThrows(IllegalArgumentException.class, () -> state(-1L, 42L, false, 1L, 0L, CREATED));
    assertThrows(IllegalArgumentException.class, () -> state(7L, 0L, false, 1L, 0L, CREATED));
    assertThrows(IllegalArgumentException.class, () -> state(7L, -1L, false, 1L, 0L, CREATED));
    assertThrows(IllegalArgumentException.class, () -> state(7L, 42L, false, 0L, 0L, CREATED));
    assertThrows(IllegalArgumentException.class, () -> state(7L, 42L, false, 1L, -1L, CREATED));
  }

  @Test
  void rejectsInvalidTimestamps() {
    assertThrows(NullPointerException.class, () -> state(7L, 42L, false, 1L, 0L, (Instant) null));
    assertThrows(
        IllegalArgumentException.class,
        () -> state(7L, 42L, false, 1L, 0L, CREATED.minusSeconds(1)));
  }

  @Test
  void reserveCommandSequencesAdvancesBatchAndRevisionByOne() {
    ThreadState stored = state(7L, 42L, false, 3L, 5L, CREATED);
    ThreadState next = stored.reserveCommandSequences(3, CREATED.plusSeconds(1));
    assertEquals(6L, next.nextCommandSequence());
    assertEquals(6L, next.revision());
    assertEquals(CREATED.plusSeconds(1), next.updatedAt());
    assertEquals(stored.headEntryId(), next.headEntryId());
    assertEquals(stored.yoloEnabled(), next.yoloEnabled());
    // 单个 sequence 等价于 count=1
    ThreadState single = stored.reserveCommandSequences(1, CREATED.plusSeconds(1));
    assertEquals(4L, single.nextCommandSequence());
    assertEquals(6L, single.revision());
  }

  @Test
  void reserveCommandSequencesRejectsNonPositiveCountAndOverflow() {
    ThreadState stored = state(7L, 42L, false, 3L, 5L, CREATED);
    assertThrows(IllegalArgumentException.class, () -> stored.reserveCommandSequences(0, CREATED));
    assertThrows(IllegalArgumentException.class, () -> stored.reserveCommandSequences(-1, CREATED));
    assertThrows(
        ArithmeticException.class,
        () ->
            state(7L, 42L, false, Long.MAX_VALUE, 5L, CREATED).reserveCommandSequences(2, CREATED));
  }

  @Test
  void advanceHeadAndTouchRevisionBumpRevisionByOne() {
    ThreadState stored = state(7L, 42L, false, 3L, 5L, CREATED);
    // head 与 yolo policy 在同一步原子地设置，仅 bump 一次 revision
    ThreadState head = stored.advanceHead(99L, true, CREATED.plusSeconds(2));
    assertEquals(99L, head.headEntryId());
    assertTrue(head.yoloEnabled());
    assertEquals(6L, head.revision());
    assertEquals(3L, head.nextCommandSequence());
    // 在 terminal apply 时重新发送当前 policy，语义保持一致
    ThreadState replayPolicy = head.advanceHead(99L, true, CREATED.plusSeconds(3));
    assertEquals(7L, replayPolicy.revision());
    ThreadState touched = replayPolicy.touchRevision(CREATED.plusSeconds(3));
    assertEquals(8L, touched.revision());
    assertEquals(99L, touched.headEntryId());
    assertTrue(touched.yoloEnabled());
    assertEquals(3L, touched.nextCommandSequence());
  }

  @Test
  void transitionMethodsClampWallClockRollbackToCurrentUpdatedAt() {
    Instant durableNow = CREATED.plusSeconds(2);
    ThreadState stored = state(7L, 42L, false, 3L, 5L, durableNow);

    // Caller wall-clock 回拨时，纯转换保留 durable 时间下界；revision/sequence/head 语义照常推进。
    assertEquals(durableNow, stored.reserveCommandSequences(1, CREATED).updatedAt());
    assertEquals(durableNow, stored.advanceHead(99L, true, CREATED).updatedAt());
    assertEquals(durableNow, stored.touchRevision(CREATED).updatedAt());
  }

  @Test
  void validateTransitionAcceptsExactReplayAndRejectsIdentityRegression() {
    ThreadState stored = state(7L, 42L, false, 3L, 5L, CREATED);
    ThreadState.validateTransition(stored, stored);
    assertThrows(
        IllegalArgumentException.class,
        () -> ThreadState.validateTransition(stored, state(8L, 42L, false, 3L, 5L, CREATED)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                new ThreadState(
                    7L, 42L, false, 3L, 5L, CREATED.plusSeconds(1), CREATED.plusSeconds(1))));
  }

  @Test
  void validateTransitionRejectsSequenceRevisionAndUpdatedAtRegression() {
    ThreadState stored = state(7L, 42L, false, 3L, 5L, CREATED.plusSeconds(2));
    // nextCommandSequence 倒退
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(7L, 42L, false, 2L, 6L, CREATED.plusSeconds(3))));
    // revision 倒退
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(7L, 42L, false, 3L, 4L, CREATED.plusSeconds(3))));
    // updatedAt 倒退
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(7L, 42L, false, 3L, 6L, CREATED.plusSeconds(1))));
  }

  @Test
  void validateTransitionRequiresExactRevisionIncrementOnAnyChange() {
    ThreadState stored = state(7L, 42L, false, 3L, 5L, CREATED);
    // 任何对外可见的变更都必须将 revision 恰好 bump 一次
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(7L, 42L, true, 3L, 5L, CREATED.plusSeconds(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(7L, 42L, false, 4L, 5L, CREATED.plusSeconds(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(7L, 43L, false, 3L, 5L, CREATED.plusSeconds(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(7L, 42L, false, 3L, 7L, CREATED.plusSeconds(1))));
    // 即便仅修改 updatedAt，仍必须 bump revision
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(7L, 42L, false, 3L, 5L, CREATED.plusSeconds(1))));
    ThreadState.validateTransition(stored, state(7L, 42L, false, 3L, 6L, CREATED.plusSeconds(1)));
  }

  private static ThreadState state(
      long id,
      long headEntryId,
      boolean yoloEnabled,
      long nextCommandSequence,
      long revision,
      Instant updatedAt) {
    return new ThreadState(
        id, headEntryId, yoloEnabled, nextCommandSequence, revision, CREATED, updatedAt);
  }
}
