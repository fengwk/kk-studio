package fun.fengwk.kkstudio.web.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessAcceptedCommandsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandBatchDTO;
import fun.fengwk.kkstudio.web.harness.HarnessCommandBatchService;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapper;

import java.util.Objects;

/**
 * 既有 Thread 的通用命令写入口：携带 path 的目标 Thread、命令批次与可选的精确 cursor，不携带 owner。
 *
 * <p>单条纯 USER_MESSAGE 批次不要求 cursor，按当前邮箱尾部入队；SET_* / GOAL / 设置批次仍要求精确 cursor（缺失为 400， stale 为
 * 409）。服务端从 Thread 解析 Session，身份认证、资源授权与附件引用校验照常完成；Issue/Chat 等业务规则由对应工作流在自身边界显式编排，本入口不按 owner
 * 伪造或拒绝。
 */
@RestController
@RequestMapping("/api/threads")
public class StudioHarnessThreadCommandBatchController {

  private final HarnessCommandBatchService commandBatchService;

  public StudioHarnessThreadCommandBatchController(HarnessCommandBatchService commandBatchService) {
    this.commandBatchService = Objects.requireNonNull(commandBatchService, "commandBatchService");
  }

  /** 接受一个既有 Thread 的用户命令批；202 仅表示 durable acceptance 已提交。 */
  @PostMapping("/{threadId}/command-batches")
  public ApiResponse<HarnessAcceptedCommandsDTO> accept(
      @PathVariable String threadId, @RequestBody HarnessThreadCommandBatchDTO request) {
    return ApiResponse.accepted(
        RuntimeErrors.translate(
            () -> {
              AcceptCommandsCommand command =
                  HarnessRuntimeRequestMapper.toAcceptThreadCommandsCommand(threadId, request);
              return commandBatchService.acceptOnThread(command);
            }));
  }
}
