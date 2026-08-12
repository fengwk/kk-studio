package fun.fengwk.kkstudio.harness.runtime.thread;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * durable Thread 当前状态。
 *
 * <p>仅持久化 Thread 自身拥有的字段：head Entry cursor、Thread YOLO runtime policy、下一条 Command sequence 以及对外可见的
 * snapshot revision。Session、environment、status、open turn、runnable flag、 execution epoch 与 processor
 * lease 刻意省略；session 与 environment 事实从 {@code headEntryId} 处的 Entry 分支派生。
 *
 * <p>所有状态变更都通过下方纯转换方法执行；转换会把回拨的调用方 wall-clock 抬升到当前 {@code updatedAt}，任何对外可见的变更都会把 {@code revision}
 * 严格 +1。Store 仍必须在每次 {@code updateThread} 写入前调用 {@link #validateTransition}，严格拒绝直接构造的时间回退。
 */
public record ThreadState(
    UUID id,
    UUID headEntryId,
    boolean yoloEnabled,
    long nextCommandSequence,
    long revision,
    Instant createdAt,
    Instant updatedAt) {

  public ThreadState {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(headEntryId, "headEntryId");
    if (nextCommandSequence < 1) {
      throw new IllegalArgumentException("nextCommandSequence must start at 1");
    }
    if (revision < 0) {
      throw new IllegalArgumentException("revision must not be negative");
    }
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  /**
   * 校验 {@code next} 是存储行 {@code stored} 的合法迁移：identity 不可变， {@code nextCommandSequence} / {@code
   * revision} / {@code updatedAt} 不允许回退，任何对外可见的 变更都会把 {@code revision} 严格 +1。exact replay 一律被接受。
   */
  public static void validateTransition(ThreadState stored, ThreadState next) {
    Objects.requireNonNull(stored, "stored");
    Objects.requireNonNull(next, "next");
    if (stored.equals(next)) {
      return;
    }
    if (!stored.id().equals(next.id())) {
      throw new IllegalArgumentException("thread id must not change");
    }
    if (!stored.createdAt().equals(next.createdAt())) {
      throw new IllegalArgumentException("thread createdAt must not change");
    }
    if (next.nextCommandSequence() < stored.nextCommandSequence()) {
      throw new IllegalArgumentException("nextCommandSequence must not regress");
    }
    if (next.updatedAt().isBefore(stored.updatedAt())) {
      throw new IllegalArgumentException("updatedAt must not regress");
    }
    if (next.revision() != Math.addExact(stored.revision(), 1L)) {
      throw new IllegalArgumentException(
          "any thread state change must bump revision by exactly one");
    }
  }

  /**
   * 在一个原子步骤中预留 {@code count} 条 Command sequence：{@code nextCommandSequence} 前进 {@code count}，{@code
   * revision} 严格 +1；{@code count} 必须为正。
   */
  public ThreadState reserveCommandSequences(int count, Instant now) {
    if (count <= 0) {
      throw new IllegalArgumentException("count must be positive");
    }
    ThreadState next =
        new ThreadState(
            id,
            headEntryId,
            yoloEnabled,
            Math.addExact(nextCommandSequence, (long) count),
            Math.addExact(revision, 1L),
            createdAt,
            effectiveMutationTime(now));
    validateTransition(this, next);
    return next;
  }

  /**
   * 在一个原子步骤中推进 head Entry cursor 并设置冻结的 YOLO runtime policy（terminal apply 会重新发送当前 policy 值）；{@code
   * revision} 严格 +1。
   */
  public ThreadState advanceHead(UUID headEntryId, boolean yoloEnabled, Instant now) {
    ThreadState next =
        new ThreadState(
            id,
            headEntryId,
            yoloEnabled,
            nextCommandSequence,
            Math.addExact(revision, 1L),
            createdAt,
            effectiveMutationTime(now));
    validateTransition(this, next);
    return next;
  }

  /** 显式把对外可见的 snapshot revision +1，不修改其他 durable 字段。 */
  public ThreadState touchRevision(Instant now) {
    ThreadState next =
        new ThreadState(
            id,
            headEntryId,
            yoloEnabled,
            nextCommandSequence,
            Math.addExact(revision, 1L),
            createdAt,
            effectiveMutationTime(now));
    validateTransition(this, next);
    return next;
  }

  private Instant effectiveMutationTime(Instant now) {
    Instant candidate = Objects.requireNonNull(now, "now");
    return candidate.isBefore(updatedAt) ? updatedAt : candidate;
  }
}
