package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;

import java.util.Objects;
import java.util.UUID;

/**
 * 单次 {@code acceptCommands} 的 sealed target：NEW_ROOT_SESSION（新建 Session + ROOT + 独立执行根 Thread）、
 * NEW_CHILD_SESSION（新建 Session + 作为既有 Thread 执行子代理的子 Thread）、NEW_THREAD（在既有 Session 的既有合法边界下 独立
 * fork 新执行根）、NEW_FORKED_SESSION（把既有执行根在合法切点处的有效上下文复制到新 Session 与独立执行根）与 THREAD（在既有 Thread 上继续接受
 * Commands）。本批 Commands 由 {@link AcceptCommandsCommand#commands()} 携带，target 只负责定位 / 初始创建语义；Session
 * 与 ROOT Thread 的名称由 Runtime 在创建时 按 Commands 内容派生，NEW_THREAD 的分支名称则由调用方显式给出并经 {@link
 * Names#normalize} 规范。
 *
 * <p>NEW_ROOT_SESSION / NEW_CHILD_SESSION / NEW_THREAD / NEW_FORKED_SESSION 的 {@code sessionId} /
 * {@code threadId} 由调用方预分配（initial creation replay 以 client threadId 为查找键）；THREAD 使用 exact 的 head /
 * next-sequence cursor 期望。
 *
 * <p>YOLO 输入只出现在执行根 target：{@link NewRootSession}、{@link NewThread} 与 {@link NewForkedSession}
 * 携带调用方给定的根开关；{@link NewChildSession} 不携带任何开关，Runtime 在树锁内从真实父链派生不可变执行根并写入 {@code
 * FOLLOW(rootThreadId)}，调用方无法指定任意 Follow 目标。
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

  /**
   * 在既有 Session 的合法 fork 边界 {@code startEntryId}（ROOT 或已闭合 {@code TURN_END}）下独立 fork 新执行根；不复制
   * Entry， Thread head 直接指向该 Entry。分支显示名由调用方显式给出并经 {@link Names#normalize} 规范，是创建请求身份的一部分。
   */
  record NewThread(
      UUID sessionId, UUID startEntryId, UUID threadId, String threadName, boolean yoloEnabled)
      implements AcceptCommandsTarget {

    public NewThread {
      Objects.requireNonNull(sessionId, "sessionId");
      Objects.requireNonNull(startEntryId, "startEntryId");
      Objects.requireNonNull(threadId, "threadId");
      threadName = Names.normalize(threadName);
    }
  }

  /**
   * 会话 fork：把既有执行根 {@code sourceThreadId} 在合法切点 {@code startEntryId}（ROOT 或已闭合 {@code
   * TURN_END}，且必须是该来源路径上的 Entry）处的有效上下文复制到新 Session，并创建独立执行根 Thread。根 settings 从来源 branch
   * 在切点处的生效快照推导，绝不接受调用方给定的 settings；不复制 join / commands / invocations，也不改变源执行。调用方预分配 {@code
   * sessionId} / {@code threadId}（initial creation replay 以 client threadId 为查找键）。
   */
  record NewForkedSession(
      UUID sourceThreadId, UUID startEntryId, UUID sessionId, UUID threadId, boolean yoloEnabled)
      implements AcceptCommandsTarget {

    public NewForkedSession {
      Objects.requireNonNull(sourceThreadId, "sourceThreadId");
      Objects.requireNonNull(startEntryId, "startEntryId");
      Objects.requireNonNull(sessionId, "sessionId");
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
