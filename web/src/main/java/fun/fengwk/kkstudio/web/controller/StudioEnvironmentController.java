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
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentInstallConfigs;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentEventDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallCodeDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallCodeRequestDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRegistrationTokenDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRotateTokenDTO;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 稳定 Environment Card REST API。
 *
 * <p>{@code registrationToken} 仅出现在 create、显式只读 token 以及 rotate-token 响应中；三者均返回 {@code
 * Cache-Control: no-store}， 避免敏感凭据进入 HTTP 缓存。列表与普通详情永不返回 token。最近一次 READY 的宿主 metadata 直接随 Card
 * 返回，因此没有独立端点；最近 200 条以内的运维事件走只读 events 端点，Card 只投影最近一条 WARN/ERROR。
 */
@AllArgsConstructor
@RequestMapping("/api/harness/environments")
@RestController
public class StudioEnvironmentController {

  private static final String NO_STORE = "no-store";
  private static final MediaType PLAIN_TEXT =
      new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

  private final EnvironmentService environmentService;

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

  /** 校验 code 后按稳定 Environment 身份返回完整安装脚本。响应不经 Result 包装。 */
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

  /** 幂等只读当前 registrationToken；不轮换、不改变 version/updateTime。 */
  @GetMapping("/{environmentId}/token")
  public ResponseEntity<Result<EnvironmentRegistrationTokenDTO>> getRegistrationToken(
      @PathVariable String environmentId) {
    return noStore(
        Results.ok(environmentService.getRegistrationToken(parseEnvironmentId(environmentId))));
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
