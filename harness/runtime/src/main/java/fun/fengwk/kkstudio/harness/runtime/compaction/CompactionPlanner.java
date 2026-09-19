package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AudioMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ImageMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.VideoMessageContent;

import java.util.ArrayList;
import java.util.Arrays;
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
 * partial；{@link #prepareFallback} 复用失败 primary 的 phase/anchors，仅替换 executionModel 为 fallback。
 *
 * <p>本类只计算切分事实与 {@link CompactionPreparation#removedPrefixTokens()}。Resolver 按实际 execution model 解析
 * context window / max output 后调用 {@link CompactionConfig#outputBudget} 计算请求预算；fallback 不复用 primary
 * model 的窗口。
 */
public final class CompactionPlanner {

  /** 非文本媒体块的确定性 token 估算占位字符数（对齐上游 Pi 的 image 估算）。 */
  private static final long ESTIMATED_MEDIA_CHARS = 4_800L;

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
    List<Entry> entries = path.entries();
    boolean[] visible = visibilityMask(entries);

    // boundary start：最新 complete 压缩的 cutEntryId 之后（无则 ROOT 之后）。引用缺失视为分支损坏，fail closed。
    int boundaryStart = 0;
    String previousSummary = null;
    Optional<CompactionTurns.CompactionTurn> latest = CompactionTurns.latestComplete(path);
    if (latest.isPresent()) {
      CompactionTurns.CompactionTurn complete = latest.get();
      List<Entry> completeEntries = path.entries();
      Entry completeResult = completeEntries.get(complete.resultIndex());
      int prevCut = indexOfId(entries, complete.freezing().cutEntryId());
      int prevResult = indexOfId(entries, completeResult.id());
      if (prevCut < 0 || prevResult < 0 || prevCut > prevResult) {
        throw new IllegalStateException(
            "complete compaction references are corrupt on the current path: cutEntryId="
                + complete.freezing().cutEntryId());
      }
      boundaryStart = prevCut;
      String stripped =
          CompactionFileSections.stripReservedSections(complete.result().summaryText());
      previousSummary = stripped.isBlank() ? null : stripped;
    }

    List<Integer> cutPoints = validCutPoints(entries, visible, boundaryStart);
    if (cutPoints.isEmpty()) {
      return Optional.empty();
    }
    int cutIndex = cutPoints.get(0);
    long keepRecentTokens = config.effectiveKeep(contextWindow);
    long accumulated = 0L;
    for (int i = entries.size() - 1; i >= boundaryStart; i--) {
      long tokens = visible[i] ? estimateTokens(entries.get(i)) : 0L;
      if (tokens == 0L) {
        continue;
      }
      accumulated = Math.addExact(accumulated, tokens);
      if (accumulated >= keepRecentTokens) {
        cutIndex = firstAtOrAfter(cutPoints, i);
        break;
      }
    }
    UUID cutEntryId = entries.get(cutIndex).id();

    // 切分检测：cut 非 USER 时，从 cut 所属逻辑 Agent segment 的最近 INPUT 起点取第一个
    // USER/CUSTOM；后续 CONTINUATION durable turns 仍属于同一 segment。
    boolean cutIsUser = isUserLike(entries.get(cutIndex));
    int turnPrefixStartIndex =
        cutIsUser ? -1 : turnPrefixStartIndex(entries, visible, boundaryStart, cutIndex);
    boolean split =
        !cutIsUser
            && turnPrefixStartIndex >= 0
            && hasContextMessage(entries, visible, turnPrefixStartIndex, cutIndex);
    if (!split) {
      List<AgentMessage> messages = contextMessages(entries, visible, boundaryStart, cutIndex);
      if (messages.isEmpty()) {
        return Optional.empty();
      }
      long removedPrefixTokens = estimateMessages(messages);
      return Optional.of(
          new CompactionPreparation(
              CompactionPhase.FULL,
              trigger,
              path.baseSettings().model(),
              cutEntryId,
              null,
              null,
              previousSummary,
              messages,
              removedPrefixTokens));
    }
    // 切分：无更早 HISTORY 段时直接摘要当前 turn prefix；有历史时先 HISTORY 再机械 TURN_PREFIX。
    int historyEndIndex = turnPrefixStartIndex;
    if (!hasContextMessage(entries, visible, boundaryStart, historyEndIndex)) {
      List<AgentMessage> prefixMessages =
          contextMessages(entries, visible, turnPrefixStartIndex, cutIndex);
      if (prefixMessages.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(
          new CompactionPreparation(
              CompactionPhase.TURN_PREFIX,
              trigger,
              path.baseSettings().model(),
              cutEntryId,
              entries.get(turnPrefixStartIndex).id(),
              null,
              null,
              prefixMessages,
              estimateMessages(prefixMessages)));
    }
    List<AgentMessage> historyMessages =
        contextMessages(entries, visible, boundaryStart, historyEndIndex);
    long removedPrefixTokens = estimateMessages(historyMessages);
    return Optional.of(
        new CompactionPreparation(
            CompactionPhase.HISTORY,
            trigger,
            path.baseSettings().model(),
            cutEntryId,
            entries.get(turnPrefixStartIndex).id(),
            null,
            previousSummary,
            historyMessages,
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
    boolean[] visible = visibilityMask(entries);
    List<AgentMessage> prefixMessages =
        contextMessages(entries, visible, prefixStartIndex, cutIndex);
    if (prefixMessages.isEmpty()) {
      throw new IllegalStateException(
          "HISTORY compaction prefix range contains no context messages");
    }
    long removedPrefixTokens = estimateMessages(prefixMessages);
    return new CompactionPreparation(
        CompactionPhase.TURN_PREFIX,
        frozen.trigger(),
        frozen.executionModel(),
        frozen.cutEntryId(),
        frozen.turnPrefixStartEntryId(),
        historyResultEntryId,
        null,
        prefixMessages,
        removedPrefixTokens);
  }

  /**
   * 失败 primary 的 fallback：同 phase/anchors 的第二个 Compaction Turn，仅把 executionModel 替换为 {@link
   * CompactionConfig#fallbackModel()}；HISTORY/TURN_PREFIX 冻结引用原样复用。引用缺失视为分支损坏，fail closed。
   */
  public CompactionPreparation prepareFallback(
      EntryPath path, CompactionTurns.CompactionTurn failedTurn) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(failedTurn, "failedTurn");
    if (failedTurn.end() == null || failedTurn.end().outcome() != TurnEndOutcome.FAILED) {
      throw new IllegalStateException("fallback requires a FAILED compaction turn");
    }
    CompactionStart frozen = failedTurn.freezing();
    if (config.fallbackModel() == null || config.fallbackModel().equals(frozen.executionModel())) {
      throw new IllegalStateException(
          "fallback requires a configured fallbackModel different from the failed executionModel");
    }
    List<Entry> entries = path.entries();
    boolean[] visible = visibilityMask(entries);
    int cutIndex = indexOfId(entries, frozen.cutEntryId());
    if (cutIndex < 0) {
      throw new IllegalStateException(
          "failed compaction cutEntryId is not on the current path: " + frozen.cutEntryId());
    }
    int boundaryStart = boundaryStartIndex(entries);
    String previousSummary = previousSummary(entries);
    List<AgentMessage> messages;
    UUID historyResultEntryId = null;
    if (frozen.phase() == CompactionPhase.FULL) {
      messages = contextMessages(entries, visible, boundaryStart, cutIndex);
    } else {
      int prefixIndex = indexOfId(entries, frozen.turnPrefixStartEntryId());
      if (prefixIndex < 0 || prefixIndex >= cutIndex) {
        throw new IllegalStateException(
            "failed compaction turnPrefixStartEntryId is not on the current path before cut");
      }
      if (frozen.phase() == CompactionPhase.HISTORY) {
        messages = contextMessages(entries, visible, boundaryStart, prefixIndex);
      } else {
        messages = contextMessages(entries, visible, prefixIndex, cutIndex);
        historyResultEntryId = frozen.historyCompactionEntryId();
      }
    }
    if (messages.isEmpty()) {
      throw new IllegalStateException("fallback compaction range contains no context messages");
    }
    long removedPrefixTokens = estimateMessages(messages);
    return new CompactionPreparation(
        frozen.phase(),
        frozen.trigger(),
        config.fallbackModel(),
        frozen.cutEntryId(),
        frozen.turnPrefixStartEntryId(),
        historyResultEntryId,
        frozen.phase() == CompactionPhase.TURN_PREFIX ? null : previousSummary,
        messages,
        removedPrefixTokens);
  }

  /**
   * 按已冻结的 {@link CompactionStart} Entry IDs 重建摘要输入，不重新运行 cut selection。
   *
   * <p>FULL/HISTORY 从最新 complete 压缩的 cut 之后取到 historyEnd；TURN_PREFIX 只取 prefix..cut。previousSummary
   * 仅 FULL/HISTORY 且路径上存在 complete 压缩时非空。
   */
  public static CompactionSummaryInput reconstructSummaryInput(
      EntryPath path, CompactionStart start) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(start, "start");
    List<Entry> entries = path.entries();
    boolean[] visible = visibilityMask(entries);
    int cutIndex = indexOfId(entries, start.cutEntryId());
    if (cutIndex < 0) {
      throw new IllegalStateException(
          "compaction cutEntryId is not on the current path: " + start.cutEntryId());
    }
    if (start.phase() == CompactionPhase.TURN_PREFIX) {
      int prefixIndex = indexOfId(entries, start.turnPrefixStartEntryId());
      if (prefixIndex < 0 || prefixIndex >= cutIndex) {
        throw new IllegalStateException(
            "TURN_PREFIX compaction requires turnPrefixStartEntryId on the current path before cut");
      }
      List<AgentMessage> messages = contextMessages(entries, visible, prefixIndex, cutIndex);
      if (messages.isEmpty()) {
        throw new IllegalStateException("compaction prefix range contains no context messages");
      }
      return new CompactionSummaryInput(messages, null);
    }
    int boundaryStart = boundaryStartIndex(entries);
    String previousSummary = previousSummary(entries);
    int historyEnd =
        start.phase() == CompactionPhase.HISTORY
            ? indexOfId(entries, start.turnPrefixStartEntryId())
            : cutIndex;
    List<AgentMessage> messages = contextMessages(entries, visible, boundaryStart, historyEnd);
    if (messages.isEmpty()) {
      throw new IllegalStateException("compaction history range contains no context messages");
    }
    return new CompactionSummaryInput(messages, previousSummary);
  }

  /**
   * 估算一次普通请求的输入 token：调用方准备好的 system instruction 与当前 path 的可见对话投影之和。供 Resolver 计算
   * “剩余上下文”输出预算，不参与压缩切分。
   */
  public static long estimateRequestTokens(EntryPath path, String systemInstruction) {
    Objects.requireNonNull(systemInstruction, "systemInstruction");
    return Math.addExact(estimateTextTokens(systemInstruction), estimateProjectionTokens(path));
  }

  /** 以最新 complete 压缩 projection 估算当前 provider 上下文 token（wrapper summary + cut 后保留段）。 */
  public static long estimateProjectionTokens(EntryPath path) {
    Objects.requireNonNull(path, "path");
    List<Entry> entries = path.entries();
    boolean[] visible = visibilityMask(entries);
    long tokens = 0L;
    int walkStart = 0;
    Optional<CompactionTurns.CompactionTurn> latest = CompactionTurns.latestComplete(path);
    if (latest.isPresent()) {
      CompactionTurns.CompactionTurn complete = latest.get();
      tokens =
          estimateTextTokens(CompactionPrompts.compactedContext(complete.result().summaryText()));
      walkStart = cutIndex(entries, complete);
    }
    for (int i = walkStart; i < entries.size(); i++) {
      tokens = Math.addExact(tokens, visible[i] ? estimateTokens(entries.get(i)) : 0L);
    }
    return tokens;
  }

  /** 估算以 {@code newSummary} 替换掉 cut 之前内容后的 provider projection token（wrapper + cut 后保留段）。 */
  public static long estimateProjectionTokensAfter(
      EntryPath path, String newSummary, UUID cutEntryId) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(newSummary, "newSummary");
    Objects.requireNonNull(cutEntryId, "cutEntryId");
    List<Entry> entries = path.entries();
    boolean[] visible = visibilityMask(entries);
    int cutIndex = indexOfId(entries, cutEntryId);
    if (cutIndex < 0) {
      throw new IllegalStateException("cutEntryId is not on the current path: " + cutEntryId);
    }
    long tokens = estimateTextTokens(CompactionPrompts.compactedContext(newSummary));
    for (int i = cutIndex; i < entries.size(); i++) {
      tokens = Math.addExact(tokens, visible[i] ? estimateTokens(entries.get(i)) : 0L);
    }
    return tokens;
  }

  /** 估算指定 Entry 之后仍会进入 Provider Context 的可见消息 token；Entry 缺失即分支损坏，fail closed。 */
  public static long estimateVisibleTokensAfter(EntryPath path, UUID entryId) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(entryId, "entryId");
    List<Entry> entries = path.entries();
    int entryIndex = indexOfId(entries, entryId);
    if (entryIndex < 0) {
      throw new IllegalStateException("entryId is not on the current path: " + entryId);
    }
    boolean[] visible = visibilityMask(entries);
    long tokens = 0L;
    for (int i = entryIndex + 1; i < entries.size(); i++) {
      tokens = Math.addExact(tokens, visible[i] ? estimateTokens(entries.get(i)) : 0L);
    }
    return tokens;
  }

  private static int boundaryStartIndex(List<Entry> entries) {
    var path = new EntryPath(entries);
    Optional<CompactionTurns.CompactionTurn> latest = CompactionTurns.latestComplete(path);
    if (latest.isEmpty()) {
      return 0;
    }
    CompactionTurns.CompactionTurn complete = latest.get();
    return cutIndex(entries, complete);
  }

  private static int cutIndex(List<Entry> entries, CompactionTurns.CompactionTurn complete) {
    int cut = indexOfId(entries, complete.freezing().cutEntryId());
    int result = complete.resultIndex();
    if (cut < 0 || result < 0 || cut > result) {
      throw new IllegalStateException(
          "complete compaction cutEntryId is not on the current path before its result");
    }
    return cut;
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

  /**
   * 可见性掩码：TURN_START(COMPACTION)…TURN_END 控制 turn 内部的对话条目（MESSAGE / CUSTOM_MESSAGE /
   * AssistantAborted）不可见；TURN 边界与 CompactionPayload 本身保持可见（后者是重绕边界与 complete 锚点）。
   */
  private static boolean[] visibilityMask(List<Entry> entries) {
    boolean[] visible = new boolean[entries.size()];
    Arrays.fill(visible, true);
    boolean insideCompactionTurn = false;
    for (int i = 0; i < entries.size(); i++) {
      Entry entry = entries.get(i);
      EntryPayload payload = entry.payload();
      if (payload instanceof TurnStartPayload start) {
        if (start.reason() == TurnStartReason.COMPACTION) {
          insideCompactionTurn = true;
        }
        continue;
      }
      if (payload instanceof TurnEndPayload) {
        insideCompactionTurn = false;
        continue;
      }
      if (insideCompactionTurn && isConversationMessage(entry)) {
        visible[i] = false;
      }
    }
    return visible;
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
        || entry.payload() instanceof AssistantAbortedPayload;
  }

  /** USER 上下文消息或 CUSTOM_MESSAGE（开启一个 turn 的 user-like 消息）。 */
  private static boolean isUserLike(Entry entry) {
    if (entry.payload() instanceof MessagePayload message) {
      return message.message().role() == AgentMessageRole.USER;
    }
    return entry.payload() instanceof CustomMessagePayload;
  }

  /** 对话消息（USER/ASSISTANT/TOOL 的 MESSAGE、CUSTOM_MESSAGE、AssistantAborted）——摘要范围在此停止。 */
  private static boolean isConversationMessage(Entry entry) {
    return entry.payload() instanceof MessagePayload
        || entry.payload() instanceof CustomMessagePayload
        || entry.payload() instanceof AssistantAbortedPayload;
  }

  private static boolean hasContextMessage(
      List<Entry> entries, boolean[] visible, int fromIndex, int toIndex) {
    for (int i = fromIndex; i < toIndex; i++) {
      if (visible[i] && isConversationMessage(entries.get(i))) {
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
      } else if (entry.payload() instanceof AssistantAbortedPayload message) {
        messages.add(message.message());
      }
    }
    return List.copyOf(messages);
  }

  private static long estimateMessages(List<AgentMessage> messages) {
    long tokens = 0L;
    for (AgentMessage message : messages) {
      tokens = Math.addExact(tokens, contentTokens(message.contents()));
    }
    return tokens;
  }

  /** 估计一条 Entry 的上下文 token：消息内容字符数之和 {@code ceil(chars / 4)}；非消息为 0。 */
  static long estimateTokens(Entry entry) {
    if (entry.payload() instanceof MessagePayload message) {
      return contentTokens(message.message().contents());
    }
    if (entry.payload() instanceof CustomMessagePayload message) {
      return contentTokens(message.message().contents());
    }
    if (entry.payload() instanceof AssistantAbortedPayload message) {
      return contentTokens(message.message().contents());
    }
    return 0L;
  }

  private static long estimateTextTokens(String text) {
    return (text.length() + 3L) / 4L;
  }

  private static long contentTokens(List<AgentMessageContent> contents) {
    long chars = 0L;
    for (AgentMessageContent content : contents) {
      if (content instanceof TextMessageContent text) {
        chars += text.text().length();
      } else if (content instanceof ThinkingMessageContent thinking) {
        chars += thinking.text().length();
      } else if (content instanceof JsonMessageContent json) {
        chars += json.json().length();
      } else if (content instanceof ToolCallMessageContent call) {
        chars += call.toolName().length() + call.argumentsJson().length();
      } else if (content instanceof ToolResultMessageContent result) {
        chars += contentChars(result.contents());
      } else if (content instanceof ImageMessageContent
          || content instanceof AudioMessageContent
          || content instanceof VideoMessageContent
          || content instanceof ResourceMessageContent) {
        chars += ESTIMATED_MEDIA_CHARS;
      }
    }
    return (chars + 3L) / 4L;
  }

  private static long contentChars(List<AgentMessageContent> contents) {
    long chars = 0L;
    for (AgentMessageContent content : contents) {
      if (content instanceof TextMessageContent text) {
        chars += text.text().length();
      } else if (content instanceof JsonMessageContent json) {
        chars += json.json().length();
      } else if (content instanceof ImageMessageContent
          || content instanceof AudioMessageContent
          || content instanceof VideoMessageContent
          || content instanceof ResourceMessageContent) {
        chars += ESTIMATED_MEDIA_CHARS;
      }
    }
    return chars;
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
}
