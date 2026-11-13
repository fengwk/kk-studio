package fun.fengwk.kkstudio.harness.spi.driver;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.api.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;

import java.util.List;
import java.util.UUID;

/**
 * Runtime 的窄 Agent Driver 契约。
 *
 * <p>Driver 是 Runtime 端口的实现者：只按锁定 Thread 视图准备 Turn、推进已完成响应、投影中立活动/诊断与停止私有执行。组合根向 {@link
 * fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry} 注册所有可用 Driver，Runtime 按 {@code
 * ExecutionSelection.driver} 路由；未安装 Driver 由 {@code UninstalledAgentDriver} 承接。Runtime 不动态加载
 * Driver，不传递 ServiceLocator，也不读取 Driver 私有状态类型。
 *
 * <p>所有方法都在调用方已有事务内执行：不得开启新事务、不得执行外部 I/O、不得执行工具或绕过历史校验。
 */
public interface AgentDriver {

  /** Driver 唯一标识符，匹配 ^[a-z][a-z0-9-]{0,31}$。 */
  String id();

  /** Driver 声明的能力集。 */
  DriverCapabilities capabilities();

  /** 校验 Driver settings JSON object 文本的合法性；不合法时抛出 IllegalArgumentException。 */
  void validateSettings(String settingsJson);

  /**
   * Driver 自己的 Work 类型与处理器；组合根只注册一次，Dispatcher 按类型路由。
   *
   * <p>不得返回内核 THREAD / TOOL；同一类型只能由一个 Driver 注册。默认无 Work。
   */
  default List<DriverWork> works() {
    return List.of();
  }

  /**
   * Stop 事务提交后 best-effort 取消本 Driver 的本地执行。
   *
   * <p>参数是 {@link DriverStopResult#executionIds()} 中的执行身份；实现不得抛出异常来改变 Stop 结果，Runtime 只在边界记录失败。默认
   * no-op。
   */
  default void cancelWork(UUID executionId) {}

  /** 本机是否仍有 Driver 执行；节点优雅退出用它等待排空。默认无活动执行。 */
  default boolean hasActiveWork() {
    return false;
  }

  /**
   * Thread 深删第一阶段：按 Driver 锁阶梯锁定私有行，返回这些行持有的 Work target。
   *
   * <p>在 Command 已锁定、ToolInvocation 未锁定前调用；实现只使用调用方事务，不删除任何事实。默认无私有状态。
   */
  default List<WorkTarget> lockThreadStateForDeletion(
      HarnessStore.Transaction tx, List<UUID> threadIds) {
    return List.of();
  }

  /**
   * Thread 深删第二阶段：Work 与 ToolInvocation 已删除后，删除第一阶段已锁定的私有行。
   *
   * <p>必须在 Thread 行删除前完成；实现不得重新解释删除顺序。默认无私有状态。
   */
  default void deleteLockedThreadState(
      HarnessStore.Transaction tx, List<UUID> threadIds, List<WorkTarget> lockedWorkTargets) {}

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

  /**
   * 是否允许在本安全边界消费 queued settings 或输入。
   *
   * <p>Runtime 在所有收割排队输入的调度边界统一询问 Driver：
   *
   * <ul>
   *   <li>在安全边界提取并应用排队独立设置（{@code applyPendingSettings}）前；
   *   <li>在 {@code ContinuationDue} 边界判定是否收割排队输入（转为 {@code INPUT} 原因）前；
   *   <li>在 {@code IdleOrHistorical} 边界判定是否收割排队输入以开启新 {@code INPUT} 回前后。
   * </ul>
   *
   * <p>默认返回 {@code true}（如 Builtin 保持既有优先队列语义）；Driver 在私有周期冻结或仍 active 时可返回 {@code false}，
   * 阻止新输入或设置在此时被收割。实现只在调用方事务内读取状态，不开启事务、不执行外部 I/O。
   */
  default boolean allowsQueuedInputHarvest(
      HarnessStore.Transaction tx, ThreadState thread, EntryPath path) {
    return true;
  }

  /** Stop 已推进公共 Thread head 后调用；默认 no-op。外部 Driver 用来把私有 record 的 current head 与已提交 Thread 对齐。 */
  default void completeStop(HarnessStore.Transaction tx, ThreadState thread, DriverCommit commit) {}

  /**
   * 撤销 Driver 执行资格；返回已追加 barrier 与需要提交后取消的本地执行身份。
   *
   * <p>Tool 批次停止时，公共工具结果已物化且工具子行已删除，但 {@code thread} 与 {@code path} 仍指向结算前的 assistant head，供 Driver
   * 校验并清理私有父记录；全部操作属于同一事务。
   */
  DriverStopResult stop(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState state,
      DriverCommit commit,
      UUID stopRequestId);
}
