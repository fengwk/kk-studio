package fun.fengwk.kkstudio.harness.runtime.history;

import java.util.Objects;
import java.util.UUID;

/**
 * fork 事实 Entry payload：{@link EntryType#FORK}。
 *
 * <p>只记录最小事实：fork 模式、作为切点的来源 Entry，以及会话 fork 时的来源 Thread；分支 fork 与来源同处一个 Session，因此不携带来源
 * Thread。不解析谱系、不复制执行树、不携带任何行为引导。模型可见文本是固定的 {@link #NOTICE}，只陈述事实，与既有平台通知一样是 USER 角色的 上下文，不是 system
 * 指令。
 *
 * @param mode fork 模式
 * @param sourceEntryId 作为切点的来源 Entry（分支 fork 为共享前缀的边界；会话 fork 为复制上下文的边界）
 * @param sourceThreadId 会话 fork 的来源 Thread；分支 fork 为 null
 */
public record ForkPayload(ForkMode mode, UUID sourceEntryId, UUID sourceThreadId)
    implements EntryPayload {

  /** 固定的英文 fork 通知：只陈述事实，不包含行为引导，也不把继承的历史任务提升为指令。 */
  public static final String NOTICE =
      "This thread was forked. Earlier tool calls and background tasks will not resume or report"
          + " here. Subagent thread IDs inherited from history are no longer valid.";

  public ForkPayload {
    mode = Objects.requireNonNull(mode, "mode");
    sourceEntryId = Objects.requireNonNull(sourceEntryId, "sourceEntryId");
    if (mode == ForkMode.SESSION && sourceThreadId == null) {
      throw new IllegalArgumentException("SESSION fork requires a sourceThreadId");
    }
  }

  /** 同 Session 分支 fork 事实：不携带来源 Thread。 */
  public static ForkPayload branch(UUID sourceEntryId) {
    return new ForkPayload(ForkMode.BRANCH, sourceEntryId, null);
  }

  /** 会话 fork 事实：携带来源 Thread 与切点 Entry。 */
  public static ForkPayload session(UUID sourceThreadId, UUID sourceEntryId) {
    return new ForkPayload(ForkMode.SESSION, sourceEntryId, sourceThreadId);
  }

  @Override
  public EntryType type() {
    return EntryType.FORK;
  }
}
