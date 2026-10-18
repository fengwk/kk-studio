package fun.fengwk.kkstudio.web.plugin;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.platform.plugin.service.PluginManagementService;
import fun.fengwk.kkstudio.platform.plugin.service.PluginSecretPatch;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthCompleteRequestDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthPrepareDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthPrepareRequestDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginConfigurationUpdateRequestDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckRequestDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginSecretPatchDTO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构建期 Plugin 管理面 REST 控制器。
 *
 * <p>安全边界：只路由到已安装 Plugin，未安装 id 返回 not found；认证与配置响应强制 {@code Cache-Control: no-store}，避免敏感 URL
 * 与凭据投影进入 HTTP 缓存。期望坐标按仓库既有的 canonical 十进制字符串约定解析，不可解析即 400；任何冲突都是 409。秘密补丁的值只在本次请求内存在，绝不回显。
 */
@AllArgsConstructor
@RequestMapping("/api/plugins")
@RestController
public class StudioPluginController {

  private static final String NO_STORE = "no-store";
  private static final String RESOURCE = "plugin";

  private final PluginManagementService pluginManagementService;

  @GetMapping
  public Result<List<PluginDTO>> listPlugins() {
    return Results.ok(pluginManagementService.listPlugins());
  }

  @GetMapping("/{pluginId}")
  public Result<PluginDTO> getPlugin(@PathVariable("pluginId") String pluginId) {
    return Results.ok(pluginManagementService.getPlugin(pluginId));
  }

  /**
   * 保存普通配置与秘密补丁：期望 revision 不可省略，冲突返回 409。
   *
   * <p>本节点应用本次保存失败时返回 {@code 202 Accepted}（响应体携带 desired/applied revision 与应用错误），已应用时返回 {@code 200
   * OK}；两者都强制 {@code Cache-Control: no-store}。
   */
  @PutMapping("/{pluginId}/configuration")
  public ResponseEntity<Result<PluginDTO>> updateConfiguration(
      @PathVariable("pluginId") String pluginId,
      @RequestBody PluginConfigurationUpdateRequestDTO request) {
    long expectedRevision = parseCoordinate(request.getExpectedRevision(), "expectedRevision");
    PluginDTO pluginDTO =
        pluginManagementService.updateConfiguration(
            pluginId,
            expectedRevision,
            request.getValuesJson(),
            toSecretPatch(request.getSecretPatch()));
    HttpStatus status =
        pluginDTO.getApplicationError() == null ? HttpStatus.OK : HttpStatus.ACCEPTED;
    return ResponseEntity.status(status)
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .body(Results.ok(pluginDTO));
  }

  /**
   * 显式只读连接检查：期望坐标不可省略，未安装返回 not found，尚未应用或坐标不匹配返回 409，实例未声明能力返回 400。
   *
   * <p>响应只含类型化维度与本次已应用坐标，强制 {@code Cache-Control: no-store}；绝不回显远端内容、URL 或凭据。
   */
  @PostMapping("/{pluginId}/connection-check")
  public ResponseEntity<Result<PluginConnectionCheckDTO>> checkConnection(
      @PathVariable("pluginId") String pluginId,
      @RequestBody PluginConnectionCheckRequestDTO request) {
    long expectedRevision = parseCoordinate(request.getExpectedRevision(), "expectedRevision");
    PluginConnectionCheckDTO checkDTO =
        pluginManagementService.checkConnection(pluginId, expectedRevision);
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .body(Results.ok(checkDTO));
  }

  @PostMapping("/{pluginId}/auth/prepare")
  public ResponseEntity<Result<PluginAuthPrepareDTO>> prepareAuth(
      @PathVariable("pluginId") String pluginId, @RequestBody PluginAuthPrepareRequestDTO request) {
    PluginAuthPrepareDTO prepareDTO =
        pluginManagementService.prepareAuth(pluginId, request.getRegion());
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .body(Results.ok(prepareDTO));
  }

  @PostMapping("/{pluginId}/auth/complete")
  public ResponseEntity<Result<PluginDTO>> completeAuth(
      @PathVariable("pluginId") String pluginId,
      @RequestBody PluginAuthCompleteRequestDTO request) {
    PluginDTO pluginDTO =
        pluginManagementService.completeAuth(
            pluginId, request.getAuthAttemptId(), request.getCallbackUrl());
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .body(Results.ok(pluginDTO));
  }

  @DeleteMapping("/{pluginId}/auth")
  public Result<Void> disconnect(@PathVariable("pluginId") String pluginId) {
    pluginManagementService.disconnect(pluginId);
    return Results.noContent();
  }

  private static long parseCoordinate(String value, String field) {
    try {
      return CatalogVersions.parse(value, field);
    } catch (AiValidationException error) {
      throw new AiValidationException(
          RESOURCE, field + " must be a canonical non-negative decimal string");
    }
  }

  /** 把请求体的秘密补丁映射为领域操作：KEEP/CLEAR 禁止携带值，SET 必须有界且非遮罩；任何失败都收敛为固定校验失败，绝不回显值。 */
  private static Map<String, PluginSecretPatch> toSecretPatch(
      Map<String, PluginSecretPatchDTO> secretPatch) {
    Map<String, PluginSecretPatch> mapped = new LinkedHashMap<>();
    if (secretPatch == null) {
      return mapped;
    }
    for (Map.Entry<String, PluginSecretPatchDTO> entry : secretPatch.entrySet()) {
      String field = entry.getKey();
      if (field == null || field.isBlank() || !field.equals(field.strip())) {
        throw new AiValidationException(RESOURCE, "secretPatch must use canonical field names");
      }
      PluginSecretPatchDTO dto = entry.getValue();
      if (dto == null || dto.getOperation() == null) {
        throw new AiValidationException(RESOURCE, "secretPatch must declare an operation");
      }
      String operation = dto.getOperation();
      if (("KEEP".equals(operation) || "CLEAR".equals(operation)) && dto.getValue() != null) {
        throw new AiValidationException(RESOURCE, operation + " must not carry a value");
      }
      try {
        mapped.put(
            field,
            switch (operation) {
              case "KEEP" -> PluginSecretPatch.keep();
              case "SET" -> PluginSecretPatch.set(dto.getValue());
              case "CLEAR" -> PluginSecretPatch.clear();
              default -> throw new AiValidationException(
                  RESOURCE, "secretPatch operation must be KEEP, SET or CLEAR");
            });
      } catch (IllegalArgumentException error) {
        // 固定文案：秘密值绝不进入异常消息、日志或响应。
        throw new AiValidationException(RESOURCE, "secretPatch value is invalid");
      }
    }
    return mapped;
  }
}
