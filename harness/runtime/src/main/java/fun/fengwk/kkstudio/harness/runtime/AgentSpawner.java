package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 统一的 Agent 创建原语：子代理 task、插件 Prompt 任务、Issue 任务与压缩共用这一条「创建 / 续用 Thread + 首批输入 + 票据 + THREAD
 * Work」路径。
 *
 * <p>{@link #spawn} 运行在调用方事务内，<b>假定调用方已按 {@code HarnessStore} 的规范锁序持有全部所需的锁</b>：需要时先取全局 Join
 * 准入锁，再取执行树锁， 再按 Session、Thread 的 UUID 序锁定父链（含续用的目标 Thread）。从零开始的调用方使用 {@link #lockForSpawn}
 * 取得同样的锁集合，已处于树锁内的调用方 （处理器的压缩）直接调用 {@link #spawn}。
 *
 * <p>新建时 Session / ROOT / Thread 经 {@link AcceptCommandsControl#createSessionWithThread} 创建，子
 * Thread 的有效约束为请求约束与父约束的合并； 续用既有 Thread 时沿用其冻结的创建来源与约束。票据的准入与登记由 {@link SpawnTickets} 唯一承担。
 */
public final class AgentSpawner {

  private AgentSpawner() {}

  /**
   * 获取 {@code request} 所需的全部锁：计额的子任务先取全局 Join 准入锁（即使本次额度 unlimited，否则并发的有限额度请求会在“判定额度 + 创建”之间竞态），
   * 再取执行树锁与父链（及续用目标）的 Session / Thread 锁。
   */
  public static void lockForSpawn(HarnessStore.Transaction tx, SpawnRequest request) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(request, "request");
    if (request.parentThreadId() != null && request.countsTowardQuota()) {
      tx.lockJoinAdmission();
    }
    if (request.continuation() != null) {
      AcceptCommandsControl.lockTreeAndAncestors(tx, request.threadId(), false, null);
    } else if (request.parentThreadId() != null) {
      // 子身份必须在树锁内复读：并发重放可能在等待锁期间才看到第一次接受。
      AcceptCommandsControl.lockTreeAndAncestors(
          tx, request.parentThreadId(), true, Set.of(request.threadId()));
    } else {
      AcceptCommandsControl.lockTreeAndAncestors(tx, request.threadId(), false, null);
    }
  }

  /** 在调用方事务内创建（或续用）Thread、写入首批命令并登记票据。 */
  public static AcceptedCommands spawn(HarnessStore.Transaction tx, SpawnRequest request) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(request, "request");
    return request.continuation() == null ? spawnNew(tx, request) : spawnOnExisting(tx, request);
  }

  private static AcceptedCommands spawnNew(HarnessStore.Transaction tx, SpawnRequest request) {
    UUID parentId = request.parentThreadId();
    ThreadState parent = null;
    if (parentId != null) {
      parent =
          tx.findThread(parentId)
              .orElseThrow(() -> new IllegalArgumentException("join parent does not exist"));
    }
    UUID rootThreadId = parent == null ? request.threadId() : parent.rootThreadId();
    ThreadConstraints effective =
        parent == null ? request.constraints() : request.constraints().merge(parent.constraints());
    String creationRequestHash =
        ThreadCreationRequestHash.forNewSession(
            request.sessionId(),
            request.threadId(),
            request.settings(),
            parentId,
            request.yoloEnabled(),
            request.trigger(),
            request.constraints(),
            request.commands());
    ThreadState existing = tx.findThread(request.threadId()).orElse(null);
    if (existing != null) {
      return SpawnTickets.attach(
          tx,
          AcceptCommandsControl.replayInitial(
              tx,
              request.sessionId(),
              request.threadId(),
              existing,
              request.commands(),
              creationRequestHash),
          request);
    }
    SpawnTickets.admit(tx, request, true);
    return SpawnTickets.attach(
        tx,
        AcceptCommandsControl.createSessionWithThread(
            tx,
            request.sessionId(),
            request.threadId(),
            parentId,
            rootThreadId,
            request.settings(),
            request.yoloEnabled(),
            request.trigger(),
            effective,
            creationRequestHash,
            request.commands(),
            request.preflight()),
        request);
  }

  private static AcceptedCommands spawnOnExisting(
      HarnessStore.Transaction tx, SpawnRequest request) {
    ThreadState thread =
        tx.lockThread(request.threadId())
            .orElseThrow(
                () ->
                    new HarnessRuntimeNotFoundException(
                        "thread " + request.threadId() + " does not exist"));
    if (!Objects.equals(request.parentThreadId(), thread.parentThreadId())) {
      throw new IllegalArgumentException("join parent differs from immutable child parent");
    }
    return AcceptCommandsControl.acceptOnLockedThread(
        tx,
        thread,
        new AcceptCommandsTarget.Thread(
            request.threadId(),
            request.continuation().expectedHeadEntryId(),
            request.continuation().expectedNextCommandSequence()),
        request.commands(),
        request.preflight(),
        request);
  }
}
