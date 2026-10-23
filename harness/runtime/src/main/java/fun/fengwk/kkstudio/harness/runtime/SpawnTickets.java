package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 任务票据（Join）的唯一准入与登记路径：{@link AgentSpawner} 的新建与续用两条路径共用，调用方已持有规范锁序下的全部锁。
 *
 * <p>准入先于任何写入，按父状态、父子配额、执行树深度、全局配额的顺序裁决；登记在源命令写入之后插入票据，并 supersede 同一父/子对上既有未完成票据。
 */
final class SpawnTickets {

  private SpawnTickets() {}

  /**
   * 票据准入。{@code creating} 表示本次新建子 Thread（深度比父链多一层）；续用既有 Thread 时深度就是其自身祖先链长度。{@code ticket}
   * 为空表示没有票据（用户直接输入），不做任何裁决。
   */
  static void admit(HarnessStore.Transaction tx, SpawnRequest ticket, boolean creating) {
    if (ticket == null) {
      return;
    }
    UUID childId = ticket.threadId();
    UUID parentId = ticket.parentThreadId();
    // owner 的结算票据不能被续接接管；exact replay 已在调用准入前完成身份校验并返回。
    if (tx.loadIncompleteJoins(childId).stream()
        .anyMatch(pending -> pending.delivery() == JoinDelivery.SETTLE_OWNER_TURN)) {
      throw new IllegalArgumentException("child has an unfinished owner settlement");
    }
    if (parentId != null) {
      ThreadState parent =
          tx.findThread(parentId)
              .orElseThrow(() -> new IllegalArgumentException("join parent does not exist"));
      if (ticket.delivery() == JoinDelivery.NOTIFY_PARENT) {
        // 停止父 Thread 不再接受新的执行子任务：不能只依赖 head 变化，STOPPED 是持久的拒绝条件。
        if (parent.executionControl().isStopped()) {
          throw new IllegalArgumentException("join parent is stopped");
        }
        if (!parent.headEntryId().equals(ticket.expectedParentHeadEntryId())) {
          throw new IllegalArgumentException("join parent no longer accepts this invocation");
        }
      }
      if (ticket.countsTowardQuota()) {
        int projectedChildren =
            tx.countIncompleteChildJoins(parentId) - replacedQuotaJoins(tx, childId, parentId) + 1;
        if (projectedChildren > ticket.budget().maxConcurrentChildren()) {
          throw new IllegalArgumentException("parent join quota exceeded");
        }
      }
    }
    List<UUID> chain = tx.findAncestorChain(creating && parentId != null ? parentId : childId);
    int depth = creating ? chain.size() + 1 : chain.size();
    if (depth > ticket.budget().maxDepth()) {
      throw new IllegalArgumentException("join depth quota exceeded");
    }
    if (parentId != null && ticket.countsTowardQuota()) {
      int projectedGlobal =
          tx.countIncompleteSubagentJoins() - replacedQuotaJoins(tx, childId, parentId) + 1;
      if (projectedGlobal > ticket.budget().maxConcurrentThreads()) {
        throw new IllegalArgumentException("subagent concurrency quota exceeded");
      }
    }
  }

  /** 同一父/子对至多一个有效未完成票据：本次续接会原子上位并 supersede 它，配额按替换后的有效数量判定。 */
  private static int replacedQuotaJoins(HarnessStore.Transaction tx, UUID childId, UUID parentId) {
    return (int)
        tx.loadIncompleteJoins(childId).stream()
            .filter(
                pending -> parentId.equals(pending.parentThreadId()) && pending.countsTowardQuota())
            .count();
  }

  /** 把票据登记到刚接受的源命令上；{@code ticket} 为空时原样返回。 */
  static AcceptedCommands attach(
      HarnessStore.Transaction tx, AcceptedCommands accepted, SpawnRequest ticket) {
    if (ticket == null) {
      return accepted;
    }
    ThreadJoin existing = tx.findJoin(ticket.ticketId()).orElse(null);
    if (existing != null) {
      if (!accepted.replayed()
          || !existing.childThreadId().equals(accepted.thread().id())
          || !Objects.equals(existing.parentThreadId(), ticket.parentThreadId())
          || !existing.requestHash().equals(ticket.requestHash())
          || !existing.agent().equals(ticket.agent())
          || !Objects.equals(existing.maxTurns(), ticket.budget().maxTurns())
          || existing.delivery() != ticket.delivery()
          || existing.countsTowardQuota() != ticket.countsTowardQuota()
          || accepted.acceptedCommands().stream()
              .noneMatch(cmd -> cmd.sequence() == existing.sourceCommandSequence())) {
        throw new IllegalArgumentException("join invocation identity reused");
      }
      return accepted;
    }
    if (accepted.replayed()) {
      throw new IllegalArgumentException("source commands replayed without their join");
    }
    ThreadCommand source = accepted.acceptedCommands().get(accepted.acceptedCommands().size() - 1);
    Instant now = tx.now();
    tx.insertJoin(
        new ThreadJoin(
            ticket.ticketId(),
            ticket.requestHash(),
            ticket.parentThreadId(),
            accepted.thread().id(),
            source.sequence(),
            ticket.agent(),
            ticket.budget().maxTurns(),
            0,
            null,
            null,
            null,
            now,
            now,
            ticket.delivery(),
            ticket.countsTowardQuota(),
            null));
    // 新票据落库后才 supersede 既有未完成票据，保证 superseded_by FK 指向已存在的新行；
    // 旧票据只记录接管身份并保留其 invocation identity 与排队源命令，随后不再占用配额、不再产生重复交付。
    boolean replaced = false;
    if (ticket.parentThreadId() != null) {
      for (ThreadJoin pending : tx.loadIncompleteJoins(accepted.thread().id())) {
        if (!pending.invocationId().equals(ticket.ticketId())
            && ticket.parentThreadId().equals(pending.parentThreadId())) {
          tx.updateJoin(pending.superseded(ticket.ticketId(), now));
          replaced = true;
        }
      }
    }
    return accepted.withJoinReplaced(replaced);
  }
}
