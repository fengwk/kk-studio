package fun.fengwk.kkstudio.harness.runtime.driver;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.List;
import java.util.UUID;

/**
 * Runtime 内部的窄 Agent Driver 契约。
 *
 * <p>Driver 是 Runtime 端口的实现者：只按锁定 Thread 视图准备 Turn、推进已完成响应、投影中立活动/诊断与停止私有执行。Runtime 只装配 Builtin
 * 实现，不动态加载 Driver，不传递 ServiceLocator，也不读取 Driver 私有状态类型。
 *
 * <p>所有方法都在调用方已有事务内执行：不得开启新事务、不得执行外部 I/O、不得执行工具或绕过历史校验。
 */
public interface AgentDriver {

  /** 在调用方事务内解析 candidate Turn；成功时只返回语义计划，具体私有状态由 {@code install} 提交。 */
  TurnResolution resolveTurn(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath candidatePath,
      CompactionPreparation preparation);

  /** 只读取当前 open Turn 的 Driver 状态，不取得 Driver 私有行锁。调用方须已锁定 Thread； 返回值仅用于读取投影，不授予执行或变更权限。 */
  LockedDriverState readState(
      HarnessStore.Transaction tx, ThreadState thread, EntryCurrentState state);

  /** 锁定当前 open Turn 的 Driver 状态，供执行和变更使用。没有状态时返回 {@link DriverActivity#NONE} 的实现。 */
  LockedDriverState lockState(
      HarnessStore.Transaction tx, ThreadState thread, EntryCurrentState state);

  /** 把已终止的 generation 结果物化为公共 Entry/ToolInvocation，并清理 Driver 私有 terminal 状态。 */
  DriverApplied applyResponse(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState state,
      DriverCommit commit);

  /** 公共 tool batch 追加完成后的 Driver 附加校验与清理。没有私有 generation 时必须是 no-op。 */
  void completeToolBatch(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState state,
      List<ToolInvocation> siblings,
      DriverCommit commit);

  /** 按当前 Driver 自己的策略计算下一次自动压缩；返回 {@code null} 表示无需压缩。Runtime 只调用本方法，不反向依赖具体 Driver 的压缩策略实现。 */
  CompactionPreparation prepareAutomaticCompaction(
      ThreadState thread, EntryPath path, CompactionConfig config, boolean hasQueuedMessage);

  /** 撤销 Driver 执行资格；返回已追加 barrier 与需要提交后取消的本地执行身份。 */
  DriverStopResult stop(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState state,
      DriverCommit commit,
      UUID stopRequestId);
}
