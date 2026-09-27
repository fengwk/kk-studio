package fun.fengwk.kkstudio.harness.runtime.thread;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    // 测试意图：验证合法的 ThreadState 根线程（parentThreadId 为 null）与子线程（parentThreadId 非空）能正常构造并暴露字段。
    ThreadState rootState =
        state(id(7), null, id(42), true, ThreadLifecycleStatus.ACTIVE, 3L, 5L, CREATED);

    assertEquals(id(7), rootState.id());
    assertEquals(SESSION_ID, rootState.sessionId());
    assertNull(rootState.parentThreadId());
    assertEquals(id(42), rootState.headEntryId());
    assertEquals(CREATION_REQUEST_HASH, rootState.creationRequestHash());
    assertEquals("main", rootState.name());
    assertTrue(rootState.yoloEnabled());
    assertEquals(ThreadLifecycleStatus.ACTIVE, rootState.status());
    assertEquals(3L, rootState.nextCommandSequence());
    assertEquals(5L, rootState.version());
    assertEquals(CREATED, rootState.createdAt());
    assertEquals(CREATED, rootState.updatedAt());

    assertTrue(rootState.status().isActive());
    assertFalse(rootState.status().isIdle());
    assertFalse(rootState.status().isStopped());

    ThreadState childState =
        state(
            id(8),
            id(7),
            id(43),
            false,
            ThreadLifecycleStatus.IDLE,
            1L,
            0L,
            CREATED.plusSeconds(2));
    assertEquals(id(7), childState.parentThreadId());
    assertFalse(childState.yoloEnabled());
    assertEquals(ThreadLifecycleStatus.IDLE, childState.status());
    assertTrue(childState.status().isIdle());
    assertFalse(childState.status().isActive());
    assertFalse(childState.status().isStopped());

    ThreadState stoppedState =
        state(
            id(9),
            null,
            id(44),
            false,
            ThreadLifecycleStatus.STOPPED,
            1L,
            1L,
            CREATED.plusSeconds(3));
    assertEquals(ThreadLifecycleStatus.STOPPED, stoppedState.status());
    assertTrue(stoppedState.status().isStopped());
    assertFalse(stoppedState.status().isIdle());
    assertFalse(stoppedState.status().isActive());
  }

  @Test
  void rejectsInvalidIdentityAndSequence() {
    // 测试意图：验证不可变 identity 字段校验，包括 null 拒绝、parent 指向自身拒绝以及 sequence/version 下界约束。
    assertThrows(
        IllegalArgumentException.class, () -> state(id(7), id(42), false, 0L, 0L, CREATED));
    assertThrows(
        IllegalArgumentException.class, () -> state(id(7), id(42), false, 1L, -1L, CREATED));
    assertThrows(NullPointerException.class, () -> state(null, id(42), false, 1L, 0L, CREATED));
    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadState(
                id(7),
                null,
                null,
                id(42),
                CREATION_REQUEST_HASH,
                "main",
                false,
                ThreadLifecycleStatus.ACTIVE,
                1L,
                0L,
                CREATED,
                CREATED));
    assertThrows(NullPointerException.class, () -> state(id(7), null, false, 1L, 0L, CREATED));
    // 拒绝 parentThreadId 指向自身
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadState(
                id(7),
                SESSION_ID,
                id(7),
                id(42),
                CREATION_REQUEST_HASH,
                "main",
                false,
                ThreadLifecycleStatus.ACTIVE,
                1L,
                0L,
                CREATED,
                CREATED));
    // 拒绝 status 为 null
    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadState(
                id(7),
                SESSION_ID,
                null,
                id(42),
                CREATION_REQUEST_HASH,
                "main",
                false,
                null,
                1L,
                0L,
                CREATED,
                CREATED));
    // 拒绝非法的 creationRequestHash
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadState(
                id(7),
                SESSION_ID,
                null,
                id(42),
                null,
                "main",
                false,
                ThreadLifecycleStatus.ACTIVE,
                1L,
                0L,
                CREATED,
                CREATED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadState(
                id(7),
                SESSION_ID,
                null,
                id(42),
                "invalid-hash",
                "main",
                false,
                ThreadLifecycleStatus.ACTIVE,
                1L,
                0L,
                CREATED,
                CREATED));
  }

  @Test
  void rejectsInvalidTimestamps() {
    // 测试意图：验证时间戳不可空且 updatedAt 不得早于 createdAt。
    assertThrows(
        NullPointerException.class, () -> state(id(7), id(42), false, 1L, 0L, (Instant) null));
    assertThrows(
        IllegalArgumentException.class,
        () -> state(id(7), id(42), false, 1L, 0L, CREATED.minusSeconds(1)));
  }

  @Test
  void initialConstructorAndFactoryEstablishCanonicalState() {
    // 测试意图：验证初始构造器与静态 initial 工厂方法正确设置规范初始值（sequence=1, version=0, createdAt=updatedAt=now）。
    Instant now = CREATED.plusSeconds(10);
    ThreadState initialViaConstructor =
        new ThreadState(
            id(101),
            SESSION_ID,
            id(7),
            id(42),
            CREATION_REQUEST_HASH,
            "child-branch",
            true,
            ThreadLifecycleStatus.ACTIVE,
            now);

    assertEquals(id(101), initialViaConstructor.id());
    assertEquals(SESSION_ID, initialViaConstructor.sessionId());
    assertEquals(id(7), initialViaConstructor.parentThreadId());
    assertEquals(id(42), initialViaConstructor.headEntryId());
    assertEquals(CREATION_REQUEST_HASH, initialViaConstructor.creationRequestHash());
    assertEquals("child-branch", initialViaConstructor.name());
    assertTrue(initialViaConstructor.yoloEnabled());
    assertEquals(ThreadLifecycleStatus.ACTIVE, initialViaConstructor.status());
    assertEquals(1L, initialViaConstructor.nextCommandSequence());
    assertEquals(0L, initialViaConstructor.version());
    assertEquals(now, initialViaConstructor.createdAt());
    assertEquals(now, initialViaConstructor.updatedAt());

    ThreadState initialViaFactory =
        ThreadState.initial(
            id(102),
            SESSION_ID,
            null,
            id(43),
            CREATION_REQUEST_HASH,
            "root-thread",
            false,
            ThreadLifecycleStatus.IDLE,
            now);

    assertEquals(id(102), initialViaFactory.id());
    assertNull(initialViaFactory.parentThreadId());
    assertEquals(ThreadLifecycleStatus.IDLE, initialViaFactory.status());
    assertEquals(1L, initialViaFactory.nextCommandSequence());
    assertEquals(0L, initialViaFactory.version());
    assertEquals(now, initialViaFactory.createdAt());
    assertEquals(now, initialViaFactory.updatedAt());
  }

  @Test
  void changeLifecycleStatusBumpsVersionByOneAndPreservesFields() {
    // 测试意图：验证 changeLifecycleStatus 转换方法将 status 更新、version 严格 +1、并完整保留 parent 与其他 durable 字段。
    ThreadState stored =
        state(id(7), id(100), id(42), false, ThreadLifecycleStatus.ACTIVE, 3L, 5L, CREATED);

    ThreadState idle =
        stored.changeLifecycleStatus(ThreadLifecycleStatus.IDLE, CREATED.plusSeconds(1));
    assertEquals(ThreadLifecycleStatus.IDLE, idle.status());
    assertEquals(6L, idle.version());
    assertEquals(stored.id(), idle.id());
    assertEquals(stored.sessionId(), idle.sessionId());
    assertEquals(id(100), idle.parentThreadId());
    assertEquals(stored.headEntryId(), idle.headEntryId());
    assertEquals(stored.creationRequestHash(), idle.creationRequestHash());
    assertEquals(stored.name(), idle.name());
    assertEquals(stored.yoloEnabled(), idle.yoloEnabled());
    assertEquals(stored.nextCommandSequence(), idle.nextCommandSequence());
    assertEquals(stored.createdAt(), idle.createdAt());
    assertEquals(CREATED.plusSeconds(1), idle.updatedAt());

    ThreadState stopped =
        idle.changeLifecycleStatus(ThreadLifecycleStatus.STOPPED, CREATED.plusSeconds(2));
    assertEquals(ThreadLifecycleStatus.STOPPED, stopped.status());
    assertEquals(7L, stopped.version());
    assertEquals(id(100), stopped.parentThreadId());

    // 拒绝传入 null status
    assertThrows(
        NullPointerException.class,
        () -> stored.changeLifecycleStatus(null, CREATED.plusSeconds(1)));
  }

  @Test
  void reserveCommandSequencesAdvancesBatchAndVersionByOne() {
    ThreadState stored =
        state(id(7), id(100), id(42), false, ThreadLifecycleStatus.ACTIVE, 3L, 5L, CREATED);
    ThreadState next = stored.reserveCommandSequences(3, CREATED.plusSeconds(1));
    assertEquals(6L, next.nextCommandSequence());
    assertEquals(6L, next.version());
    assertEquals(CREATED.plusSeconds(1), next.updatedAt());
    assertEquals(stored.headEntryId(), next.headEntryId());
    assertEquals(stored.yoloEnabled(), next.yoloEnabled());
    assertEquals(stored.parentThreadId(), next.parentThreadId());
    assertEquals(stored.status(), next.status());
    // 单个 sequence 等价于 count=1
    ThreadState single = stored.reserveCommandSequences(1, CREATED.plusSeconds(1));
    assertEquals(4L, single.nextCommandSequence());
    assertEquals(6L, single.version());
    assertEquals(stored.parentThreadId(), single.parentThreadId());
    assertEquals(stored.status(), single.status());
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
    ThreadState stored =
        state(id(7), id(100), id(42), true, ThreadLifecycleStatus.ACTIVE, 3L, 5L, CREATED);
    // head 推进恒保留当前 yolo policy（不再接受外部传入值，杜绝 terminal / resolver 路径写回过期策略），仅 bump 一次 version。
    ThreadState head = stored.advanceHead(id(99), CREATED.plusSeconds(2));
    assertEquals(id(99), head.headEntryId());
    assertTrue(head.yoloEnabled());
    assertEquals(6L, head.version());
    assertEquals(3L, head.nextCommandSequence());
    assertEquals(stored.parentThreadId(), head.parentThreadId());
    assertEquals(stored.status(), head.status());
    // 再次推进同样保留 policy，不因显式传入而改写。
    ThreadState advanced = head.advanceHead(id(99), CREATED.plusSeconds(3));
    assertTrue(advanced.yoloEnabled());
    assertEquals(7L, advanced.version());
    assertEquals(id(99), advanced.headEntryId());
    assertEquals(3L, advanced.nextCommandSequence());
    assertEquals(stored.parentThreadId(), advanced.parentThreadId());
    assertEquals(stored.status(), advanced.status());
    // false 值同样被保留。
    ThreadState storedDisabled = state(id(7), id(42), false, 3L, 5L, CREATED);
    ThreadState advancedDisabled = storedDisabled.advanceHead(id(99), CREATED.plusSeconds(2));
    assertFalse(advancedDisabled.yoloEnabled());
    assertEquals(6L, advancedDisabled.version());
    assertEquals(id(99), advancedDisabled.headEntryId());
    assertEquals(storedDisabled.parentThreadId(), advancedDisabled.parentThreadId());
    assertEquals(storedDisabled.status(), advancedDisabled.status());
  }

  @Test
  void setYoloEnabledBumpsVersionExactlyOnceAndPreservesCursor() {
    ThreadState stored =
        state(id(7), id(100), id(42), false, ThreadLifecycleStatus.ACTIVE, 3L, 5L, CREATED);
    ThreadState enabled = stored.setYoloEnabled(true, CREATED.plusSeconds(2));
    assertTrue(enabled.yoloEnabled());
    assertEquals(6L, enabled.version());
    assertEquals(id(42), enabled.headEntryId());
    assertEquals(3L, enabled.nextCommandSequence());
    assertEquals(stored.parentThreadId(), enabled.parentThreadId());
    assertEquals(stored.status(), enabled.status());
    // 再次切换同样精确 +1；时间钳制与其它转换一致。
    ThreadState disabled = enabled.setYoloEnabled(false, CREATED.plusSeconds(2));
    assertFalse(disabled.yoloEnabled());
    assertEquals(7L, disabled.version());
    assertEquals(CREATED.plusSeconds(2), enabled.updatedAt());
    assertEquals(CREATED.plusSeconds(2), disabled.updatedAt());
    assertEquals(stored.parentThreadId(), disabled.parentThreadId());
    assertEquals(stored.status(), disabled.status());
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
    assertEquals(durableNow, stored.renameThread("new name", CREATED).updatedAt());
    assertEquals(
        durableNow,
        stored.changeLifecycleStatus(ThreadLifecycleStatus.STOPPED, CREATED).updatedAt());
  }

  @Test
  void renameThreadNormalizesNameAndBumpsVersionExactlyOnce() {
    ThreadState stored =
        state(id(7), id(100), id(42), false, ThreadLifecycleStatus.ACTIVE, 3L, 5L, CREATED);
    ThreadState renamed = stored.renameThread("  新 名字  ", CREATED.plusSeconds(3));
    // 名称规范化折叠为单行；version 精确 +1；head/sequence/yolo/parent/status/createdAt 不变。
    assertEquals("新 名字", renamed.name());
    assertEquals(6L, renamed.version());
    assertEquals(stored.headEntryId(), renamed.headEntryId());
    assertEquals(stored.nextCommandSequence(), renamed.nextCommandSequence());
    assertEquals(stored.yoloEnabled(), renamed.yoloEnabled());
    assertEquals(stored.parentThreadId(), renamed.parentThreadId());
    assertEquals(stored.status(), renamed.status());
    assertEquals(stored.createdAt(), renamed.createdAt());
    // renamed 是同一 stored 行的合法迁移。
    ThreadState.validateTransition(stored, renamed);
    // rename 后对 renamed 行再做一次同名 rename 属于新迁移（由控制面负责 no-op），version 照常严格 +1。
    ThreadState sameName = renamed.renameThread("新 名字", CREATED.plusSeconds(3));
    assertEquals(7L, sameName.version());
    assertEquals("新 名字", sameName.name());
  }

  @Test
  void renameThreadRejectsOverlongName() {
    // Thread 名称手工上限 256 码点：rename 超长直接抛 IllegalArgumentException（绝不截断）。
    ThreadState stored = state(id(7), id(42), false, 3L, 5L, CREATED);
    assertThrows(
        IllegalArgumentException.class,
        () -> stored.renameThread("x".repeat(257), CREATED.plusSeconds(1)));
    // 恰好 256 码点可接受。
    assertEquals(
        "x".repeat(256), stored.renameThread("x".repeat(256), CREATED.plusSeconds(1)).name());
  }

  @Test
  void touchVersionBumpsVersionExactlyOnceAndPreservesFields() {
    // 测试意图：验证 touchVersion 仅递增 version 并保留 parentThreadId 与 status。
    ThreadState stored =
        state(id(7), id(100), id(42), false, ThreadLifecycleStatus.ACTIVE, 3L, 5L, CREATED);
    ThreadState touched = stored.touchVersion(CREATED.plusSeconds(1));
    assertEquals(6L, touched.version());
    assertEquals(stored.parentThreadId(), touched.parentThreadId());
    assertEquals(stored.status(), touched.status());
    assertEquals(stored.headEntryId(), touched.headEntryId());
    assertEquals(stored.nextCommandSequence(), touched.nextCommandSequence());
  }

  @Test
  void validateTransitionAcceptsExactReplayAndRejectsIdentityRegression() {
    // 测试意图：验证 validateTransition 接受完全重放，并拒绝任何 identity 变更（包括 parentThreadId 不可变）。
    ThreadState stored =
        state(id(7), id(100), id(42), false, ThreadLifecycleStatus.ACTIVE, 3L, 5L, CREATED);
    ThreadState.validateTransition(stored, stored);

    // 拒绝 id 改变
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                state(
                    id(8), id(100), id(42), false, ThreadLifecycleStatus.ACTIVE, 3L, 6L, CREATED)));
    // 拒绝 sessionId 改变
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                new ThreadState(
                    id(7),
                    id(999_999L),
                    id(100),
                    id(42),
                    CREATION_REQUEST_HASH,
                    "main",
                    false,
                    ThreadLifecycleStatus.ACTIVE,
                    3L,
                    6L,
                    CREATED,
                    CREATED.plusSeconds(1))));
    // 拒绝 parentThreadId 改变（从非空变空、从空变非空、变其他 parent）
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                state(
                    id(7),
                    null,
                    id(42),
                    false,
                    ThreadLifecycleStatus.ACTIVE,
                    3L,
                    6L,
                    CREATED.plusSeconds(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                state(
                    id(7),
                    id(101),
                    id(42),
                    false,
                    ThreadLifecycleStatus.ACTIVE,
                    3L,
                    6L,
                    CREATED.plusSeconds(1))));
    ThreadState rootStored =
        state(id(7), null, id(42), false, ThreadLifecycleStatus.ACTIVE, 3L, 5L, CREATED);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                rootStored,
                state(
                    id(7),
                    id(100),
                    id(42),
                    false,
                    ThreadLifecycleStatus.ACTIVE,
                    3L,
                    6L,
                    CREATED.plusSeconds(1))));
    // 拒绝 creationRequestHash 改变
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                new ThreadState(
                    id(7),
                    SESSION_ID,
                    id(100),
                    id(42),
                    "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                    "main",
                    false,
                    ThreadLifecycleStatus.ACTIVE,
                    3L,
                    6L,
                    CREATED,
                    CREATED.plusSeconds(1))));
    // 拒绝 createdAt 改变
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                new ThreadState(
                    id(7),
                    SESSION_ID,
                    id(100),
                    id(42),
                    CREATION_REQUEST_HASH,
                    "main",
                    false,
                    ThreadLifecycleStatus.ACTIVE,
                    3L,
                    6L,
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
                stored,
                state(
                    id(7),
                    null,
                    id(42),
                    true,
                    ThreadLifecycleStatus.ACTIVE,
                    3L,
                    5L,
                    CREATED.plusSeconds(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                state(
                    id(7),
                    null,
                    id(42),
                    false,
                    ThreadLifecycleStatus.ACTIVE,
                    4L,
                    5L,
                    CREATED.plusSeconds(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                state(
                    id(7),
                    null,
                    id(43),
                    false,
                    ThreadLifecycleStatus.ACTIVE,
                    3L,
                    5L,
                    CREATED.plusSeconds(1))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                state(
                    id(7),
                    null,
                    id(42),
                    false,
                    ThreadLifecycleStatus.ACTIVE,
                    3L,
                    7L,
                    CREATED.plusSeconds(1))));
    // status 改变但 version 未 bump 时拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                state(
                    id(7),
                    null,
                    id(42),
                    false,
                    ThreadLifecycleStatus.IDLE,
                    3L,
                    5L,
                    CREATED.plusSeconds(1))));
    // 即便仅修改 updatedAt，仍必须 bump version
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                stored,
                state(
                    id(7),
                    null,
                    id(42),
                    false,
                    ThreadLifecycleStatus.ACTIVE,
                    3L,
                    5L,
                    CREATED.plusSeconds(1))));
    // status 改变且 version 严格 +1 允许通过
    ThreadState.validateTransition(
        stored,
        state(
            id(7),
            null,
            id(42),
            false,
            ThreadLifecycleStatus.IDLE,
            3L,
            6L,
            CREATED.plusSeconds(1)));
    ThreadState.validateTransition(
        stored,
        state(
            id(7),
            null,
            id(42),
            false,
            ThreadLifecycleStatus.STOPPED,
            3L,
            6L,
            CREATED.plusSeconds(1)));
  }

  private static ThreadState state(
      UUID id,
      UUID headEntryId,
      boolean yoloEnabled,
      long nextCommandSequence,
      long version,
      Instant updatedAt) {
    return state(
        id,
        null,
        headEntryId,
        yoloEnabled,
        ThreadLifecycleStatus.ACTIVE,
        nextCommandSequence,
        version,
        updatedAt);
  }

  private static ThreadState state(
      UUID id,
      UUID parentThreadId,
      UUID headEntryId,
      boolean yoloEnabled,
      ThreadLifecycleStatus status,
      long nextCommandSequence,
      long version,
      Instant updatedAt) {
    return new ThreadState(
        id,
        SESSION_ID,
        parentThreadId,
        headEntryId,
        CREATION_REQUEST_HASH,
        "main",
        yoloEnabled,
        status,
        nextCommandSequence,
        version,
        CREATED,
        updatedAt);
  }
}
