package fun.fengwk.kkstudio.web.environment;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentRuntimeStatusDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentUpdateStartDTO;

import java.util.UUID;

/**
 * Environment 运行状态与受管更新的公开入口：按当前 READY owner 在本地读取/执行，或经内部 HMAC 单跳到 owner。
 *
 * <p>路由随 Environment 资源位于 {@code /api/platform/environments}；集群传输与 owner 客户端留在 web 组合层。
 */
@AllArgsConstructor
@RestController
public class EnvironmentRuntimeController {

  private final EnvironmentRuntimeRoutingService environmentRuntimeRoutingService;

  /** 读取当前 READY owner 的运行快照；没有本代快照时返回明确的 unavailable 错误，绝不伪造 update=null。 */
  @GetMapping("/api/platform/environments/{environmentId}/runtime")
  public EnvironmentRuntimeStatusDTO getEnvironmentRuntime(@PathVariable UUID environmentId) {
    return environmentRuntimeRoutingService.runtimeStatus(EnvironmentId.of(environmentId));
  }

  /**
   * 发起一次受管 Daemon 更新：目标版本由平台固定官方发布决定，请求体不接受版本或 URL。
   *
   * <p>返回当前运行快照；Server 不插入任务行。目标不可用、已有更新在途或存在在途调用/工作时以冲突拒绝。
   */
  @PostMapping("/api/platform/environments/{environmentId}/update-daemon")
  public EnvironmentUpdateStartDTO startEnvironmentUpdate(@PathVariable UUID environmentId) {
    return environmentRuntimeRoutingService.startUpdate(EnvironmentId.of(environmentId));
  }
}
