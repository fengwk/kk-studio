package fun.fengwk.kkstudio.core.ai.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

/** 进程内 task 并发 reservation 的计数与 session 归属语义。 */
class SubagentRunRegistryTest {

  private static final Duration ONE_MS = Duration.ofMillis(1);

  private static SubagentConfig config(int maxConcurrency, Integer maxTotalConcurrency) {
    return new SubagentConfig(2, maxConcurrency, maxTotalConcurrency, ONE_MS, 50, ONE_MS);
  }

  /** 同一 parent 的直系子 Agent 数不得超过 maxConcurrency；关闭释放计数。 */
  @Test
  void enforcesMaxConcurrencyPerParent() {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(1, null);

    try (SubagentRunRegistry.Reservation first = registry.reserve(1, 1, null, config)) {
      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> registry.reserve(1, 1, null, config));
      assertEquals("subagent concurrency limit reached (1/1)", error.getMessage());
    }

    // 关闭后计数已释放：同一 parent 可再次占用唯一槽位，占满后依旧拒绝。
    try (SubagentRunRegistry.Reservation second = registry.reserve(1, 1, null, config)) {
      assertThrows(IllegalArgumentException.class, () -> registry.reserve(1, 1, null, config));
    }
  }

  /** 不同 parent 共享同一 root 时，总 reservation 不得超过 maxTotalConcurrency。 */
  @Test
  void enforcesMaxTotalConcurrencyAcrossParents() {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(10, 2);

    try (SubagentRunRegistry.Reservation alpha = registry.reserve(1, 9, null, config);
        SubagentRunRegistry.Reservation beta = registry.reserve(2, 9, null, config)) {
      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> registry.reserve(3, 9, null, config));
      assertEquals("subagent tree concurrency limit reached (2/2)", error.getMessage());
    }

    // root 总量随 reservation 关闭而释放：两个新的不同 parent 可重新占满并再次拒绝第三个。
    try (SubagentRunRegistry.Reservation gamma = registry.reserve(3, 9, null, config);
        SubagentRunRegistry.Reservation delta = registry.reserve(4, 9, null, config)) {
      assertThrows(IllegalArgumentException.class, () -> registry.reserve(5, 9, null, config));
    }
  }

  /** 同一 resume session 同时只能有一个 reservation 持有；释放后可恢复。 */
  @Test
  void rejectsResumeSessionWhileRunning() {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(10, null);

    try (SubagentRunRegistry.Reservation first = registry.reserve(1, 1, 42L, config)) {
      IllegalArgumentException error =
          assertThrows(IllegalArgumentException.class, () -> registry.reserve(1, 1, 42L, config));
      assertEquals("subagent session \"42\" is currently running", error.getMessage());
    }

    // session 释放后可再次恢复；恢复后再次被独占。
    try (SubagentRunRegistry.Reservation resumed = registry.reserve(1, 1, 42L, config)) {
      assertThrows(IllegalArgumentException.class, () -> registry.reserve(1, 1, 42L, config));
    }
  }

  /** attach 的 thread 是唯一的运行中 session 事实：已被占用的 thread 不能再 attach。 */
  @Test
  void rejectsAttachingAnAlreadyRunningThread() {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(10, null);

    try (SubagentRunRegistry.Reservation first = registry.reserve(1, 1, null, config)) {
      first.attach(200);
      SubagentRunRegistry.Reservation second = registry.reserve(2, 2, null, config);
      try {
        IllegalArgumentException error =
            assertThrows(IllegalArgumentException.class, () -> second.attach(200));
        assertEquals("subagent session \"200\" is currently running", error.getMessage());
      } finally {
        second.close();
      }
    }

    // thread 随 reservation 关闭而释放：新 reservation 可再次 attach 并重新独占它。
    try (SubagentRunRegistry.Reservation again = registry.reserve(1, 1, null, config)) {
      again.attach(200);
      SubagentRunRegistry.Reservation other = registry.reserve(2, 2, null, config);
      try {
        assertThrows(IllegalArgumentException.class, () -> other.attach(200));
      } finally {
        other.close();
      }
    }
  }

  /** 同一 reservation 重复 attach 相同 thread 是幂等的；换 thread 则拒绝。 */
  @Test
  void rejectsReattachingWithDifferentThread() {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(10, null);

    try (SubagentRunRegistry.Reservation reservation = registry.reserve(1, 1, null, config)) {
      reservation.attach(200);
      reservation.attach(200);
      assertThrows(IllegalStateException.class, () -> reservation.attach(201));
    }
  }

  /** close 幂等：重复 close 不抛错、不重复释放；关闭后 attach 是 no-op，计数与 session 均归还。 */
  @Test
  void closeIsIdempotent() {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(1, 1);

    // 新子 Agent 的 reservation：attach 的 thread 随 close 释放。
    SubagentRunRegistry.Reservation child = registry.reserve(1, 1, null, config);
    child.attach(200);
    child.close();
    child.close();
    // 关闭后 attach 不得重新登记 thread。
    child.attach(200);

    // resume session 的 reservation：session 占用随 close 释放。
    SubagentRunRegistry.Reservation resumed = registry.reserve(1, 1, 42L, config);
    resumed.close();
    resumed.close();

    // 计数、resume session 与 thread 均已归还：可重新占满并再次拒绝。
    try (SubagentRunRegistry.Reservation again = registry.reserve(1, 1, 42L, config)) {
      assertThrows(IllegalArgumentException.class, () -> registry.reserve(1, 1, null, config));
      assertThrows(IllegalArgumentException.class, () -> registry.reserve(1, 1, 42L, config));
    }
    try (SubagentRunRegistry.Reservation withThread = registry.reserve(1, 1, null, config)) {
      withThread.attach(200);
    }
  }

  /** 嵌套 task 的活动状态按 thread parent 链投影给全部祖先，并在 reservation 释放时立即消失。 */
  @Test
  void relaysDescendantStatusesToAncestors() {
    SubagentRunRegistry registry = new SubagentRunRegistry();
    SubagentConfig config = config(10, null);
    SubagentRunRegistry.Reservation child = registry.reserve(1, 1, null, config);
    SubagentRunRegistry.Reservation grandchild = registry.reserve(2, 1, null, config);
    child.attach(2);
    grandchild.attach(3);
    var approval = new SubagentRunRegistry.RelayedApproval(51, "bash", "confirm");
    var status =
        new SubagentRunRegistry.RelayedStatus(
            3, "coder", "waiting_approval", 3, 1, 1, "waiting bash", List.of(approval));

    registry.publishStatus(status);

    assertEquals(List.of(status), registry.descendantStatuses(1));
    assertEquals(List.of(status), registry.descendantStatuses(2));
    assertEquals(List.of(), registry.descendantStatuses(3));

    grandchild.close();
    assertEquals(List.of(), registry.descendantStatuses(1));
    child.close();
  }
}
