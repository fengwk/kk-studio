package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;

import java.util.Objects;
import java.util.UUID;

/**
 * 单次 {@code acceptCommands} 的 sealed target：NEW_ROOT_SESSION（新建 Session + ROOT + 独立执行根 Thread）、
 * NEW_CHILD_SESSION（新建 Session + 作为既有 Thread 执行子代理的子 Thread）、NEW_THREAD（在既有 Session 的既有 Entry 下 独立
 * fork 新执行根）与 THREAD（在既有 Thread 上继续接受 Commands）。本批 Commands 由 {@link
 * AcceptCommandsCommand#commands()} 携带，target 只负责定位 / 初始创建语义，不携带名称——初始名称由 Runtime 在创建时按 Commands
 * 内容派生。
 *
 * <p>NEW_ROOT_SESSION / NEW_CHILD_SESSION / NEW_THREAD 的 {@code sessionId} / {@code threadId}
 * 由调用方预分配（initial creation replay 以 client threadId 为查找键）；THREAD 使用 exact 的 head / next-sequence
 * cursor 期望。
 *
 * <p>YOLO 输入只出现在执行根 target：{@link NewRootSession} 与 {@link NewThread} 携带调用方给定的根开关；{@link
 * NewChildSession} 不携带任何开关，Runtime 在树锁内从真实父链派生不可变执行根并写入 {@code FOLLOW(rootThreadId)}，调用方无法指定任意
 * Follow 目标。
 */
public sealed interface AcceptCommandsTarget {

  /**
   * 新建 Session + 独立执行根 Thread：调用方预分配 {@code sessionId} / {@code threadId}，携带 root settings 与根 YOLO
   * 初始开关。
   */
  record NewRootSession(
      UUID sessionId, UUID threadId, BranchSettings rootSettings, boolean yoloEnabled)
      implements AcceptCommandsTarget {

    public NewRootSession {
      Objects.requireNonNull(sessionId, "sessionId");
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(rootSettings, "rootSettings");
    }
  }

  /**
   * 新建 Session 并作为既有 {@code parentThreadId} 的执行子代理：调用方预分配 {@code sessionId} / {@code
   * threadId}，Runtime 在树锁内派生真实执行根，子 Thread 恒为 {@code FOLLOW(rootThreadId)}，不接受调用方给定的开关。
   */
  record NewChildSession(
      UUID sessionId, UUID threadId, BranchSettings rootSettings, UUID parentThreadId)
      implements AcceptCommandsTarget {

    public NewChildSession {
      Objects.requireNonNull(sessionId, "sessionId");
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(rootSettings, "rootSettings");
      Objects.requireNonNull(parentThreadId, "parentThreadId");
    }
  }

  /** 在既有 Session 的 {@code startEntryId} 下独立 fork 新执行根；不复制 Entry，Thread head 直接指向该 Entry。 */
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
