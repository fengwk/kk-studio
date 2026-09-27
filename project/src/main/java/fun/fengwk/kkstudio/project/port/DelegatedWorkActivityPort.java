package fun.fengwk.kkstudio.project.port;

import java.util.UUID;

/**
 * 查询 Thread 的委派子树是否仍有未交付工作，由宿主提供持久任务聚合实现。
 *
 * <p>仅用于判定 Issue Run 的安全静止点，不推进任务状态。
 */
public interface DelegatedWorkActivityPort {

  boolean hasPendingDelegatedWork(UUID threadId);
}
