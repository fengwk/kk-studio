package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessAcceptedCommandsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandBatchDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapper;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeResponseMapper;

import java.util.Objects;

/**
 * 产品公共命令写入口：本端点只服务直接持有 Session 的 Chat owner。
 *
 * <p>HTTP mapper 只产生产品用户 batch；owner 授权、附件物化和 Runtime 接受由共享应用服务在同一事务内完成。Issue+Agent 命令必须经 Issue
 * 业务工作流（Run/stage 门禁与身份校验都在那里），因此不允许借本公共端点以 ownerId 伪造命令；Canvas 不持有 Harness Session，没有 owner 形态。
 */
@RestController
@RequestMapping("/api/harness/command-batches")
public class StudioHarnessCommandBatchController {

  /** Issue+Agent 的命令由 Issue 工作流拥有，公共 batch 端点不提供该入口。 */
  private static final String ISSUE_AGENT_COMMANDS_OWNED_BY_ISSUE_WORKFLOW =
      "Issue agent commands must use the Issue business workflow";

  private final HarnessCommandAcceptanceOrchestrator acceptanceService;
  private final HarnessRuntime runtime;

  public StudioHarnessCommandBatchController(
      HarnessCommandAcceptanceOrchestrator acceptanceService, HarnessRuntime runtime) {
    this.acceptanceService = Objects.requireNonNull(acceptanceService, "acceptanceService");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  /** 接受一个 owner-aware 用户命令批；202 仅表示 durable acceptance 已提交。 */
  @PostMapping
  public Result<HarnessAcceptedCommandsDTO> accept(@RequestBody HarnessCommandBatchDTO request) {
    return Results.accepted(
        StudioHarnessThreadController.withRuntimeTranslation(
            () -> {
              OwnerRef owner = HarnessRuntimeRequestMapper.toOwner(request.getOwner());
              if (owner instanceof OwnerRef.IssueAgent) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT, ISSUE_AGENT_COMMANDS_OWNED_BY_ISSUE_WORKFLOW);
              }
              AcceptCommandsCommand command =
                  HarnessRuntimeRequestMapper.toAcceptCommandsCommand(request);
              if (command.target() instanceof AcceptCommandsTarget.NewThread fork) {
                ManualThreadControlGuard.requireForkRootSession(runtime, fork.sessionId());
              } else if (command.target()
                  instanceof AcceptCommandsTarget.NewForkedSession forkedSession) {
                ManualThreadControlGuard.requireForkRootSourceThread(
                    runtime, forkedSession.sourceThreadId());
              }
              AcceptedCommands accepted = acceptanceService.accept(owner, command);
              ThreadSnapshot currentSnapshot = runtime.getThreadSnapshot(accepted.thread().id());
              return HarnessRuntimeResponseMapper.toAcceptedCommandsDto(accepted, currentSnapshot);
            }));
  }
}
