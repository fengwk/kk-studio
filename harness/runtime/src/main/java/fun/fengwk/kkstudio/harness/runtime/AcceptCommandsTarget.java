package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.SubagentContext;

import java.util.Objects;
import java.util.UUID;

/**
 * 单次 {@code acceptCommands} 的 sealed target：NEW_SESSION（新建 Session + ROOT + Thread）、NEW_THREAD（在既有
 * Session 的既有 Entry 下开新 Thread，不复制 Entry）与 THREAD（在既有 Thread 上继续接受 Commands）。本批 Commands 由 {@link
 * AcceptCommandsCommand#commands()} 携带，target 只负责定位 / 初始创建语义，不携带名称——初始名称由 Runtime 在创建时按 Commands
 * 内容派生。
 *
 * <p>NEW_SESSION / NEW_THREAD 的 {@code sessionId} / {@code threadId} 由调用方预分配（initial creation
 * replay 以 client threadId 为查找键）；THREAD 使用 exact 的 head / next-sequence cursor 期望。
 */
public sealed interface AcceptCommandsTarget {

  /**
   * 新建 Session：调用方预分配 {@code sessionId} / {@code threadId}，携带 root settings、可选的 subagent 归属与
   * initial yolo。
   */
  record NewSession(
      UUID sessionId,
      UUID threadId,
      BranchSettings rootSettings,
      SubagentContext subagentContext,
      boolean yoloEnabled)
      implements AcceptCommandsTarget {

    public NewSession {
      Objects.requireNonNull(sessionId, "sessionId");
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(rootSettings, "rootSettings");
    }
  }

  /** 在既有 Session 的 {@code startEntryId} 下开新 Thread；不复制 Entry，Thread head 直接指向该 Entry。 */
  record NewThread(UUID sessionId, UUID startEntryId, UUID threadId, boolean yoloEnabled)
      implements AcceptCommandsTarget {

    public NewThread {
      Objects.requireNonNull(sessionId, "sessionId");
      Objects.requireNonNull(startEntryId, "startEntryId");
      Objects.requireNonNull(threadId, "threadId");
    }
  }

  /** 在既有 Thread 上继续接受 Commands：精确的 head / next-command-sequence cursor 期望。 */
  record Thread(UUID threadId, UUID expectedHeadEntryId, long expectedNextCommandSequence)
      implements AcceptCommandsTarget {

    public Thread {
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(expectedHeadEntryId, "expectedHeadEntryId");
      if (expectedNextCommandSequence <= 0) {
        throw new IllegalArgumentException("expectedNextCommandSequence must be positive");
      }
    }
  }
}
