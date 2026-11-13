package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.api.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 纯运行时压缩规划器：基于 root-to-head EntryPath、{@link CompactionConfig} 与触发 turn 的冻结 {@code contextWindow}
 * 计算一次压缩 turn 的切分事实与 removed-prefix 估算。
 *
 * <p>previous-context only：摘要范围 = 最新 complete 压缩的 latest summary + {@code [previousCut, newCut)}
 * 增量； Entry 不删除、不复制 retainedTail。cut 选择对齐上游 Pi {@code findCutPoint}：从路径尾部按估计消息 token（{@code
 * ceil(chars / 4)}，媒体用固定占位）向 boundary start 累加，首个累计越过 {@code effectiveKeep} 的位置之后取下一个合法 cut
 * point（USER/ASSISTANT 上下文消息、CUSTOM_MESSAGE 或 AssistantAborted，绝不切在 ToolResult 或 COMPACTION turn
 * 内部条目）；从未越过则取范围内最早的合法 cut point。
 *
 * <p>切分检测：cut 非 USER 时从最近 INPUT 的首个 USER/CUSTOM 开始，并把后续 CONTINUATION durable turns 视为同一逻辑 Agent
 * segment；切分且 HISTORY 段为空时直接执行 TURN_PREFIX，HISTORY 段非空时为 HISTORY→TURN_PREFIX 两段。{@link
 * #prepareTurnPrefix} 精确引用紧邻已关闭 HISTORY 结果 Entry（{@code historyCompactionEntryId}），绝不扫描 stale
 * partial。
 *
 * <p>本类只计算切分事实与 {@link CompactionPreparation#removedPrefixTokens()}。Resolver 按实际 execution model 解析
 * context window / max output 后调用 {@link CompactionConfig#outputBudget} 计算请求预算；切分与 removed-prefix
 * 使用的 token 估算和上下文可见性规则统一由 {@link TokenEstimator} 提供，本类不持有第二份口径。
 */
public final class CompactionPlanner {

  private final CompactionConfig config;

  public CompactionPlanner(CompactionConfig config) {
    this.config = Objects.requireNonNull(config, "config");
  }

  /** 计算一次新压缩（primary）；无内容可摘要时返回 empty（调用方不得启动压缩 turn）。 */
  public Optional<CompactionPreparation> prepare(
      EntryPath path, CompactionTrigger trigger, long contextWindow) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(trigger, "trigger");
    if (contextWindow <= 0) {
      throw new IllegalArgumentException("contextWindow must be positive");
    }
    Optional<CompactionCutPlanner.Cut> plannedCut =
        new CompactionCutPlanner(config).plan(path, contextWindow);
    if (plannedCut.isEmpty()) {
      return Optional.empty();
    }
    List<Entry> entries = path.entries();
    boolean[] visible = TokenEstimator.visibilityMask(entries);
    int boundaryStart = 0;
    Optional<CompactionTurns.CompactionTurn> latest = CompactionTurns.latestComplete(path);
    if (latest.isPresent()) {
      boundaryStart = indexOfId(entries, latest.get().freezing().cutEntryId());
      if (boundaryStart < 0) {
        throw new IllegalStateException("complete compaction cut is not on the current path");
      }
    }
    int cutIndex = plannedCut.get().pathIndex();
    List<AgentMessage> messages = contextMessages(entries, visible, boundaryStart, cutIndex);
    if (messages.isEmpty()) {
      return Optional.empty();
    }
    long removedPrefixTokens = TokenEstimator.estimateMessages(messages);
    return Optional.of(
        new CompactionPreparation(
            CompactionPhase.FULL,
            trigger,
            plannedCut.get().entryId(),
            null,
            null,
            previousSummary(entries),
            messages,
            removedPrefixTokens));
  }

  /**
   * 机械 TURN_PREFIX 延续：使用紧邻前一个已关闭 HISTORY 压缩结果的精确冻结事实（{@code historyTurn}）， {@code
   * historyCompactionEntryId} 精确引用该 HISTORY 结果 Entry；绝不重新选 cut，也绝不扫描 stale partial。
   */
  public CompactionPreparation prepareTurnPrefix(
      EntryPath path, CompactionTurns.CompactionTurn historyTurn) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(historyTurn, "historyTurn");
    if (historyTurn.phase() != CompactionPhase.HISTORY) {
      throw new IllegalStateException(
          "TURN_PREFIX continuation requires an incomplete HISTORY compaction turn");
    }
    if (!historyTurn.completed()) {
      throw new IllegalStateException(
          "TURN_PREFIX continuation requires a completed HISTORY compaction turn");
    }
    CompactionStart frozen = historyTurn.freezing();
    if (frozen.turnPrefixStartEntryId() == null || historyTurn.result() == null) {
      throw new IllegalStateException(
          "completed HISTORY compaction turn must carry turnPrefixStartEntryId and a result");
    }
    List<Entry> entries = path.entries();
    int cutIndex = indexOfId(entries, frozen.cutEntryId());
    int prefixStartIndex = indexOfId(entries, frozen.turnPrefixStartEntryId());
    UUID historyResultEntryId = entries.get(historyTurn.resultIndex()).id();
    if (cutIndex < 0 || prefixStartIndex < 0 || prefixStartIndex >= cutIndex) {
      throw new IllegalStateException(
          "HISTORY compaction references are not on the current path: cutEntryId="
              + frozen.cutEntryId()
              + " turnPrefixStartEntryId="
              + frozen.turnPrefixStartEntryId());
    }
    boolean[] visible = TokenEstimator.visibilityMask(entries);
    List<AgentMessage> prefixMessages =
        contextMessages(entries, visible, prefixStartIndex, cutIndex);
    if (prefixMessages.isEmpty()) {
      throw new IllegalStateException(
          "HISTORY compaction prefix range contains no context messages");
    }
    long removedPrefixTokens = TokenEstimator.estimateMessages(prefixMessages);
    return new CompactionPreparation(
        CompactionPhase.TURN_PREFIX,
        frozen.trigger(),
        frozen.cutEntryId(),
        frozen.turnPrefixStartEntryId(),
        historyResultEntryId,
        null,
        prefixMessages,
        removedPrefixTokens);
  }

  private static String previousSummary(List<Entry> entries) {
    var path = new EntryPath(entries);
    Optional<CompactionTurns.CompactionTurn> latest = CompactionTurns.latestComplete(path);
    if (latest.isEmpty()) {
      return null;
    }
    String stripped =
        CompactionFileSections.stripReservedSections(latest.get().result().summaryText());
    return stripped.isBlank() ? null : stripped;
  }

  /**
   * cut 所属逻辑 Agent segment 的首个可见 USER/CUSTOM 索引。segment 从最近 INPUT 开始并跨越其后的 CONTINUATION durable
   * turns；当前 compaction boundary 内没有 INPUT，或 cut 前没有 user-like 消息时返回 -1。
   */
  private static int turnPrefixStartIndex(
      List<Entry> entries, boolean[] visible, int boundaryStart, int cutIndex) {
    int turnStartIndex = -1;
    for (int i = cutIndex - 1; i >= boundaryStart; i--) {
      if (entries.get(i).payload() instanceof TurnStartPayload start) {
        if (start.reason() == TurnStartReason.INPUT) {
          turnStartIndex = i;
          break;
        }
      }
    }
    if (turnStartIndex < 0) {
      return -1;
    }
    for (int i = turnStartIndex + 1; i < cutIndex; i++) {
      if (visible[i] && isUserLike(entries.get(i))) {
        return i;
      }
    }
    return -1;
  }

  /** 有效合法 cut point 索引（升序）：可见的 USER/ASSISTANT 上下文消息、CUSTOM_MESSAGE 与 AssistantAborted。 */
  private static List<Integer> validCutPoints(
      List<Entry> entries, boolean[] visible, int startIndex) {
    List<Integer> cutPoints = new ArrayList<>();
    for (int i = startIndex; i < entries.size(); i++) {
      if (visible[i] && isCutPoint(entries.get(i))) {
        cutPoints.add(i);
      }
    }
    return List.copyOf(cutPoints);
  }

  private static boolean isCutPoint(Entry entry) {
    if (entry.payload() instanceof MessagePayload message) {
      AgentMessageRole role = message.message().role();
      return role == AgentMessageRole.USER || role == AgentMessageRole.ASSISTANT;
    }
    return entry.payload() instanceof CustomMessagePayload
        || entry.payload() instanceof NotificationPayload
        || entry.payload() instanceof AssistantAbortedPayload;
  }

  /** USER 上下文消息或 CUSTOM_MESSAGE / NOTIFICATION（开启一个 turn 的 user-like 消息）。 */
  private static boolean isUserLike(Entry entry) {
    if (entry.payload() instanceof MessagePayload message) {
      return message.message().role() == AgentMessageRole.USER;
    }
    return entry.payload() instanceof CustomMessagePayload
        || entry.payload() instanceof NotificationPayload;
  }

  private static boolean hasContextMessage(
      List<Entry> entries, boolean[] visible, int fromIndex, int toIndex) {
    for (int i = fromIndex; i < toIndex; i++) {
      if (visible[i] && TokenEstimator.isConversationMessage(entries.get(i))) {
        return true;
      }
    }
    return false;
  }

  /**
   * 提取 [fromIndex, toIndex) 范围内的可见对话消息（MESSAGE / CUSTOM_MESSAGE / AssistantAborted），按路径顺序；
   * COMPACTION turn 内部的条目一律排除。
   */
  private static List<AgentMessage> contextMessages(
      List<Entry> entries, boolean[] visible, int fromIndex, int toIndex) {
    List<AgentMessage> messages = new ArrayList<>();
    for (int i = fromIndex; i < toIndex; i++) {
      if (!visible[i]) {
        continue;
      }
      Entry entry = entries.get(i);
      if (entry.payload() instanceof MessagePayload message) {
        messages.add(message.message());
      } else if (entry.payload() instanceof CustomMessagePayload message) {
        messages.add(message.message());
      } else if (entry.payload() instanceof NotificationPayload notification) {
        messages.add(notification.message());
      } else if (entry.payload() instanceof AssistantAbortedPayload message) {
        messages.add(message.message());
      }
    }
    return List.copyOf(messages);
  }

  private static int indexOfId(List<Entry> entries, UUID entryId) {
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id().equals(entryId)) {
        return i;
      }
    }
    return -1;
  }

  private static int firstAtOrAfter(List<Integer> cutPoints, int index) {
    for (int cutPoint : cutPoints) {
      if (cutPoint >= index) {
        return cutPoint;
      }
    }
    return cutPoints.get(cutPoints.size() - 1);
  }

  /**
   * 检查最后一个非 compaction 普通 assistant 之后是否存在未被普通 assistant 续接消费的 tool results。
   *
   * <p>若存在，返回发起该批次调用的普通 assistant 在 entries 中的索引；否则返回 -1。 USER / CUSTOM / NOTIFICATION 不是普通
   * assistant 续接消费 tool results 的证据。
   */
  private static int unconsumedToolBatchAssistantIndex(
      List<Entry> entries, boolean[] visible, int boundaryStart) {
    int lastAssistantIndex = -1;
    for (int i = entries.size() - 1; i >= boundaryStart; i--) {
      if (!visible[i]) {
        continue;
      }
      Entry entry = entries.get(i);
      if (entry.payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT) {
        lastAssistantIndex = i;
        break;
      }
    }
    if (lastAssistantIndex < 0) {
      return -1;
    }
    for (int i = lastAssistantIndex + 1; i < entries.size(); i++) {
      if (!visible[i]) {
        continue;
      }
      Entry entry = entries.get(i);
      if (entry.payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.TOOL) {
        return lastAssistantIndex;
      }
    }
    return -1;
  }
}
