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
import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationService;
import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationType;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillInventoryQueryService;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSourceService;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInventoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentOperationCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentOperationDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRegistrationTokenDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRotateTokenDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceUpdateDTO;

import java.util.List;
import java.util.UUID;

/**
 * 稳定 Environment Card、Skill 来源配置、持久化库存与异步管理操作 REST API。
 *
 * <p>{@code registrationToken} 仅出现在 create、显式只读 token 以及 rotate-token 响应中；三者均返回 {@code
 * Cache-Control: no-store}， 避免敏感凭据进入 HTTP 缓存。列表与普通详情永不返回 token。普通 Skill
 * 来源配置、持久化清单与异步管理操作端点保持常规缓存语义（不附加 no-store 头）。
 */
@AllArgsConstructor
@RequestMapping("/api/harness/environments")
@RestController
public class StudioEnvironmentController {

  private static final String NO_STORE = "no-store";

  private final EnvironmentService environmentService;
  private final EnvironmentSkillSourceService skillSourceService;
  private final EnvironmentSkillInventoryQueryService inventoryQueryService;
  private final EnvironmentOperationService operationService;

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

  // --- Skill sources ---

  @GetMapping("/{environmentId}/skill-sources")
  public Result<List<EnvironmentSkillSourceDTO>> listSkillSources(
      @PathVariable String environmentId) {
    return Results.ok(skillSourceService.list(parseEnvironmentId(environmentId)));
  }

  @GetMapping("/{environmentId}/skill-sources/{sourceId}")
  public Result<EnvironmentSkillSourceDTO> getSkillSource(
      @PathVariable String environmentId, @PathVariable String sourceId) {
    return Results.ok(
        skillSourceService.get(
            parseEnvironmentId(environmentId), parseCanonicalUuid(sourceId, "sourceId")));
  }

  @PostMapping("/{environmentId}/skill-sources")
  public Result<EnvironmentSkillSourceDTO> createSkillSource(
      @PathVariable String environmentId, @RequestBody EnvironmentSkillSourceCreateDTO request) {
    return Results.created(skillSourceService.create(parseEnvironmentId(environmentId), request));
  }

  @PutMapping("/{environmentId}/skill-sources/{sourceId}")
  public Result<EnvironmentSkillSourceDTO> updateSkillSource(
      @PathVariable String environmentId,
      @PathVariable String sourceId,
      @RequestBody EnvironmentSkillSourceUpdateDTO request) {
    return Results.ok(
        skillSourceService.update(
            parseEnvironmentId(environmentId), parseCanonicalUuid(sourceId, "sourceId"), request));
  }

  @DeleteMapping("/{environmentId}/skill-sources/{sourceId}")
  public Result<Void> deleteSkillSource(
      @PathVariable String environmentId,
      @PathVariable String sourceId,
      @RequestParam String expectedVersion) {
    skillSourceService.delete(
        parseEnvironmentId(environmentId),
        parseCanonicalUuid(sourceId, "sourceId"),
        expectedVersion);
    return Results.noContent();
  }

  // --- Persistent inventory ---

  @GetMapping("/{environmentId}/inventory")
  public Result<EnvironmentInventoryDTO> getInventory(@PathVariable String environmentId) {
    return Results.ok(inventoryQueryService.getInventory(parseEnvironmentId(environmentId)));
  }

  @GetMapping("/{environmentId}/inventory/skills")
  public Result<List<EnvironmentSkillDTO>> listInventorySkills(
      @PathVariable String environmentId,
      @RequestParam(defaultValue = "false") boolean usableOnly) {
    EnvironmentId id = parseEnvironmentId(environmentId);
    List<EnvironmentSkillDTO> skills =
        usableOnly
            ? inventoryQueryService.listUsableSkills(id)
            : inventoryQueryService.listSkills(id);
    return Results.ok(skills);
  }

  // --- Durable async management operations ---

  @PostMapping("/{environmentId}/skill-sources/{sourceId}/refresh")
  public Result<EnvironmentOperationDTO> refreshSkillSource(
      @PathVariable String environmentId,
      @PathVariable String sourceId,
      @RequestBody EnvironmentOperationCreateDTO request) {
    return Results.accepted(
        operationService.create(
            parseEnvironmentId(environmentId),
            parseCanonicalUuid(sourceId, "sourceId"),
            EnvironmentOperationType.SKILL_REFRESH,
            request));
  }

  @PostMapping("/{environmentId}/skill-sources/{sourceId}/install")
  public Result<EnvironmentOperationDTO> installSkillSource(
      @PathVariable String environmentId,
      @PathVariable String sourceId,
      @RequestBody EnvironmentOperationCreateDTO request) {
    return Results.accepted(
        operationService.create(
            parseEnvironmentId(environmentId),
            parseCanonicalUuid(sourceId, "sourceId"),
            EnvironmentOperationType.SKILL_INSTALL,
            request));
  }

  @PostMapping("/{environmentId}/skill-sources/{sourceId}/update")
  public Result<EnvironmentOperationDTO> updateSkillSource(
      @PathVariable String environmentId,
      @PathVariable String sourceId,
      @RequestBody EnvironmentOperationCreateDTO request) {
    return Results.accepted(
        operationService.create(
            parseEnvironmentId(environmentId),
            parseCanonicalUuid(sourceId, "sourceId"),
            EnvironmentOperationType.SKILL_UPDATE,
            request));
  }

  @GetMapping("/{environmentId}/operations")
  public Result<List<EnvironmentOperationDTO>> listOperations(
      @PathVariable String environmentId, @RequestParam(defaultValue = "50") int limit) {
    return Results.ok(operationService.list(parseEnvironmentId(environmentId), limit));
  }

  @GetMapping("/{environmentId}/operations/{operationId}")
  public Result<EnvironmentOperationDTO> getOperation(
      @PathVariable String environmentId, @PathVariable String operationId) {
    return Results.ok(
        operationService.get(
            parseEnvironmentId(environmentId), parseCanonicalUuid(operationId, "operationId")));
  }

  @PostMapping("/{environmentId}/operations/{operationId}/cancel")
  public Result<EnvironmentOperationDTO> cancelOperation(
      @PathVariable String environmentId, @PathVariable String operationId) {
    return Results.ok(
        operationService.cancel(
            parseEnvironmentId(environmentId), parseCanonicalUuid(operationId, "operationId")));
  }

  private static EnvironmentId parseEnvironmentId(String text) {
    try {
      return EnvironmentId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new AiValidationException("environmentId", "environmentId must be a canonical UUID");
    }
  }

  private static UUID parseCanonicalUuid(String value, String field) {
    if (value == null || value.length() != 36) {
      throw new AiValidationException(field, field + " must be a canonical UUID");
    }
    for (int i = 0; i < 36; i++) {
      char c = value.charAt(i);
      if (i == 8 || i == 13 || i == 18 || i == 23) {
        if (c != '-') {
          throw new AiValidationException(field, field + " must be a canonical UUID");
        }
      } else {
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
          throw new AiValidationException(field, field + " must be a canonical UUID");
        }
      }
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new AiValidationException(field, field + " must be a canonical UUID");
    }
  }

  /** 携带凭据的响应必须禁止任何中间缓存。 */
  private static <T> ResponseEntity<Result<T>> noStore(Result<T> body) {
    return ResponseEntity.status(body.getStatus())
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .body(body);
  }
}
