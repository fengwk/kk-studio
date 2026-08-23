package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.ai.environment.service.LiveEnvironmentQueryService;
import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentDTO;

import java.util.List;

/**
 * 只读的 live Environment registry API。
 *
 * <p>Environment 仅存在于服务器内存中，没有 create/update/delete 表面。Daemon 连接通过 WebSocket gateway 填充
 * registry。映射保持在 Core 中，因此本 controller 只依赖应用 DTO。
 */
@AllArgsConstructor
@RequestMapping("/api/ai/environment")
@RestController
public class StudioToolEnvironmentController {

  private final LiveEnvironmentQueryService liveEnvironmentQueryService;

  @GetMapping
  public Result<List<LiveEnvironmentDTO>> listEnvironments() {
    return Results.ok(liveEnvironmentQueryService.listEnvironments());
  }
}
