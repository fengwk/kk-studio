package fun.fengwk.kkstudio.platform.agenttask;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.SpawnRequest;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 平台的 Agent 任务入口：子代理 task 工具、插件 Prompt 任务、Issue 任务与 HTTP 提交都经由它创建
 * Thread、接受首批输入并登记结果票据，再用同一张票据观察、停止与读取结果。
 *
 * <p>提交委托运行时的统一创建原语 {@link HarnessRuntime#spawn}，并加入调用方已有的事务（Issue 在自己的产品事务内提交）。有父 Thread 的任务以
 * {@code NOTIFY_PARENT} 通知父 Thread 并计入并发额度，无父任务以 {@code RECEIPT_ONLY}
 * 只留回执；任务没有额外的创建约束。观察只投影票据已冻结的结果，绝不据子 Thread 当前 head 推测。
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

  /** 提交任务：同一事务内创建（或续用）Thread、接受首批输入并登记票据。 */
  public AgentTaskHandle submit(AgentTaskRequest request) {
    Objects.requireNonNull(request, "request");
    boolean child = request.parentThreadId() != null;
    AcceptedCommands accepted =
        requireRuntime()
            .spawn(
                new SpawnRequest(
                    request.threadId(),
                    request.sessionId(),
                    request.settings(),
                    request.commands(),
                    request.preflight(),
                    request.parentThreadId(),
                    request.expectedParentHeadEntryId(),
                    request.continuation(),
                    request.yoloEnabled(),
                    ThreadConstraints.NONE,
                    request.trigger(),
                    child ? JoinDelivery.NOTIFY_PARENT : JoinDelivery.RECEIPT_ONLY,
                    child,
                    request.budget(),
                    request.ticketId(),
                    request.requestHash()));
    return new AgentTaskHandle(accepted.thread().id(), request.ticketId(), accepted);
  }

  /** 票据的固定凭据；不存在返回空。 */
  public Optional<ThreadJoin> findTicket(UUID ticketId) {
    return requireRuntime().findJoin(Objects.requireNonNull(ticketId, "ticketId"));
  }

  /** 任务当前状态：票据不存在返回空；结果冻结前为 RUNNING，之后带回执。 */
  public Optional<AgentTaskStatus> observe(UUID ticketId) {
    HarnessRuntime runtime = requireRuntime();
    Optional<ThreadJoin> join = runtime.findJoin(Objects.requireNonNull(ticketId, "ticketId"));
    if (join.isEmpty()) {
      return Optional.empty();
    }
    ThreadJoin ticket = join.get();
    ThreadJoinReceipt receipt = runtime.projectJoinReceipt(ticketId).orElse(null);
    AgentTaskStatus.State state =
        receipt == null
            ? AgentTaskStatus.State.RUNNING
            : switch (receipt.outcome()) {
              case COMPLETED -> AgentTaskStatus.State.COMPLETED;
              case ERROR -> AgentTaskStatus.State.ERROR;
              case CANCELLED -> AgentTaskStatus.State.CANCELLED;
            };
    return Optional.of(
        new AgentTaskStatus(ticketId, ticket.childThreadId(), ticket.agent(), state, receipt));
  }

  /** 任务的冻结结果回执；任务不存在或尚未结束返回空。 */
  public Optional<ThreadJoinReceipt> result(UUID ticketId) {
    return requireRuntime().projectJoinReceipt(Objects.requireNonNull(ticketId, "ticketId"));
  }

  /** 按票据停止执行它的 Thread（含其子树）；票据不存在时抛 {@link HarnessRuntimeNotFoundException}。 */
  public void stop(UUID ticketId) {
    ThreadJoin ticket =
        findTicket(ticketId)
            .orElseThrow(
                () ->
                    new HarnessRuntimeNotFoundException(
                        "agent task " + ticketId + " does not exist"));
    stopThread(ticket.childThreadId());
  }

  /** 停止指定 Thread（含其子树）：Thread 已不存在时容忍，stale version 在有界次数内重读重试。 */
  public void stopThread(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    HarnessRuntime runtime = requireRuntime();
    for (int attempt = 0; attempt < STOP_ATTEMPTS; attempt++) {
      ThreadSnapshot snapshot;
      try {
        snapshot = runtime.getThreadSnapshot(threadId);
      } catch (HarnessRuntimeNotFoundException gone) {
        return;
      }
      try {
        runtime.stop(new StopCommand(threadId, UUID.randomUUID(), snapshot.thread().version()));
        return;
      } catch (HarnessRuntimeConflictException conflict) {
        if (conflict.reason() != HarnessRuntimeConflictException.Reason.STALE_VERSION) {
          // 只有真正的 stale version 才是有界 CAS 重读；其它冲突必须明确失败。
          throw conflict;
        }
      } catch (HarnessRuntimeNotFoundException gone) {
        return;
      }
    }
    throw new IllegalStateException(
        "agent task stop did not converge after " + STOP_ATTEMPTS + " stale version retries");
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimes.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    return runtime;
  }
}
