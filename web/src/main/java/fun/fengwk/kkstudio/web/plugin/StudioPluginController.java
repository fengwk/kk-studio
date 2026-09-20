package fun.fengwk.kkstudio.web.plugin;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.plugin.service.PluginManagementService;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthCompleteRequestDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthPrepareDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthPrepareRequestDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginDTO;

import java.util.List;

/**
 * 构建期 Plugin 管理面 REST 控制器。
 *
 * <p>安全边界：只路由到已安装 Plugin，未安装 id 返回 not found；认证响应强制 {@code Cache-Control: no-store}，避免敏感 URL
 * 与凭据投影进入 HTTP 缓存。
 */
@AllArgsConstructor
@RequestMapping("/api/plugins")
@RestController
public class StudioPluginController {

  private static final String NO_STORE = "no-store";

  private final PluginManagementService pluginManagementService;

  @GetMapping
  public Result<List<PluginDTO>> listPlugins() {
    return Results.ok(pluginManagementService.listPlugins());
  }

  @GetMapping("/{pluginId}")
  public Result<PluginDTO> getPlugin(@PathVariable("pluginId") String pluginId) {
    return Results.ok(pluginManagementService.getPlugin(pluginId));
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
    PluginDTO pluginDTO = pluginManagementService.completeAuth(pluginId, request.getCallbackUrl());
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
        .body(Results.ok(pluginDTO));
  }

  @DeleteMapping("/{pluginId}/auth")
  public Result<Void> disconnect(@PathVariable("pluginId") String pluginId) {
    pluginManagementService.disconnect(pluginId);
    return Results.noContent();
  }
}
