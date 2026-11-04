package fun.fengwk.kkstudio.platform.vfs;

import java.util.UUID;

/**
 * Platform VFS 线程归属校验器：判断目标 Thread 是否属于当前对话。
 *
 * <p>判据是「该线程的执行根等于当前调用的执行根」，而不是「线程所在 Session 等于执行根 Session」：子 Agent 运行在独立 Session
 * 中，但执行根仍是当前对话的根线程，因此仍然可以使用根会话目录下的 {@code threads/<线程 ID>/}。
 */
@FunctionalInterface
public interface ThreadMembershipValidator {

  /**
   * 判断指定 {@code threadId} 的执行根是否等于 {@code rootThreadId}。
   *
   * @param rootThreadId 当前调用的执行根线程 ID
   * @param threadId 待校验的线程 ID
   * @return 若该线程属于当前对话返回 {@code true}，否则返回 {@code false}
   */
  boolean isThreadInExecutionRoot(UUID rootThreadId, UUID threadId);
}
