package fun.fengwk.kkstudio.platform.project.tool;

import java.util.UUID;

/**
 * Harness Thread 的 Issue Agent 归属判定端口。
 *
 * <p>Issue Agent 的 Thread（含同一 Session 的兄弟分支）由 Project 策略与 Issue 编排持有，通用 Thread 控制面不得覆盖其 YOLO 或
 * Stop；判定必须只依赖持久化归属事实，不能先触碰运行中的 Runtime。
 */
public interface ProjectThreadOwnerResolver {

  /** 该 Thread 是否属于任何 Issue+Agent 稳定归属（含同一 Session 的兄弟分支）。 */
  boolean isIssueAgentBranch(UUID threadId);
}
