package fun.fengwk.kkstudio.platform.harness.http;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessAcceptedCommandsDTO;
import fun.fengwk.kkstudio.platform.orchestration.DirectCommandsNotAllowedException;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.ResourceAccess;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;

import java.util.Objects;

/**
 * 人工命令批的应用编排：分支创建与既有 Thread 续写都先过人工写入边界，再交给平台接受服务，最后回读权威快照。
 *
 * <p>owner 授权、附件物化与 Runtime 入队仍在 {@link HarnessCommandAcceptanceOrchestrator}
 * 的事务内完成；这里只编排人工控制面的前置判定与响应投影。
 */
@Service
public class HarnessCommandBatchService {

  private final HarnessCommandAcceptanceOrchestrator acceptanceService;
  private final HarnessRuntime runtime;
  private final ResourceAccess resourceAccess;

  public HarnessCommandBatchService(
      HarnessCommandAcceptanceOrchestrator acceptanceService,
      HarnessRuntime runtime,
      ResourceAccess resourceAccess) {
    this.acceptanceService = acceptanceService;
    this.runtime = runtime;
    this.resourceAccess = resourceAccess;
  }

  public HarnessAcceptedCommandsDTO accept(
      StudioPrincipal actor, SessionOwner owner, AcceptCommandsCommand command) {
    Objects.requireNonNull(actor, "actor");
    if (command.target() instanceof AcceptCommandsTarget.NewThread fork) {
      ManualThreadControlGuard.requireForkRootSession(runtime, fork.sessionId());
    } else if (command.target() instanceof AcceptCommandsTarget.NewForkedSession forkedSession) {
      ManualThreadControlGuard.requireForkRootSourceThread(runtime, forkedSession.sourceThreadId());
    }
    try {
      return toAcceptedDto(acceptanceService.accept(actor.userId(), owner, command));
    } catch (DirectCommandsNotAllowedException error) {
      throw DomainException.conflict("DIRECT_COMMANDS_NOT_ALLOWED", error.getMessage(), error);
    }
  }

  public HarnessAcceptedCommandsDTO acceptOnThread(
      StudioPrincipal actor, AcceptCommandsCommand command) {
    Objects.requireNonNull(actor, "actor");
    if (command.target() instanceof AcceptCommandsTarget.Thread thread) {
      resourceAccess.requireThread(actor, thread.threadId());
      ManualThreadControlGuard.requireExecutionRoot(runtime, thread.threadId());
    }
    return toAcceptedDto(acceptanceService.acceptOnThread(actor.userId(), command));
  }

  private HarnessAcceptedCommandsDTO toAcceptedDto(AcceptedCommands accepted) {
    ThreadSnapshot currentSnapshot = runtime.getThreadSnapshot(accepted.thread().id());
    return HarnessRuntimeResponseMapper.toAcceptedCommandsDto(accepted, currentSnapshot);
  }
}
