package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;
import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitLedger;
import fun.fengwk.kkstudio.harness.spi.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.util.List;

/**
 * {@code acceptCommands} 与统一 spawn 共用的批量校验与 admission。
 *
 * <p>本类只读取已锁定的 durable 事实并拒绝非法请求，不写任何行：命令 batch 形状、THREAD 游标准入、fork 边界与 Driver 选择、
 * 执行根环境约束、preflight 结果形状以及 STOPPED 目标恢复前必须收敛的退出/子 Join 事实都在这里判定。持久化路径由 {@link
 * AcceptCommandsControl} 编排，精确重放由 {@link AcceptCommandsReplay} 承担。
 */
final class AcceptCommandsValidation {

  /** SET_* prefix 的固定顺序：SET_AGENT -&gt; SET_EXECUTION -&gt; SET_ENVIRONMENT，每类至多一次、全部在消息之前。 */
  private static final List<ThreadCommandType> SET_PREFIX_ORDER =
      List.of(
          ThreadCommandType.SET_AGENT,
          ThreadCommandType.SET_EXECUTION,
          ThreadCommandType.SET_ENVIRONMENT);

  private AcceptCommandsValidation() {}

  /** NEW_THREAD 的合法 fork 边界：只有 ROOT 或已闭合 {@code TURN_END}。 */
  static void requireLegalForkBoundary(Entry startEntry) {
    requireLegalForkBoundary(startEntry, "NEW_THREAD");
  }

  /**
   * NEW_THREAD / NEW_FORKED_SESSION 的合法 fork 边界：只有 ROOT 或已闭合 {@code TURN_END}。其它位置都是 active / 半轮 /
   * 未闭合前缀，必须原子拒绝；这里只按 start Entry 自身判定，不写源 Thread、不补写 synthetic closure，也不做任何 source Thread 推断。
   */
  static void requireLegalForkBoundary(Entry startEntry, String target) {
    if (startEntry.payload().type().isRoot() || startEntry.payload() instanceof TurnEndPayload) {
      return;
    }
    throw DomainException.validation(
        "startEntry",
        "start entry "
            + startEntry.id()
            + " is not a legal "
            + target
            + " fork boundary (ROOT or a closed TURN_END)");
  }

  /** 创建型 target 的 batch shape：允许固定顺序的 SET_* 前缀，并必须以恰一条 user-like 输入结尾。 */
  static void validateBatchShape(AcceptCommandsTarget target, List<NewThreadCommand> commands) {
    validateBatchShape(target, commands, true);
  }

  static void validateBatchShape(
      AcceptCommandsTarget target, List<NewThreadCommand> commands, boolean requireUserLike) {
    validateBatchShape(target.getClass().getSimpleName(), commands, requireUserLike);
  }

  /**
   * 与 target 无关的 batch shape 校验：统一 spawn 在写入任何行之前复用同一组规则，避免复制第二套或伪造 accept target。
   *
   * <p>SET_* 必须以固定顺序（SET_AGENT -&gt; SET_EXECUTION -&gt; SET_ENVIRONMENT）、至多一次且全部出现在消息之前。 创建类
   * target（NEW_SESSION / NEW_THREAD）要求<b>恰有一条</b>末尾 user-like 输入；THREAD target 允许 settings-only
   * batch（0 条 user-like），最多一条 user-like 且必须位于末尾。非法 batch 是请求校验错误，抛 {@link DomainException} 而非业务冲突。
   *
   * @param targetLabel 用于错误消息的来源标签
   */
  static void validateBatchShape(
      String targetLabel, List<NewThreadCommand> commands, boolean requireUserLike) {
    int userLikeCount = 0;
    int lastSetOrder = -1;
    boolean sawMessage = false;
    for (NewThreadCommand command : commands) {
      ThreadCommandPayload payload = command.payload();
      if (payload.type().isSetting()) {
        if (sawMessage) {
          throw invalidBatch(targetLabel, "SET_* commands must precede all messages");
        }
        int order = SET_PREFIX_ORDER.indexOf(payload.type());
        if (order <= lastSetOrder) {
          throw invalidBatch(
              targetLabel, "SET_* prefix must use the fixed order and each type at most once");
        }
        lastSetOrder = order;
        continue;
      }
      sawMessage = true;
      if (isUserLike(command)) {
        userLikeCount++;
      }
    }
    boolean trailingUserLike = !commands.isEmpty() && isUserLike(commands.get(commands.size() - 1));
    if (userLikeCount > 1
        || (requireUserLike && (userLikeCount != 1 || !trailingUserLike))
        || (userLikeCount == 1 && !trailingUserLike)) {
      throw invalidBatch(
          targetLabel,
          requireUserLike
              ? "batches must end with exactly one user-like input"
              : "batches may contain at most one user-like input and it must be the last command");
    }
    if (commands.isEmpty()) {
      throw invalidBatch(targetLabel, "batches must not be empty");
    }
  }

  /**
   * THREAD 全新 batch 的 admission：cursor-bound 批次必须精确匹配 head / next sequence，stale cursor 确定性拒绝；单条纯
   * USER_MESSAGE 批次按锁内当前邮箱尾部入队，不读取客户端的 head / sequence。SET_*（含 SET_ENVIRONMENT）只入队、由 Reducer 于下一个
   * INPUT 边界收割，不参与 admission——即使 Thread 处于 live Model / Tool 或有 queued 消息 / THREAD Work 也照常接受。
   */
  static void validateThreadBatchAdmission(
      AcceptCommandsTarget.Thread target, ThreadState thread, boolean cursorFreeBatch) {
    if (cursorFreeBatch) {
      return;
    }
    if (!thread.headEntryId().equals(target.expectedHeadEntryId())
        || thread.nextCommandSequence() != target.expectedNextCommandSequence()) {
      throw conflict(
          HarnessConflictReason.STALE_COMMAND_CURSOR,
          "thread "
              + thread.id()
              + " head/next command sequence does not match the batch expectation");
    }
  }

  /** 任何显式新 USER/CUSTOM 可信输入都把 STOPPED 目标恢复为 RUNNABLE；不复活任何后代。 */
  static boolean resumeStoppedTarget(ThreadState thread, boolean genuineUserInput) {
    return genuineUserInput && thread.executionControl().isStopped();
  }

  /** 恢复输入要求本 Thread 已无未确认执行，且直接子 Join（含 owner 压缩结算义务）已收敛。SET 等维护输入不恢复，因此不受此限制。 */
  static boolean resumeConverged(HarnessStore.Transaction tx, ThreadState thread) {
    return !ExecutionExitLedger.hasUnconfirmed(tx, thread.id())
        && tx.joins().countIncompleteChildren(thread.id()) == 0
        && !tx.joins().hasIncompleteOwnerChild(thread.id());
  }

  /** 可信任务输入：USER_MESSAGE、CUSTOM_MESSAGE；系统通知只经 NOTIFICATION Command，不是输入来源。 */
  static boolean isGenuineUserInput(List<NewThreadCommand> commands) {
    for (NewThreadCommand command : commands) {
      if (command.payload() instanceof UserMessageCommandPayload
          || command.payload() instanceof CustomMessageCommandPayload) {
        return true;
      }
    }
    return false;
  }

  static void requireRootForEnvironment(ThreadState thread, List<NewThreadCommand> commands) {
    if (!thread.id().equals(thread.rootThreadId())
        && commands.stream()
            .anyMatch(command -> command.payload().type() == ThreadCommandType.SET_ENVIRONMENT)) {
      throw DomainException.validation(
          "commands", "only the execution root can set an environment");
    }
  }

  static void requirePreflightShape(
      List<NewThreadCommand> requests, List<NewThreadCommand> prepared) {
    if (prepared == null) {
      throw new IllegalStateException("command preflight returned null");
    }
    if (prepared.size() != requests.size()) {
      throw new IllegalStateException(
          "command preflight must return exactly "
              + requests.size()
              + " commands, got "
              + prepared.size());
    }
    for (int i = 0; i < prepared.size(); i++) {
      NewThreadCommand request = requests.get(i);
      NewThreadCommand result = prepared.get(i);
      if (result == null
          || !result.idempotencyKey().equals(request.idempotencyKey())
          || !result.requestHash().equals(request.requestHash())) {
        throw new IllegalStateException(
            "command preflight must preserve idempotencyKey and requestHash at index " + i);
      }
    }
  }

  static void validateExecutionCommandsOnNewRoot(
      DriverRegistry driverRegistry, BranchSettings rootSettings, List<NewThreadCommand> commands) {
    if (rootSettings.execution() != null) {
      driverRegistry.validate(rootSettings.execution());
    }
    for (NewThreadCommand command : commands) {
      if (command.payload() instanceof SetExecutionCommandPayload setExecution) {
        driverRegistry.validate(setExecution.execution());
      }
    }
  }

  static void validateForkExecutionCommands(
      DriverRegistry driverRegistry,
      BranchSettings sourceSettings,
      List<NewThreadCommand> commands,
      String context) {
    AgentDriver sourceDriver = driverRegistry.of(sourceSettings);
    if (!sourceDriver.capabilities().fork()) {
      throw DomainException.validation(
          "execution", "fork is unsupported by driver " + sourceDriver.id() + " in " + context);
    }
    for (NewThreadCommand command : commands) {
      if (command.payload() instanceof SetExecutionCommandPayload setExecution) {
        driverRegistry.validate(setExecution.execution());
        if (!sourceDriver.id().equals(setExecution.execution().driver())) {
          throw DomainException.validation(
              "execution",
              "driver cannot be changed in fork: source="
                  + sourceDriver.id()
                  + ", requested="
                  + setExecution.execution().driver());
        }
      }
    }
  }

  static void validateExecutionCommandsOnThread(
      BranchSettings currentSettings,
      List<NewThreadCommand> commands,
      DriverRegistry driverRegistry) {
    for (NewThreadCommand command : commands) {
      if (command.payload() instanceof SetExecutionCommandPayload setExecution) {
        driverRegistry.validate(setExecution.execution());
        if (currentSettings.execution() != null
            && !currentSettings.execution().driver().equals(setExecution.execution().driver())) {
          throw DomainException.validation(
              "execution",
              "driver cannot be changed on an existing thread: current="
                  + currentSettings.execution().driver()
                  + ", requested="
                  + setExecution.execution().driver());
        }
      }
    }
  }

  private static boolean isUserLike(NewThreadCommand command) {
    return command.payload() instanceof UserMessageCommandPayload
        || command.payload() instanceof CustomMessageCommandPayload;
  }

  private static DomainException invalidBatch(String targetLabel, String message) {
    return DomainException.validation("commands", targetLabel + " batch: " + message);
  }

  private static DomainException conflict(HarnessConflictReason reason, String message) {
    return DomainException.conflict(reason.name(), message);
  }
}
