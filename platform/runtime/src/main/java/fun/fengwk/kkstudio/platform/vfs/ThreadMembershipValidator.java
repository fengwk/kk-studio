package fun.fengwk.kkstudio.platform.vfs;

import java.util.UUID;

/** Platform VFS 线程归属校验器：判断目标 Thread 是否属于给定的根会话（即当前对话）。 */
@FunctionalInterface
public interface ThreadMembershipValidator {

  /**
   * 判断指定 {@code threadId} 是否属于 {@code rootSessionId} 所在的当前对话。
   *
   * @param rootSessionId 当前对话的根会话 ID
   * @param threadId 待校验的线程 ID
   * @return 若该线程属于当前对话返回 {@code true}，否则返回 {@code false}
   */
  boolean isThreadInSession(UUID rootSessionId, UUID threadId);
}
