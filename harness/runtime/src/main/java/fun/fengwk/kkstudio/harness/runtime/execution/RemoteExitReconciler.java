package fun.fengwk.kkstudio.harness.runtime.execution;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 跨实例远端退出对账器。
 *
 * <p>当目标 Environment 达到 READY 状态时，异步读取该 Environment 下全部尚未确认的 REMOTE 退出事实。 对属于非 RUNNING 工具的事实（包括
 * Tool 行已删除）通过 {@link EnvironmentCapabilityTransport#attach} 挂接仅确认退出的监听器； 属于 RUNNING 工具的事实全部跳过，留给
 * ToolProcessor 挂接。
 */
@Slf4j
public final class RemoteExitReconciler {

  private final HarnessStore store;
  private final EnvironmentCapabilityTransport transport;
  private final ExecutionExitConfirmer exitConfirmer;
  private final Executor executor;

  public RemoteExitReconciler(
      HarnessStore store,
      EnvironmentCapabilityTransport transport,
      ExecutionExitConfirmer exitConfirmer,
      Executor executor) {
    this.store = Objects.requireNonNull(store, "store");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.exitConfirmer = Objects.requireNonNull(exitConfirmer, "exitConfirmer");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  /**
   * Environment READY 事件回调：异步排期远端退出事实对账。
   *
   * <p>立即返回，不在调用线程上访问数据库或传输。
   */
  public void onEnvironmentReady(EnvironmentId environmentId) {
    if (environmentId == null) {
      return;
    }
    try {
      executor.execute(() -> reconcile(environmentId));
    } catch (RejectedExecutionException error) {
      log.warn("remote exit reconciliation rejected: environmentId={}", environmentId, error);
    }
  }

  private void reconcile(EnvironmentId environmentId) {
    List<ExecutionExitFact> candidates;
    try {
      candidates =
          store.transaction(
              tx -> {
                List<ExecutionExitFact> unconfirmed =
                    tx.exits().loadUnconfirmedRemote(environmentId);
                if (unconfirmed.isEmpty()) {
                  return List.of();
                }
                List<ExecutionExitFact> attachable = new ArrayList<>();
                Map<UUID, Boolean> runningByInvocationId = new HashMap<>();
                for (ExecutionExitFact fact : unconfirmed) {
                  boolean isRunning =
                      runningByInvocationId.computeIfAbsent(
                          fact.invocationId(),
                          id ->
                              tx.tools()
                                  .find(id)
                                  .map(tool -> tool.status() == ToolInvocationStatus.RUNNING)
                                  .orElse(false));
                  if (!isRunning) {
                    attachable.add(fact);
                  }
                }
                return List.copyOf(attachable);
              });
    } catch (RuntimeException error) {
      log.warn("failed to query unconfirmed remote exits: environmentId={}", environmentId, error);
      return;
    }

    for (ExecutionExitFact fact : candidates) {
      try {
        transport.attach(environmentId, fact.executionId(), createExitOnlyListener(fact));
      } catch (IllegalArgumentException error) {
        log.debug(
            "execution already registered on this node, skipping: executionId={}",
            fact.executionId(),
            error);
      } catch (EnvironmentCapabilityUnavailableException error) {
        log.info(
            "environment capability unavailable, stopping reconcile round: environmentId={}",
            environmentId,
            error);
        break;
      }
    }
  }

  private EnvironmentCapabilityExecutionListener createExitOnlyListener(ExecutionExitFact fact) {
    return new EnvironmentCapabilityExecutionListener() {
      @Override
      public void onComplete(EnvironmentCapabilityResult result) {
        // 忽略终态结果
      }

      @Override
      public void onError(Throwable error) {
        // 忽略异常（包括 NOT_FOUND），事实保持 UNCONFIRMED
      }

      @Override
      public void onExitConfirmed() {
        exitConfirmer.confirm(fact, ExecutionExitState.REMOTE_ACKNOWLEDGED);
      }
    };
  }
}
