package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeAction;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeCommand;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * IDE 命令的唯一发送与等待路径：把传输失败、超时与中断统一翻译为「结果未知」，绝不重发。
 *
 * <p>普通关闭（{@code discard=false}）不受 {@code commandTimeout} 约束，由 Daemon 决定何时结束；其余命令按超时放弃观察。
 */
final class IdeCommandCaller {

  private final IdeCommandTransport transport;
  private final Duration commandTimeout;

  IdeCommandCaller(IdeCommandTransport transport, Duration commandTimeout) {
    this.transport = Objects.requireNonNull(transport, "transport");
    this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
    if (commandTimeout.isZero() || commandTimeout.isNegative()) {
      throw new IllegalArgumentException("commandTimeout must be positive");
    }
  }

  /** 发送并等待回执；发送不确定、超时与中断都以 {@link IdeOutcomeUnknownException} 失败。 */
  IdeCall call(EnvironmentId environmentId, IdeCommand command) {
    IdeRequest request;
    try {
      request = transport.requestIde(environmentId, command);
    } catch (EnvironmentCapabilitySendUncertainException error) {
      throw new IdeOutcomeUnknownException("environment IDE command outcome is uncertain", error);
    } catch (EnvironmentCapabilityUnavailableException | EnvironmentCapabilityBusyException error) {
      throw DomainException.unavailable(
          "DAEMON_NOT_READY", "environment daemon is not ready", error);
    }
    IdeResult reply;
    try {
      reply =
          command.getAction() == IdeAction.CLOSE_VIEW
                  && command.hasDiscard()
                  && !command.getDiscard()
              ? request.reply().get()
              : request.reply().get(commandTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException error) {
      transport.abandonIde(request.invocationId());
      throw new IdeOutcomeUnknownException("environment IDE command timed out", error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      transport.abandonIde(request.invocationId());
      throw new IdeOutcomeUnknownException("environment IDE command was interrupted", error);
    } catch (ExecutionException error) {
      throw new IdeOutcomeUnknownException(
          "environment IDE command failed without a confirmed outcome", error.getCause());
    }
    return new IdeCall(reply, request.identity());
  }

  /** 结果未知直接作为结构化失败返回，供不允许对账的命令使用。 */
  IdeCall callConfirmed(EnvironmentId environmentId, IdeCommand command) {
    try {
      return call(environmentId, command);
    } catch (IdeOutcomeUnknownException error) {
      throw IdeProtocol.outcomeUnknown(error.getMessage(), error);
    }
  }

  /** 命令可能已到达 Daemon 但未收到确定回执；只有 OPEN 允许按原 claim 纯读对账。 */
  static final class IdeOutcomeUnknownException extends RuntimeException {

    private IdeOutcomeUnknownException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
