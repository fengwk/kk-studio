package fun.fengwk.kkstudio.harness.runtime.thread;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

/** ThreadState durable 字段与不变量校验。 */
class ThreadStateTest {

  private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
  private static final String CREATION_REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final UUID SESSION_ID = id(1_000_000L);

  @Test
  void acceptsValidDurableFields() {
    ThreadState state = state(id(7), id(42), true, 3L, 5L, CREATED);

    assertEquals(id(7), state.id());
    assertEquals(id(42), state.headEntryId());
    assertTrue(state.yoloEnabled());
    assertEquals(3L, state.nextCommandSequence());
    assertEquals(5L, state.version());
    assertEquals(CREATED, state.createdAt());
    assertEquals(CREATED, state.updatedAt());

    ThreadState yoloDisabled = state(id(8), id(43), false, 1L, 0L, CREATED.plusSeconds(2));
    assertFalse(yoloDisabled.yoloEnabled());
  }

  @Test
  void rejectsInvalidIdentityAndSequence() {
    assertThrows(
        IllegalArgumentException.class, () -> state(id(7), id(42), false, 0L, 0L, CREATED));
    assertThrows(
        IllegalArgumentException.class, () -> state(id(7), id(42), false, 1L, -1L, CREATED));
    assertThrows(NullPointerException.class, () -> state(null, id(42), false, 1L, 0L, CREATED));
    assertThrows(NullPointerException.class, () -> state(id(7), null, false, 1L, 0L, CREATED));
  }

  @Test
  void rejectsInvalidTimestamps() {
    assertThrows(
        NullPointerException.class, () -> state(id(7), id(42), false, 1L, 0L, (Instant) null));
    assertThrows(
        IllegalArgumentException.class,
        () -> state(id(7), id(42), false, 1L, 0L, CREATED.minusSeconds(1)));
  }

  @Test
  void reserveCommandSequencesAdvancesBatchAndVersionByOne() {
    ThreadState stored = state(id(7), id(42), false, 3L, 5L, CREATED);
    ThreadState next = stored.reserveCommandSequences(3, CREATED.plusSeconds(1));
    assertEquals(6L, next.nextCommandSequence());
    assertEquals(6L, next.version());
    assertEquals(CREATED.plusSeconds(1), next.updatedAt());
    assertEquals(stored.headEntryId(), next.headEntryId());
    assertEquals(stored.yoloEnabled(), next.yoloEnabled());
    // 单个 sequence 等价于 count=1
    ThreadState single = stored.reserveCommandSequences(1, CREATED.plusSeconds(1));
    assertEquals(4L, single.nextCommandSequence());
    assertEquals(6L, single.version());
  }

  @Test
  void reserveCommandSequencesRejectsNonPositiveCountAndOverflow() {
    ThreadState stored = state(id(7), id(42), false, 3L, 5L, CREATED);
    assertThrows(IllegalArgumentException.class, () -> stored.reserveCommandSequences(0, CREATED));
    assertThrows(IllegalArgumentException.class, () -> stored.reserveCommandSequences(-1, CREATED));
    assertThrows(
        ArithmeticException.class,
        () ->
            state(id(7), id(42), false, Long.MAX_VALUE, 5L, CREATED)
                .reserveCommandSequences(2, CREATED));
  }

  @Test
  void advanceHeadPreservesYoloPolicyAndBumpsVersionByOne() {
    ThreadState stored = state(id(7), id(42), true, 3L, 5L, CREATED);
    // head 推进恒保留当前 yolo policy（不再接受外部传入值，杜绝 terminal / resolver 路径写回过期策略），仅 bump 一次 version。
    ThreadState head = stored.advanceHead(id(99), CREATED.plusSeconds(2));
    assertEquals(id(99), head.headEntryId());
    assertTrue(head.yoloEnabled());
    assertEquals(6L, head.version());
    assertEquals(3L, head.nextCommandSequence());
    // 再次推进同样保留 policy，不因显式传入而改写。
    ThreadState advanced = head.advanceHead(id(99), CREATED.plusSeconds(3));
    assertTrue(advanced.yoloEnabled());
    assertEquals(7L, advanced.version());
    assertEquals(id(99), advanced.headEntryId());
    assertEquals(3L, advanced.nextCommandSequence());
    // false 值同样被保留。
    ThreadState storedDisabled = state(id(7), id(42), false, 3L, 5L, CREATED);
    ThreadState advancedDisabled = storedDisabled.advanceHead(id(99), CREATED.plusSeconds(2));
    assertFalse(advancedDisabled.yoloEnabled());
    assertEquals(6L, advancedDisabled.version());
    assertEquals(id(99), advancedDisabled.headEntryId());
  }

  @Test
  void setYoloEnabledBumpsVersionExactlyOnceAndPreservesCursor() {
    ThreadState stored = state(id(7), id(42), false, 3L, 5L, CREATED);
    ThreadState enabled = stored.setYoloEnabled(true, CREATED.plusSeconds(2));
    assertEquals(true, enabled.yoloEnabled());
    assertEquals(6L, enabled.version());
    assertEquals(id(42), enabled.headEntryId());
    assertEquals(3L, enabled.nextCommandSequence());
    // 再次切换同样精确 +1；时间钳制与其它转换一致。
    ThreadState disabled = enabled.setYoloEnabled(false, CREATED.plusSeconds(2));
    assertEquals(false, disabled.yoloEnabled());
    assertEquals(7L, disabled.version());
    assertEquals(CREATED.plusSeconds(2), enabled.updatedAt());
    assertEquals(CREATED.plusSeconds(2), disabled.updatedAt());
  }

  @Test
  void transitionMethodsClampWallClockRollbackToCurrentUpdatedAt() {
    Instant durableNow = CREATED.plusSeconds(2);
    ThreadState stored = state(id(7), id(42), false, 3L, 5L, durableNow);

    // Caller wall-clock 回拨时，纯转换保留 durable 时间下界；version/sequence/head 语义照常推进。
    assertEquals(durableNow, stored.reserveCommandSequences(1, CREATED).updatedAt());
    assertEquals(durableNow, stored.advanceHead(id(99), CREATED).updatedAt());
    assertEquals(durableNow, stored.touchVersion(CREATED).updatedAt());
    assertEquals(durableNow, stored.setYoloEnabled(true, CREATED).updatedAt());
  }

  @Test
  void validateTransitionAcceptsExactReplayAndRejectsIdentityRegression() {
    ThreadState stored = state(id(7), id(42), false, 3L, 5L, CREATED);
    ThreadState.validateTransition(stored, stored);
    assertThrows(
        IllegalArgumentException.class,
        () -> ThreadState.validateTransition(stored, state(id(8), id(42), false, 3L, 5L, CREATED)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                new ThreadState(
                    id(7),
                    SESSION_ID,
                    id(42),
                    CREATION_REQUEST_HASH,
                    false,
                    3L,
                    5L,
                    CREATED.plusSeconds(1),
                    CREATED.plusSeconds(1))));
  }

  @Test
  void validateTransitionRejectsSequenceVersionAndUpdatedAtRegression() {
    ThreadState stored = state(id(7), id(42), false, 3L, 5L, CREATED.plusSeconds(2));
    // nextCommandSequence 倒退
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(id(7), id(42), false, 2L, 6L, CREATED.plusSeconds(3))));
    // version 倒退
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(id(7), id(42), false, 3L, 4L, CREATED.plusSeconds(3))));
    // updatedAt 倒退
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(id(7), id(42), false, 3L, 6L, CREATED.plusSeconds(1))));
  }

  @Test
  void validateTransitionRequiresExactVersionIncrementOnAnyChange() {
    ThreadState stored = state(id(7), id(42), false, 3L, 5L, CREATED);
    // 任何对外可见的变更都必须将 version 恰好 bump 一次
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(id(7), id(42), true, 3L, 5L, CREATED.plusSeconds(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(id(7), id(42), false, 4L, 5L, CREATED.plusSeconds(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(id(7), id(43), false, 3L, 5L, CREATED.plusSeconds(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(id(7), id(42), false, 3L, 7L, CREATED.plusSeconds(1))));
    // 即便仅修改 updatedAt，仍必须 bump version
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored, state(id(7), id(42), false, 3L, 5L, CREATED.plusSeconds(1))));
    ThreadState.validateTransition(
        stored, state(id(7), id(42), false, 3L, 6L, CREATED.plusSeconds(1)));
  }

  private static ThreadState state(
      UUID id,
      UUID headEntryId,
      boolean yoloEnabled,
      long nextCommandSequence,
      long version,
      Instant updatedAt) {
    return new ThreadState(
        id,
        SESSION_ID,
        headEntryId,
        CREATION_REQUEST_HASH,
        yoloEnabled,
        nextCommandSequence,
        version,
        CREATED,
        updatedAt);
  }
}
