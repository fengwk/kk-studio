package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.core.studio.StudioCommandAcceptanceService;
import fun.fengwk.kkstudio.core.studio.StudioOwner;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessAcceptedCommandsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandBatchDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapper;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeResponseMapper;

import java.util.Objects;

/**
 * Chat/Canvas 共用的唯一产品命令写入口。
 *
 * <p>HTTP mapper 只产生产品用户 batch；owner 授权、附件物化和 Runtime 接受由共享应用服务在同一事务内完成。
 */
@RestController
@RequestMapping("/api/ai/runtime/command-batches")
public class StudioHarnessCommandBatchController {

  private final StudioCommandAcceptanceService acceptanceService;
  private final HarnessRuntime runtime;

  public StudioHarnessCommandBatchController(
      StudioCommandAcceptanceService acceptanceService, HarnessRuntime runtime) {
    this.acceptanceService = Objects.requireNonNull(acceptanceService, "acceptanceService");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  /** 接受一个 owner-aware 用户命令批；202 仅表示 durable acceptance 已提交。 */
  @PostMapping
  public Result<HarnessAcceptedCommandsDTO> accept(@RequestBody HarnessCommandBatchDTO request) {
    return Results.accepted(
        StudioHarnessThreadController.withRuntimeTranslation(
            () -> {
              StudioOwner owner = HarnessRuntimeRequestMapper.toOwner(request.getOwner());
              AcceptCommandsCommand command =
                  HarnessRuntimeRequestMapper.toAcceptCommandsCommand(request);
              AcceptedCommands accepted = acceptanceService.accept(owner, command);
              ThreadSnapshot currentSnapshot = runtime.getThreadSnapshot(accepted.thread().id());
              return HarnessRuntimeResponseMapper.toAcceptedCommandsDto(accepted, currentSnapshot);
            }));
  }
}
