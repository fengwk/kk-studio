package fun.fengwk.kkstudio.platform.agenttask;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;
import fun.fengwk.kkstudio.harness.api.SpawnRequest;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 平台的 Agent 任务入口：子代理 task 工具与插件 Prompt 任务都经由它创建 Thread、接受首批输入并登记结果票据，之后按 Thread 停止与读取票据结果。
 *
 * <p>提交委托运行时的统一创建原语 {@link HarnessRuntime#spawn}，并加入调用方已有的事务。有父 Thread 的任务根据内部 {@code async}
 * 选择通知交付或同步工具结果交付，并计入并发额度；无父任务以 {@code RECEIPT_ONLY} 只留回执。读取结果只投影票据已冻结的回执，绝不据子 Thread 当前 head 推测。
 *
 * <p>运行时以 {@link ObjectProvider} 惰性解析：只装配 Platform 的上下文不会因缺少运行时而启动失败，真正使用时确定失败。
 */
@Component
public class AgentTaskService {

  /** stop 的 CAS 重读上限：只有真正的 stale version 才重试，耗尽后受控失败而不是静默返回。 */
  private static final int STOP_ATTEMPTS = 3;

  private final ObjectProvider<HarnessRuntime> runtimes;

  @Autowired
  public AgentTaskService(ObjectProvider<HarnessRuntime> runtimes) {
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
  }

  /** 提交任务：同一事务内创建（或续用）Thread、接受首批输入并登记票据。 直接提交给运行时，保留 subagent 等内部创建屏障。 */
  public AgentTaskHandle submit(AgentTaskRequest request) {
    Objects.requireNonNull(request, "request");
    return submitToRuntime(requireRuntime(), request);
  }

  private AgentTaskHandle submitToRuntime(HarnessRuntime runtime, AgentTaskRequest request) {
    boolean child = request.parentThreadId() != null;
    AcceptedCommands accepted =
        runtime.spawn(
            new SpawnRequest(
                request.threadId(),
                request.sessionId(),
                request.settings(),
                request.commands(),
                request.parentThreadId(),
                child ? request.expectedParentHeadEntryId() : null,
                request.continuation(),
                request.yoloEnabled(),
                ThreadConstraints.NONE,
                request.kind(),
                child
                    ? (request.async()
                        ? JoinDelivery.NOTIFY_PARENT
                        : JoinDelivery.SETTLE_TOOL_RESULT)
                    : JoinDelivery.RECEIPT_ONLY,
                request.budget(),
                request.ticketId(),
                request.requestHash()),
            request.preflight());
    return new AgentTaskHandle(accepted.thread().id(), request.ticketId(), accepted);
  }

  /** 票据的冻结结果回执；任务不存在或尚未结束返回空。 */
  public Optional<ThreadJoinReceipt> result(UUID ticketId) {
    return requireRuntime().projectJoinReceipt(Objects.requireNonNull(ticketId, "ticketId"));
  }

  /** 停止指定 Thread（含其子树）：Thread 已不存在时容忍，stale version 在有界次数内重读重试。 */
  public void stopThread(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    HarnessRuntime runtime = requireRuntime();
    for (int attempt = 0; attempt < STOP_ATTEMPTS; attempt++) {
      ThreadSnapshot snapshot;
      try {
        snapshot = runtime.getThreadSnapshot(threadId);
      } catch (DomainException gone) {
        if (gone.code() != DomainErrorCode.NOT_FOUND || !"thread".equals(gone.resource())) {
          throw gone;
        }
        return;
      }
      try {
        runtime.stop(new StopCommand(threadId, UUID.randomUUID(), snapshot.thread().version()));
        return;
      } catch (DomainException failure) {
        if (failure.code() == DomainErrorCode.NOT_FOUND && "thread".equals(failure.resource())) {
          return;
        }
        if (failure.code() != DomainErrorCode.CONFLICT
            || !HarnessConflictReason.STALE_VERSION.name().equals(failure.reason())) {
          throw failure;
        }
      }
    }
    throw new IllegalStateException(
        "thread stop did not converge after " + STOP_ATTEMPTS + " stale version retries");
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimes.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    return runtime;
  }
}
