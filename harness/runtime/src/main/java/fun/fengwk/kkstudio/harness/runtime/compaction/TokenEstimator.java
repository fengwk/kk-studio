package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.api.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AudioMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ImageMessageContent;
import fun.fengwk.kkstudio.harness.api.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.api.session.VideoMessageContent;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultModelContents;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 请求上下文 token 的纯估算：只用 root-to-head {@link EntryPath} 与调用方已准备好的 system instruction，不读 Store、不读 时钟、不写
 * durable 状态。
 *
 * <p>本类是估算算法与「哪些 Entry 进入 provider 上下文」规则的唯一实现：压缩切分规划、no-gain 判定与 Turn 解析同用，调用方绝不复制口径。 估算对齐上游
 * Pi——文本按 {@code ceil(chars / 4)}，非文本媒体用固定占位字符；压缩投影复用 {@link CompactionTurns} 的 complete 事实与 {@link
 * CompactionPrompts#compactedContext} 的 summary wrapper，因此「估算的上下文」与正式发送的 provider 请求同源。
 *
 * <p>本类是 {@code runtime.compaction} 的实现细节：内核外部只经 {@link
 * fun.fengwk.kkstudio.harness.spi.compaction.RequestTokenEstimator} 端口消费请求估算，端口实现由组合根用本类的 {@link
 * #estimateRequestTokens} 方法引用提供。
 */
public final class TokenEstimator {

  /** 非文本媒体块的确定性 token 估算占位字符数（对齐上游 Pi 的 image 估算）。 */
  private static final long ESTIMATED_MEDIA_CHARS = 4_800L;

  private TokenEstimator() {}

  /**
   * 估算一次普通请求的输入 token：调用方准备好的 system instruction 与当前 path 的可见对话投影之和。供 Turn 解析计算「剩余上下文」
   * 输出预算，不参与压缩切分。
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

  /** 估计一条 Entry 的上下文 token：消息内容字符数之和 {@code ceil(chars / 4)}；非消息为 0。 */
  public static long estimateTokens(Entry entry) {
    if (entry.payload() instanceof MessagePayload message) {
      return contentTokens(message.message().contents());
    }
    if (entry.payload() instanceof CustomMessagePayload message) {
      return contentTokens(message.message().contents());
    }
    if (entry.payload() instanceof NotificationPayload notification) {
      return contentTokens(notification.message().contents());
    }
    if (entry.payload() instanceof AssistantAbortedPayload message) {
      return contentTokens(message.message().contents());
    }
    return 0L;
  }

  /** 估计一段文本的 token：{@code ceil(chars / 4)}。 */
  public static long estimateTextTokens(String text) {
    return (text.length() + 3L) / 4L;
  }

  /** 估计一组上下文消息的 token 之和。 */
  public static long estimateMessages(List<AgentMessage> messages) {
    long tokens = 0L;
    for (AgentMessage message : messages) {
      tokens = Math.addExact(tokens, contentTokens(message.contents()));
    }
    return tokens;
  }

  /**
   * 可见性掩码：TURN_START(COMPACTION)…TURN_END 控制 turn 内部的对话条目（MESSAGE / CUSTOM_MESSAGE /
   * AssistantAborted）不可见； TURN 边界与 CompactionPayload 本身保持可见（后者是重绕边界与 complete 锚点）。返回的数组由调用方持有。
   */
  public static boolean[] visibilityMask(List<Entry> entries) {
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

  /** 对话消息（USER/ASSISTANT/TOOL 的 MESSAGE、CUSTOM_MESSAGE、NOTIFICATION、AssistantAborted）——摘要范围在此停止。 */
  public static boolean isConversationMessage(Entry entry) {
    return entry.payload() instanceof MessagePayload
        || entry.payload() instanceof CustomMessagePayload
        || entry.payload() instanceof NotificationPayload
        || entry.payload() instanceof AssistantAbortedPayload;
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
        chars += contentChars(ToolResultModelContents.contentsOf(result));
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
}
