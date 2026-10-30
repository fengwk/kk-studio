package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.EnvironmentInstallConfigs;
import fun.fengwk.kkstudio.platform.environment.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.update.EnvironmentUpdateService;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentEventDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallCodeDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallCodeRequestDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRotateTokenDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRuntimeStatusDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateStartDTO;
import fun.fengwk.kkstudio.web.environment.cluster.EnvironmentRuntimeOwnerClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 稳定 Environment Card REST API。
 *
 * <p>{@code registrationToken} 仅在 create、rotate-token 和经安装 code 授权的脚本响应中返回；这些响应均带 {@code
 * Cache-Control: no-store}，避免敏感凭据进入 HTTP 缓存。列表与普通详情永不返回 token。最近一次 READY 的宿主 metadata 直接随 Card
 * 返回，因此没有独立端点；最近 200 条以内的运维事件走只读 events 端点，Card 只投影最近一条 WARN/ERROR。当前 Daemon 运行版本与更新进度走 runtime
 * 端点，不在 Card 上保留历史更新任务。
 */
@AllArgsConstructor
@RequestMapping("/api/environments")
@RestController
public class StudioEnvironmentController {

  private static final String NO_STORE = "no-store";
  private static final MediaType PLAIN_TEXT =
      new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

  private final EnvironmentService environmentService;
  private final EnvironmentUpdateService environmentUpdateService;
  private final ClusterNodeRegistry clusterNodeRegistry;
  private final EnvironmentRuntimeOwnerClient runtimeOwnerClient;

  @GetMapping
  public Result<List<EnvironmentCardDTO>> listEnvironments() {
    return Results.ok(environmentService.list());
  }

  @GetMapping("/{environmentId}")
  public Result<EnvironmentCardDTO> getEnvironment(@PathVariable String environmentId) {
    return Results.ok(environmentService.get(parseEnvironmentId(environmentId)));
  }

  @PostMapping
  public ResponseEntity<Result<EnvironmentCardDTO>> createEnvironment(
      @RequestBody EnvironmentCreateDTO request) {
    return noStore(Results.created(environmentService.create(request)));
  }

  /** 按时间正序返回最近的事件窗口（最多 200 条）；管理面按需轮询，不引入实时协议。 */
  @GetMapping("/{environmentId}/events")
  public Result<List<EnvironmentEventDTO>> listEnvironmentEvents(
      @PathVariable String environmentId) {
    return Results.ok(environmentService.listEvents(parseEnvironmentId(environmentId)));
  }

  /**
   * 读取当前运行快照：owner 返回 revision/status；没有本代 READY 快照时返回明确的 unavailable 错误，绝不伪造 update=null。
   *
   * <p>非 owner 请求经内部 HMAC 单跳到 owner；不缓存正文，也不在 Server 持久化进度。
   */
  @GetMapping("/{environmentId}/runtime")
  public Result<EnvironmentRuntimeStatusDTO> getEnvironmentRuntime(
      @PathVariable String environmentId) {
    EnvironmentId id = parseEnvironmentId(environmentId);
    return Results.ok(runtimeStatus(id));
  }

  /**
   * 发起一次受管 Daemon 更新：目标版本由平台固定官方发布决定，请求体不接受版本或 URL。
   *
   * <p>返回当前运行快照；Server 不插入任务行。目标不可用、已有更新在途或存在在途调用/工作时以冲突拒绝。
   */
  @PostMapping("/{environmentId}/update")
  public Result<EnvironmentUpdateStartDTO> startEnvironmentUpdate(
      @PathVariable String environmentId) {
    EnvironmentId id = parseEnvironmentId(environmentId);
    return Results.ok(startUpdate(id));
  }

  /** 签发五分钟安装 code；校验当前版本和已保存设置，不写环境状态。 */
  @PostMapping("/{environmentId}/install-code")
  public ResponseEntity<Result<EnvironmentInstallCodeDTO>> issueInstallCode(
      @PathVariable String environmentId, @RequestBody EnvironmentInstallCodeRequestDTO request) {
    String expectedVersion = request != null ? request.getExpectedVersion() : null;
    return noStore(
        Results.ok(
            environmentService.issueInstallCode(
                parseEnvironmentId(environmentId), expectedVersion)));
  }

  /** 校验 code 后签发新凭证并返回完整安装脚本；code 随即失效。响应不经 Result 包装。 */
  @GetMapping(value = "/{environmentId}/install", produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> installationScript(
      @PathVariable String environmentId, @RequestParam String code) {
    return script(environmentService.installationScript(parseEnvironmentId(environmentId), code));
  }

  /** 按操作系统返回卸载脚本；不读取 token，也不改变 Environment。 */
  @GetMapping(value = "/uninstall/{operatingSystem}", produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> uninstallationScript(@PathVariable String operatingSystem) {
    return script(environmentService.uninstallationScript(operatingSystem));
  }

  @PostMapping("/{environmentId}/registration-token")
  public ResponseEntity<Result<EnvironmentCardDTO>> rotateToken(
      @PathVariable String environmentId, @RequestBody EnvironmentRotateTokenDTO request) {
    String expectedVersion = request != null ? request.getExpectedVersion() : null;
    return noStore(
        Results.ok(
            environmentService.rotateToken(parseEnvironmentId(environmentId), expectedVersion)));
  }

  @DeleteMapping("/{environmentId}")
  public Result<Void> deleteEnvironment(
      @PathVariable String environmentId, @RequestParam String expectedVersion) {
    environmentService.delete(parseEnvironmentId(environmentId), expectedVersion);
    return Results.noContent();
  }

  @PutMapping("/{environmentId}/install-config")
  public Result<EnvironmentCardDTO> updateInstallConfig(
      @PathVariable String environmentId, @RequestBody Map<String, Object> request) {
    return Results.ok(
        environmentService.updateInstallConfig(
            parseEnvironmentId(environmentId),
            EnvironmentInstallConfigs.parseUpdate(EnvironmentInstallConfigs.tree(request))));
  }

  private static EnvironmentId parseEnvironmentId(String text) {
    try {
      return EnvironmentId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new AiValidationException("environmentId", "environmentId must be a canonical UUID");
    }
  }

  /** owner 单跳：本节点为 READY owner 时直接读取本地缓存，否则经内部 HMAC 调 owner；无路由即明确 unavailable。 */
  private EnvironmentRuntimeStatusDTO runtimeStatus(EnvironmentId environmentId) {
    ClusterOwnerRoute route = requireOwnerRoute(environmentId);
    if (route.local()) {
      return environmentUpdateService.runtimeStatus(environmentId);
    }
    return runtimeOwnerClient.runtimeStatus(route, environmentId);
  }

  /** owner 单跳发起更新；只在 READY owner 上执行一次严格准入，不向其它节点扇出。 */
  private EnvironmentUpdateStartDTO startUpdate(EnvironmentId environmentId) {
    ClusterOwnerRoute route = requireOwnerRoute(environmentId);
    if (route.local()) {
      return environmentUpdateService.startUpdate(environmentId);
    }
    return runtimeOwnerClient.startUpdate(route, environmentId);
  }

  private ClusterOwnerRoute requireOwnerRoute(EnvironmentId environmentId) {
    return clusterNodeRegistry
        .route(environmentId)
        .orElseThrow(
            () ->
                new AiEnvironmentUnavailableException(
                    "environment has no current READY owner route"));
  }

  /** 脚本按原文字节返回，禁止缓存，并阻止浏览器按附件内容嗅探。 */
  private static ResponseEntity<String> script(String body) {
    return ResponseEntity.ok()
        .contentType(PLAIN_TEXT)
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .header("X-Content-Type-Options", "nosniff")
        .body(body);
  }

  /** 携带凭据的响应必须禁止任何中间缓存。 */
  private static <T> ResponseEntity<Result<T>> noStore(Result<T> body) {
    return ResponseEntity.status(body.getStatus())
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .body(body);
  }
}
