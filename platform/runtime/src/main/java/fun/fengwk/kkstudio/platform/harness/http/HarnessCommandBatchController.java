package fun.fengwk.kkstudio.platform.harness.http;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessAcceptedCommandsDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandBatchDTO;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;

import java.util.Objects;

/**
 * 产品公共命令写入口：本端点只服务直接持有 Session 的 Chat owner。
 *
 * <p>HTTP mapper 只产生产品用户 batch；owner 授权、附件物化和 Runtime 接受由共享应用服务在同一事务内完成。Issue+Agent 命令必须经 Issue
 * 业务工作流（Run/stage 门禁与身份校验都在那里），因此不允许借本公共端点以 ownerId 伪造命令；Canvas 不持有 Harness Session，没有 owner 形态。
 */
@RestController
@RequestMapping("/api/harness/command-batches")
public class HarnessCommandBatchController {

  private final HarnessCommandBatchService commandBatchService;

  public HarnessCommandBatchController(HarnessCommandBatchService commandBatchService) {
    this.commandBatchService = Objects.requireNonNull(commandBatchService, "commandBatchService");
  }

  /** 接受一个 owner-aware 用户命令批；202 仅表示 durable acceptance 已提交。 */
  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public HarnessAcceptedCommandsDTO accept(@RequestBody HarnessCommandBatchDTO request) {
    SessionOwner owner = HarnessRuntimeRequestMapper.toOwner(request.getOwner());
    AcceptCommandsCommand command = HarnessRuntimeRequestMapper.toAcceptCommandsCommand(request);
    return commandBatchService.accept(owner, command);
  }
}
