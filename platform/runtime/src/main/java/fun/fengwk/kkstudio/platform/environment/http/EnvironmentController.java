package fun.fengwk.kkstudio.platform.environment.http;

import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.environment.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentInstallCodeDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentInstallCodeRequestDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentRotateTokenDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentUpdateDTO;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Environment 卡片 REST API：CRUD、安装 code 与按卡片脚本、token 轮换。
 *
 * <p>{@code registrationToken} 仅在 create、rotate-token 和经安装 code 授权的脚本响应中返回；这些响应均带 {@code
 * Cache-Control: no-store}，避免敏感凭据进入 HTTP 缓存。最近一次 READY 的宿主 metadata 直接随 Environment 返回，因此没有独立端点；最近
 * 200 条以内的运维事件走只读 events 端点。当前 Daemon 运行版本与更新进度由集群 owner 单跳的 runtime 端点提供。
 */
@AllArgsConstructor
@RestController
public class EnvironmentController {

  private static final String NO_STORE = "no-store";
  private static final String RESOURCE = "environment";
  private static final MediaType PLAIN_TEXT =
      new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

  private final EnvironmentService environmentService;

  @GetMapping("/api/platform/environments")
  public List<EnvironmentDTO> listEnvironments() {
    return environmentService.list();
  }

  @GetMapping("/api/platform/environments/{environmentId}")
  public EnvironmentDTO getEnvironment(@PathVariable UUID environmentId) {
    return environmentService.get(EnvironmentId.of(environmentId));
  }

  @PostMapping("/api/platform/environments")
  public ResponseEntity<EnvironmentDTO> createEnvironment(
      @RequestBody EnvironmentCreateDTO request) {
    return noStore(HttpStatus.CREATED, environmentService.create(request));
  }

  /** 整体替换 note 与 daemonConfig；name 不可变，期望版本缺失或冲突时不写入。 */
  @PutMapping("/api/platform/environments/{environmentId}")
  public EnvironmentDTO updateEnvironment(
      @PathVariable UUID environmentId, @RequestBody EnvironmentUpdateDTO request) {
    return environmentService.update(EnvironmentId.of(environmentId), request);
  }

  /** 按时间正序返回最近的事件窗口（最多 200 条）；管理面按需轮询，不引入实时协议。 */
  @GetMapping("/api/platform/environments/{environmentId}/events")
  public List<EnvironmentEventDTO> listEnvironmentEvents(@PathVariable UUID environmentId) {
    return environmentService.listEvents(EnvironmentId.of(environmentId));
  }

  /** 签发五分钟安装 code；校验当前版本，不写环境状态。 */
  @PostMapping("/api/platform/environments/{environmentId}/install-codes")
  public ResponseEntity<EnvironmentInstallCodeDTO> issueInstallCode(
      @PathVariable UUID environmentId, @RequestBody EnvironmentInstallCodeRequestDTO request) {
    return noStore(
        HttpStatus.OK,
        environmentService.issueInstallCode(
            EnvironmentId.of(environmentId), requireExpectedVersion(request.getExpectedVersion())));
  }

  /** 校验 code 后签发新凭证并返回完整安装脚本；目标 OS 只属于本次安装命令，code 随即失效。 */
  @GetMapping(
      value = "/api/platform/environments/{environmentId}/install-script",
      produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> installationScript(
      @PathVariable UUID environmentId,
      @RequestParam String code,
      @RequestParam DaemonOperatingSystem operatingSystem) {
    return script(
        environmentService.installationScript(
            EnvironmentId.of(environmentId), code, operatingSystem));
  }

  /** 按卡片 UUID 与目标 OS 返回卸载脚本；不读取 token，也不改变 Environment。 */
  @GetMapping(
      value = "/api/platform/environments/{environmentId}/uninstall-script",
      produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> uninstallationScript(
      @PathVariable UUID environmentId,
      @RequestParam DaemonOperatingSystem operatingSystem) {
    return script(
        environmentService.uninstallationScript(
            EnvironmentId.of(environmentId), operatingSystem));
  }

  @PostMapping("/api/platform/environments/{environmentId}/rotate-token")
  public ResponseEntity<EnvironmentDTO> rotateToken(
      @PathVariable UUID environmentId, @RequestBody EnvironmentRotateTokenDTO request) {
    return noStore(
        HttpStatus.OK,
        environmentService.rotateToken(
            EnvironmentId.of(environmentId), requireExpectedVersion(request.getExpectedVersion())));
  }

  @DeleteMapping("/api/platform/environments/{environmentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteEnvironment(
      @PathVariable UUID environmentId, @RequestParam long expectedVersion) {
    environmentService.delete(EnvironmentId.of(environmentId), expectedVersion);
  }

  /** 脚本按原文字节返回，禁止缓存，并阻止浏览器按附件内容嗅探。 */
  private static ResponseEntity<String> script(String body) {
    return ResponseEntity.ok()
        .contentType(PLAIN_TEXT)
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .header("X-Content-Type-Options", "nosniff")
        .body(body);
  }

  /** 期望版本是必填请求字段；缺失在 HTTP 边界拒绝，绝不以缺省 0 参与 CAS。 */
  private static long requireExpectedVersion(Long expectedVersion) {
    if (expectedVersion == null) {
      throw DomainException.validation(RESOURCE, "expectedVersion is required");
    }
    return expectedVersion;
  }

  /** 携带凭据的响应必须禁止任何中间缓存。 */
  private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
    return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(body);
  }
}
