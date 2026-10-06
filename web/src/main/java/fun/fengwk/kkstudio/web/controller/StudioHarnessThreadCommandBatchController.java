package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessAcceptedCommandsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandBatchDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapper;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeResponseMapper;

import java.util.Objects;

/**
 * 既有 Thread 的通用命令写入口：只携带 path 的目标 Thread、精确 cursor 与有序命令，不携带 owner。
 *
 * <p>服务端从 Thread 解析 Session，身份认证、资源授权与附件引用校验照常完成；Issue/Chat 等业务规则由对应工作流在自身边界显式编排，本入口不按 owner 伪造或拒绝。
 */
@RestController
@RequestMapping("/api/harness/threads")
public class StudioHarnessThreadCommandBatchController {

  private final HarnessCommandAcceptanceOrchestrator acceptanceService;
  private final HarnessRuntime runtime;

  public StudioHarnessThreadCommandBatchController(
      HarnessCommandAcceptanceOrchestrator acceptanceService, HarnessRuntime runtime) {
    this.acceptanceService = Objects.requireNonNull(acceptanceService, "acceptanceService");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  /** 接受一个既有 Thread 的用户命令批；202 仅表示 durable acceptance 已提交。 */
  @PostMapping("/{threadId}/command-batches")
  public Result<HarnessAcceptedCommandsDTO> accept(
      @PathVariable String threadId, @RequestBody HarnessThreadCommandBatchDTO request) {
    return Results.accepted(
        StudioHarnessThreadController.withRuntimeTranslation(
            () -> {
              AcceptCommandsCommand command =
                  HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(threadId, request);
              if (command.target() instanceof AcceptCommandsTarget.Thread thread) {
                ManualThreadControlGuard.requireExecutionRoot(runtime, thread.threadId());
              }
              AcceptedCommands accepted = acceptanceService.acceptOnThread(command);
              ThreadSnapshot currentSnapshot = runtime.getThreadSnapshot(accepted.thread().id());
              return HarnessRuntimeResponseMapper.toAcceptedCommandsDto(accepted, currentSnapshot);
            }));
  }
}
