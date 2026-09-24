package fun.fengwk.kkstudio.platform.project.repo;

import java.util.UUID;

/** 只读 Project 的 Issue Agent 工作 Branch 上 Harness 调用生命周期事实。 */
public interface IssueHarnessInvocationRepository {

  /**
   * 该 Project 是否存在未收尾的 Model/Tool 调用（READY/DISPATCHING/RUNNING 与 WAITING_APPROVAL），与 IssueRun
   * 是否为终态无关。
   */
  boolean hasNonTerminalByProjectId(UUID projectId);
}
