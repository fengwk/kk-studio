package fun.fengwk.kkstudio.platform.harness.http;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessAcceptedCommandsDTO;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.ResourceAccess;

import java.util.Objects;

/** 既有 Thread 命令批的应用编排：通过人工写入边界后交给接受服务，再回读权威快照。 */
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

  /** 接受既有 Thread 的 owner-free 续写；target 必须是执行根。 */
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
