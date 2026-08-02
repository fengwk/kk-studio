package fun.fengwk.kkstudio.core.ai.runtime.execution;

import java.time.Instant;

/**
 * Environment Tool 激活队列端口。
 *
 * <p>该端口只负责依据 ToolInvocation 的创建顺序推进指定 Environment 的队头，不把 Environment FIFO 业务泄漏到通用激活存储。
 */
public interface EnvironmentToolActivationQueue {

  /**
   * 在事务中尝试激活指定 Environment 的最老可激活 ToolInvocation。
   *
   * @return 仅当队头被从 PARKED 激活或提前 wakeAt 时返回 true
   */
  boolean activateOldestTool(String environmentName, Instant wakeAt);
}
