package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
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
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRegistrationTokenDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRotateTokenDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateDTO;

import java.util.List;

/**
 * 稳定 Environment Card 管理 REST API。
 *
 * <p>{@code registrationToken} 只出现在 create 响应与显式只读 token 端点；两者都返回 {@code Cache-Control: no-store}，
 * 避免凭据进入 HTTP 缓存。列表与普通详情永不返回 token。
 */
@AllArgsConstructor
@RequestMapping("/api/harness/environments")
@RestController
public class StudioEnvironmentController {

  private static final String NO_STORE = "no-store";

  private final EnvironmentService environmentService;

  @GetMapping
  public Result<List<EnvironmentCardDTO>> listEnvironments() {
    return Results.ok(environmentService.list());
  }

  @GetMapping("/{environmentId}")
  public Result<EnvironmentCardDTO> getEnvironment(@PathVariable String environmentId) {
    return Results.ok(environmentService.get(EnvironmentId.parse(environmentId)));
  }

  @PostMapping
  public ResponseEntity<Result<EnvironmentCardDTO>> createEnvironment(
      @RequestBody EnvironmentCreateDTO request) {
    return noStore(Results.created(environmentService.create(request)));
  }

  @PutMapping("/{environmentId}")
  public Result<EnvironmentCardDTO> updateEnvironment(
      @PathVariable String environmentId, @RequestBody EnvironmentUpdateDTO request) {
    String expectedVersion = request != null ? request.getExpectedVersion() : null;
    return Results.ok(
        environmentService.update(EnvironmentId.parse(environmentId), request, expectedVersion));
  }

  /** 幂等只读当前 registrationToken；不轮换、不改变 version/updateTime。 */
  @GetMapping("/{environmentId}/token")
  public ResponseEntity<Result<EnvironmentRegistrationTokenDTO>> getRegistrationToken(
      @PathVariable String environmentId) {
    return noStore(
        Results.ok(environmentService.getRegistrationToken(EnvironmentId.parse(environmentId))));
  }

  @PostMapping("/{environmentId}/registration-token")
  public ResponseEntity<Result<EnvironmentCardDTO>> rotateToken(
      @PathVariable String environmentId, @RequestBody EnvironmentRotateTokenDTO request) {
    String expectedVersion = request != null ? request.getExpectedVersion() : null;
    return noStore(
        Results.ok(
            environmentService.rotateToken(EnvironmentId.parse(environmentId), expectedVersion)));
  }

  @DeleteMapping("/{environmentId}")
  public Result<Void> deleteEnvironment(
      @PathVariable String environmentId, @RequestParam String expectedVersion) {
    environmentService.delete(EnvironmentId.parse(environmentId), expectedVersion);
    return Results.noContent();
  }

  /** 携带凭据的响应必须禁止任何中间缓存。 */
  private static <T> ResponseEntity<Result<T>> noStore(Result<T> body) {
    return ResponseEntity.status(body.getStatus())
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .body(body);
  }
}
