package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTurns;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.history.SettingsPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话 fork 的有效上下文复制：把来源 branch 在切点处的模型可见上下文复制为新身份的不可变 Entry。
 *
 * <p>复制范围是「切点处的有效上下文」：存在 complete 压缩时从该压缩 {@code cutEntryId} 所在的完整 Turn 起（摘要由复制过去的 COMPACTION Entry
 * 在投影时重建），否则从 ROOT 之后的完整历史起；两者都以切点（fork 边界，ROOT 或已闭合 TURN_END）收尾。因此每个复制的 Turn 都完整，绝不修补半轮。
 *
 * <p>只复制历史事实：Entry 的 payload 与 provider replay state 原样保留，仅重写跨 Entry 的引用（压缩 cut / turn prefix 引用与
 * tool result 的 assistantEntryId）到新 id，绝不复用旧身份；invocation / command / join 与执行控制绝不复制。
 */
final class SessionForkCopy {

  private SessionForkCopy() {}

  /**
   * 把 {@code sourceCutPath}（ROOT 到切点）的有效上下文复制到 {@code newSessionId}，父链挂在 {@code newRootEntryId}
   * 之后；返回最后复制的 Entry，或内容为空时返回 {@code null}（此时有效上下文只有新建的 ROOT）。
   */
  static Entry copyEffectiveContext(
      HarnessStore.Transaction tx,
      UUID newSessionId,
      UUID newRootEntryId,
      EntryPath sourceCutPath,
      Instant now) {
    List<Entry> entries = sourceCutPath.entries();
    int forkCutIndex = entries.size() - 1;
    int copyStartIndex = effectiveStartIndex(sourceCutPath, entries, forkCutIndex);
    Map<UUID, UUID> newIds = new HashMap<>();
    UUID parentId = newRootEntryId;
    Instant previousCreatedAt = now;
    Entry lastCopied = null;
    for (int i = copyStartIndex; i <= forkCutIndex; i++) {
      Entry source = entries.get(i);
      UUID newId = tx.nextId();
      newIds.put(source.id(), newId);
      Instant createdAt =
          HarnessStoreTime.notBefore(previousCreatedAt.plusMillis(1L), source.createdAt());
      Entry copied =
          new Entry(
              newId,
              newSessionId,
              parentId,
              remapPayload(source.payload(), newIds),
              createdAt,
              source.providerReplayState());
      tx.insertEntry(copied);
      parentId = newId;
      previousCreatedAt = createdAt;
      lastCopied = copied;
    }
    return lastCopied;
  }

  /**
   * 有效上下文起点：存在 complete 压缩时从该压缩 {@code cutEntryId} 所属完整 Turn 的 TURN_START 起（cut
   * 之前的对话消息不复制，由摘要覆盖）；否则从 ROOT 之后起。
   *
   * <p>切点可能落在 Turn 中间（合法 cut point 只需是可见的 USER/ASSISTANT/CUSTOM/Aborted 消息），因此必须回溯到所属 TURN_START
   * 才能构成合法 turn 序列；回合之间的通知 / SETTINGS 也一并回溯复制，保证「紧邻 INPUT 的回合间通知」这一输入前置条件不丢失。
   */
  private static int effectiveStartIndex(EntryPath path, List<Entry> entries, int forkCutIndex) {
    return CompactionTurns.latestComplete(path)
        .map(
            complete -> {
              int compactionCutIndex = indexOfId(entries, complete.freezing().cutEntryId());
              if (compactionCutIndex < 0 || compactionCutIndex > forkCutIndex) {
                throw new IllegalStateException(
                    "complete compaction cut entry is not on the fork source path");
              }
              return extendBackOverBetweenTurnControls(
                  entries, enclosingTurnStartIndex(entries, compactionCutIndex));
            })
        .orElse(1);
  }

  /** 从 {@code index} 向前吸收紧邻的回合间通知 / SETTINGS；绝不越过 ROOT。 */
  private static int extendBackOverBetweenTurnControls(List<Entry> entries, int index) {
    int start = index;
    while (start > 1 && isBetweenTurnControl(entries.get(start - 1).payload())) {
      start--;
    }
    return start;
  }

  private static boolean isBetweenTurnControl(EntryPayload payload) {
    return payload instanceof NotificationPayload || payload instanceof SettingsPayload;
  }

  /** 返回 {@code index} 处 Entry 所属 Turn 的 TURN_START；位于回合之间的控制 Entry 直接返回自身。 */
  private static int enclosingTurnStartIndex(List<Entry> entries, int index) {
    for (int i = index; i >= 0; i--) {
      EntryPayload payload = entries.get(i).payload();
      if (payload instanceof TurnStartPayload) {
        return i;
      }
      if (payload instanceof TurnEndPayload) {
        return index;
      }
    }
    return index;
  }

  /** 重写跨 Entry 引用到复制后的新身份；其它 payload 原样保留为历史事实。 */
  private static EntryPayload remapPayload(EntryPayload payload, Map<UUID, UUID> newIds) {
    if (payload instanceof TurnEndPayload end) {
      return new TurnEndPayload(
          requireMapped(end.turnStartEntryId(), newIds),
          end.outcome(),
          end.continueModel(),
          end.reason(),
          end.closeRequestId());
    }
    if (payload instanceof TurnStartPayload start && start.compaction() != null) {
      CompactionStart compaction = start.compaction();
      return new TurnStartPayload(
          start.reason(),
          start.settings(),
          start.ownerThreadId(),
          start.contextWindow(),
          start.maxOutputTokens(),
          new CompactionStart(
              compaction.phase(),
              compaction.trigger(),
              compaction.executionModel(),
              requireMapped(compaction.cutEntryId(), newIds),
              mappedOrNull(compaction.turnPrefixStartEntryId(), newIds),
              mappedOrNull(compaction.historyCompactionEntryId(), newIds)));
    }
    if (payload instanceof MessagePayload message && message.toolResultMetadata() != null) {
      ToolResultMetadata metadata = message.toolResultMetadata();
      return new MessagePayload(
          message.message(),
          null,
          new ToolResultMetadata(
              metadata.invocationId(),
              requireMapped(metadata.assistantEntryId(), newIds),
              metadata.toolCallId(),
              metadata.callIndex(),
              metadata.status(),
              metadata.synthetic(),
              metadata.reason(),
              metadata.inputReceipt()));
    }
    return payload;
  }

  private static UUID requireMapped(UUID sourceId, Map<UUID, UUID> newIds) {
    UUID mapped = newIds.get(sourceId);
    if (mapped == null) {
      throw new IllegalStateException(
          "fork copy references entry " + sourceId + " outside the copied effective context");
    }
    return mapped;
  }

  private static UUID mappedOrNull(UUID sourceId, Map<UUID, UUID> newIds) {
    return sourceId == null ? null : newIds.get(sourceId);
  }

  private static int indexOfId(List<Entry> entries, UUID entryId) {
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id().equals(entryId)) {
        return i;
      }
    }
    return -1;
  }
}
