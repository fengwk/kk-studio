package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 纯运行时压缩规划器：基于 root-to-head EntryPath 与 {@link CompactionConfig} 计算一次压缩 turn 的切分事实。
 *
 * <p>算法对齐上游 Pi 的 {@code prepareCompaction} / {@code findCutPoint}：从路径尾部按估计消息 token （{@code
 * ceil(chars / 4)}，image/audio/resource 使用固定占位预算）向 boundary start 累加，首个累计越过 {@code keepRecentTokens
 * = min(floor(contextWindow * 0.5), maxRecentTokens)} 的位置之后取下一个合法 cut point （USER/ASSISTANT 上下文消息、
 * CUSTOM_MESSAGE 或 {@code AssistantAborted}，绝不切在 ToolResult）；若预算从未越过则取范围内最早的合法 cut point。 {@code
 * tokensBefore} 是压缩前的压缩感知上下文估计（wrapper summary + cut 之后保留段，对齐 Pi checkpoint 元数据），只作 durable
 * 元数据，不参与触发（触发只看 provider usage）。
 *
 * <p>firstKept 重绕：从 cut 位置向 boundary start 回退，把相邻的非上下文控制元数据（CUSTOM / TURN 边界 / 错误障碍等，
 * 不含任何对话消息）纳入保留区起点；实际首个保留上下文消息为 {@code cutEntryId}。重绕绝不跨越任何 CompactionPayload 边界 （对齐 Pi 的 {@code
 * prevEntry.type === "compaction"} 停止）。切分检测：cut 非 USER 时，只在 cut 所属同一 INPUT turn 内查找第一个 USER/CUSTOM
 * 上下文消息并记录 {@code turnPrefixStartEntryId}（前缀非空才切分）；绝不跨 TURN_START 把 CONTINUATION 的 Assistant 错归到更早
 * turn。
 *
 * <p>可见性掩码：TURN_START(COMPACTION)…TURN_END 控制 turn 内部的任何对话条目（消息 / CUSTOM / AssistantAborted）一律
 * 不可见（与 DatabaseTurnResolver 投影一致）——不能成为 cut point、token 估计、摘要消息或 firstKept 重绕的对话边界； 被停止的压缩 turn 的
 * AssistantAborted 因此永远不会成为 cut 或进入摘要。CompactionPayload / TURN 边界本身保持可见。
 *
 * <p>{@code prepare} 只做 FULL 派生（按切分事实派生 HISTORY/TURN_PREFIX/FULL）；没有可摘要内容时返回 empty，调用方不得启动压缩
 * turn。切分 HISTORY 部分成功后由 {@link #prepareTurnPrefix} 机械延续：切分事实（ids / tokensBefore / trigger）一律 冻结复用自
 * incomplete payload，绝不重新选 cut；引用缺失或前缀为空视为分支损坏，抛 {@link IllegalStateException}。
 */
public final class CompactionPlanner {

  /** 非文本媒体块的确定性 token 估算占位字符数（对齐上游 Pi 的 image 估算）。 */
  private static final long ESTIMATED_MEDIA_CHARS = 4_800L;

  private final CompactionConfig config;

  public CompactionPlanner(CompactionConfig config) {
    this.config = Objects.requireNonNull(config, "config");
  }

  /** 计算一次 FULL 压缩准备；无内容可摘要时返回 empty（调用方不得启动压缩 turn）。 */
  public Optional<CompactionPreparation> prepare(
      EntryPath path, CompactionTrigger trigger, long contextWindow) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(trigger, "trigger");
    if (contextWindow <= 0) {
      throw new IllegalArgumentException("contextWindow must be positive");
    }
    List<Entry> entries = path.entries();
    long keepRecentTokens = config.effectiveKeepRecentTokens(contextWindow);
    boolean[] visible = visibilityMask(entries);

    // boundary start：上一份 complete 压缩的 firstKeptEntryId 之后；无则从 ROOT 之后开始。
    // firstKeptEntryId 引用缺失视为分支损坏（Entry 从不删除），fail closed。
    int boundaryStart = 0;
    String previousSummary = null;
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).payload() instanceof CompactionPayload payload && payload.complete()) {
        int firstKept = indexOfId(entries, payload.firstKeptEntryId());
        int cut = indexOfId(entries, payload.cutEntryId());
        if (firstKept < 0 || cut < 0 || firstKept > cut || cut >= i) {
          throw new IllegalStateException(
              "complete compaction references are corrupt on the current path: firstKeptEntryId="
                  + payload.firstKeptEntryId()
                  + " cutEntryId="
                  + payload.cutEntryId());
        }
        boundaryStart = firstKept;
        String stripped = CompactionFileSections.stripReservedSections(payload.summaryText());
        previousSummary = stripped.isBlank() ? null : stripped;
      }
    }

    // 合法 cut points：可见的 USER/ASSISTANT 上下文消息、CUSTOM_MESSAGE 与 AssistantAborted，绝不切在 ToolResult
    // 或 COMPACTION turn 内部条目。
    List<Integer> cutPoints = validCutPoints(entries, visible, boundaryStart);
    if (cutPoints.isEmpty()) {
      return Optional.empty();
    }
    int cutIndex = cutPoints.get(0);
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

    // firstKeptEntryId：保留 cut 之前相邻的非上下文控制元数据（不含任何对话消息），实际切分点为 cutEntryId。
    // 重绕绝不跨越 CompactionPayload 边界（对齐 Pi prevEntry.type === "compaction"）；COMPACTION turn 内部
    // 的不可见消息按元数据透明处理，被停止的压缩 turn 不会成为重绕的对话边界。
    int firstKeptIndex = cutIndex;
    while (firstKeptIndex > boundaryStart) {
      Entry previous = entries.get(firstKeptIndex - 1);
      if (previous.payload() instanceof CompactionPayload) {
        break;
      }
      if (isConversationMessage(previous) && visible[firstKeptIndex - 1]) {
        break;
      }
      firstKeptIndex--;
    }
    long firstKeptEntryId = entries.get(firstKeptIndex).id();
    long cutEntryId = entries.get(cutIndex).id();

    // 切分检测：cut 非 USER 时，只在 cut 所属同一 turn 内取第一个 USER/CUSTOM；CONTINUATION 不跨 turn 借用旧 USER。
    boolean cutIsUser = isUserLike(entries.get(cutIndex));
    int turnPrefixStartIndex =
        cutIsUser ? -1 : turnPrefixStartIndex(entries, visible, boundaryStart, cutIndex);
    boolean split =
        !cutIsUser
            && turnPrefixStartIndex >= 0
            && hasContextMessage(entries, visible, turnPrefixStartIndex, cutIndex);

    // 阶段派生：FULL 按切分事实派生；切分且无先前历史时直接 TURN_PREFIX（"No prior history."）。
    int historyEndIndex = split ? turnPrefixStartIndex : cutIndex;
    CompactionPhase phase =
        split
            ? (hasContextMessage(entries, visible, boundaryStart, historyEndIndex)
                ? CompactionPhase.HISTORY
                : CompactionPhase.TURN_PREFIX)
            : CompactionPhase.FULL;

    List<AgentMessage> messagesToSummarize =
        phase == CompactionPhase.TURN_PREFIX
            ? contextMessages(entries, visible, turnPrefixStartIndex, cutIndex)
            : contextMessages(entries, visible, boundaryStart, historyEndIndex);
    if (messagesToSummarize.isEmpty()) {
      // 没有可摘要内容时不做压缩（也不单独重压缩 previous summary，避免空转）。
      return Optional.empty();
    }
    String effectivePreviousSummary = phase == CompactionPhase.TURN_PREFIX ? null : previousSummary;
    Long turnPrefixStartEntryId = split ? entries.get(turnPrefixStartIndex).id() : null;
    return Optional.of(
        new CompactionPreparation(
            phase,
            trigger,
            estimateCompactionAwareTokens(entries),
            contextWindow,
            firstKeptEntryId,
            cutEntryId,
            turnPrefixStartEntryId,
            effectivePreviousSummary,
            messagesToSummarize));
  }

  /**
   * 切分 HISTORY 部分成功后的机械 TURN_PREFIX 延续：所有切分事实（firstKept / cut / turnPrefixStart / tokensBefore /
   * trigger）冻结复用自 {@code incomplete} payload，绝不重新选 cut。引用缺失或前缀段为空视为分支损坏，抛 {@link
   * IllegalStateException}；{@code contextWindow} 沿用 HISTORY invocation 冻结的窗口。
   */
  public CompactionPreparation prepareTurnPrefix(
      EntryPath path, CompactionPayload incomplete, long contextWindow) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(incomplete, "incomplete");
    if (incomplete.phase() != CompactionPhase.HISTORY || incomplete.complete()) {
      throw new IllegalStateException(
          "TURN_PREFIX continuation requires an incomplete HISTORY payload");
    }
    if (incomplete.turnPrefixStartEntryId() == null || contextWindow <= 0) {
      throw new IllegalStateException(
          "incomplete HISTORY payload must carry turnPrefixStartEntryId and a positive context window");
    }
    List<Entry> entries = path.entries();
    int firstKeptIndex = indexOfId(entries, incomplete.firstKeptEntryId());
    int cutIndex = indexOfId(entries, incomplete.cutEntryId());
    int prefixStartIndex = indexOfId(entries, incomplete.turnPrefixStartEntryId());
    if (firstKeptIndex < 0 || cutIndex < 0 || prefixStartIndex < 0) {
      throw new IllegalStateException(
          "incomplete HISTORY payload references are not on the current path: firstKeptEntryId="
              + incomplete.firstKeptEntryId()
              + " cutEntryId="
              + incomplete.cutEntryId()
              + " turnPrefixStartEntryId="
              + incomplete.turnPrefixStartEntryId());
    }
    if (firstKeptIndex > cutIndex || prefixStartIndex >= cutIndex) {
      throw new IllegalStateException(
          "incomplete HISTORY payload requires firstKeptEntryId <= cutEntryId and "
              + "turnPrefixStartEntryId < cutEntryId");
    }
    // COMPACTION turn 内部条目不可见：前缀段内被停止压缩的 turn 消息不进入延续摘要，空则视为分支损坏。
    boolean[] visible = visibilityMask(entries);
    List<AgentMessage> messagesToSummarize =
        contextMessages(entries, visible, prefixStartIndex, cutIndex);
    if (messagesToSummarize.isEmpty()) {
      throw new IllegalStateException(
          "incomplete HISTORY payload prefix range contains no context messages");
    }
    return new CompactionPreparation(
        CompactionPhase.TURN_PREFIX,
        incomplete.trigger(),
        incomplete.tokensBefore(),
        contextWindow,
        incomplete.firstKeptEntryId(),
        incomplete.cutEntryId(),
        incomplete.turnPrefixStartEntryId(),
        null,
        messagesToSummarize);
  }

  /** 压缩感知上下文 token 估计：最新 complete 压缩的 wrapper summary + cut 之后保留段；无压缩时估计全部条目。 */
  static long estimateCompactionAwareTokens(List<Entry> entries) {
    boolean[] visible = visibilityMask(entries);
    long tokens = 0L;
    int walkStart = 0;
    for (int i = 0; i < entries.size(); i++) {
      Entry entry = entries.get(i);
      if (entry.payload() instanceof CompactionPayload payload && payload.complete()) {
        int firstKeptIndex = indexOfId(entries, payload.firstKeptEntryId());
        int cutIndex = indexOfId(entries, payload.cutEntryId());
        if (firstKeptIndex < 0 || cutIndex < 0 || firstKeptIndex > cutIndex || cutIndex >= i) {
          throw new IllegalStateException(
              "complete compaction references are corrupt on the current path: firstKeptEntryId="
                  + payload.firstKeptEntryId()
                  + " cutEntryId="
                  + payload.cutEntryId());
        }
        tokens = estimateTextTokens(CompactionPrompts.compactedContext(payload.summaryText()));
        walkStart = cutIndex;
      }
    }
    for (int i = walkStart; i < entries.size(); i++) {
      long entryTokens = visible[i] ? estimateTokens(entries.get(i)) : 0L;
      tokens = Math.addExact(tokens, entryTokens);
    }
    return tokens;
  }

  /**
   * cut 所属同一 turn 的首个可见 USER/CUSTOM 索引；该 turn 不在当前 compaction boundary 内、不是 INPUT turn，或 cut 前没有
   * user-like 消息时返回 -1。
   */
  private static int turnPrefixStartIndex(
      List<Entry> entries, boolean[] visible, int boundaryStart, int cutIndex) {
    int turnStartIndex = -1;
    for (int i = cutIndex - 1; i >= boundaryStart; i--) {
      if (entries.get(i).payload() instanceof TurnStartPayload start) {
        if (start.reason() != TurnStartReason.INPUT) {
          return -1;
        }
        turnStartIndex = i;
        break;
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

  /** 对话消息（USER/ASSISTANT/TOOL 的 MESSAGE、CUSTOM_MESSAGE、AssistantAborted）——重绕与摘要范围都在此停止。 */
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
          || content instanceof ResourceMessageContent) {
        chars += ESTIMATED_MEDIA_CHARS;
      }
    }
    return chars;
  }

  private static int indexOfId(List<Entry> entries, long entryId) {
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id() == entryId) {
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
