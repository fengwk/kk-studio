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
import java.util.function.Supplier;

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

  /**
   * 提交外部独立任务（仅限 HTTP 等外部请求）：同一事务内创建（或续用）Thread、接受首批输入并登记票据。
   *
   * <p>外部身份由 {@code ticketId} 与 {@code requestHash} 唯一确定；已存在票据先校验请求指纹再重放，避免对已接受任务重复物化设置。 首次提交才经由
   * {@code requestSupplier} 惰性物化请求并调用运行时；校验物化请求与外部身份声明严格一致且无父 Thread。 仅当遭遇 {@code THREAD_ID_REUSED}
   * 精确冲突时，再次复核票据指纹以实现并发安全的回放。
   */
  public UUID submitExternal(
      UUID ticketId, String requestHash, Supplier<AgentTaskRequest> requestSupplier) {
    Objects.requireNonNull(ticketId, "ticketId");
    Objects.requireNonNull(requestHash, "requestHash");
    Objects.requireNonNull(requestSupplier, "requestSupplier");
    HarnessRuntime runtime = requireRuntime();

    Optional<ThreadJoin> existingTicket = runtime.findJoin(ticketId);
    if (existingTicket.isPresent()) {
      ThreadJoin ticket = existingTicket.get();
      if (!ticket.requestHash().equals(requestHash)) {
        throw new IllegalArgumentException("join invocation identity reused");
      }
      return ticket.childThreadId();
    }

    AgentTaskRequest request =
        Objects.requireNonNull(requestSupplier.get(), "requestSupplier returned null");
    if (!ticketId.equals(request.ticketId())) {
      throw new IllegalArgumentException("request supplier returned mismatched ticketId");
    }
    if (!requestHash.equals(request.requestHash())) {
      throw new IllegalArgumentException("request supplier returned mismatched requestHash");
    }
    if (request.parentThreadId() != null) {
      throw new IllegalArgumentException("external agent task cannot have parent thread");
    }

    try {
      return submitToRuntime(runtime, request).threadId();
    } catch (HarnessRuntimeConflictException conflict) {
      if (conflict.reason() == HarnessRuntimeConflictException.Reason.THREAD_ID_REUSED) {
        Optional<ThreadJoin> ticket = runtime.findJoin(ticketId);
        if (ticket.isPresent()) {
          if (!ticket.get().requestHash().equals(requestHash)) {
            throw new IllegalArgumentException("join invocation identity reused", conflict);
          }
          return ticket.get().childThreadId();
        }
      }
      throw conflict;
    }
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

  /** 任务当前状态：票据不存在返回空；有效票据在结果冻结前为 RUNNING，被接管的旧票据为 SUPERSEDED，其余带冻结回执。 */
  public Optional<AgentTaskStatus> observe(UUID ticketId) {
    HarnessRuntime runtime = requireRuntime();
    Optional<ThreadJoin> join = runtime.findJoin(Objects.requireNonNull(ticketId, "ticketId"));
    if (join.isEmpty()) {
      return Optional.empty();
    }
    ThreadJoin ticket = join.get();
    if (ticket.supersededByInvocationId() != null) {
      return Optional.of(
          new AgentTaskStatus(
              ticketId,
              ticket.childThreadId(),
              ticket.agent(),
              AgentTaskStatus.State.SUPERSEDED,
              null));
    }
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

  /**
   * 按票据停止执行它的 Thread（含其子树）；票据不存在时抛 {@link HarnessRuntimeNotFoundException}。
   *
   * <p>已冻结结果或被后续 Run supersede 的票据是历史事实，必须 no-op：否则同一个 Thread 上的后续 Run 会被旧票据误停。每次 CAS 重试都在 取得新
   * snapshot 后复核票据状态；后续 Run 改写 Thread 会让旧 version 的 stop 失败，从而回到复核。
   */
  public void stop(UUID ticketId) {
    ThreadJoin initial =
        findTicket(ticketId)
            .orElseThrow(
                () ->
                    new HarnessRuntimeNotFoundException(
                        "agent task " + ticketId + " does not exist"));
    if (isTerminal(initial)) {
      return;
    }
    stopTicketed(ticketId, initial.childThreadId());
  }

  private static boolean isTerminal(ThreadJoin ticket) {
    return ticket.matched() || ticket.supersededByInvocationId() != null;
  }

  /** 票据仍有效时才停止其 child；snapshot 与 stop 之间票据完成/被接管时，后续 Thread version 变化触发复核并 no-op。 */
  private void stopTicketed(UUID ticketId, UUID threadId) {
    HarnessRuntime runtime = requireRuntime();
    for (int attempt = 0; attempt < STOP_ATTEMPTS; attempt++) {
      ThreadSnapshot snapshot;
      try {
        snapshot = runtime.getThreadSnapshot(threadId);
      } catch (HarnessRuntimeNotFoundException gone) {
        return;
      }
      Optional<ThreadJoin> current = runtime.findJoin(ticketId);
      if (current.isEmpty() || isTerminal(current.get())) {
        return;
      }
      try {
        runtime.stop(new StopCommand(threadId, UUID.randomUUID(), snapshot.thread().version()));
        return;
      } catch (HarnessRuntimeConflictException conflict) {
        if (conflict.reason() != HarnessRuntimeConflictException.Reason.STALE_VERSION) {
          throw conflict;
        }
      } catch (HarnessRuntimeNotFoundException gone) {
        return;
      }
    }
    throw new IllegalStateException(
        "agent task stop did not converge after " + STOP_ATTEMPTS + " stale version retries");
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
