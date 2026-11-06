package fun.fengwk.kkstudio.platform.pluginhost.http;

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

import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginAuthCompleteRequestDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginAuthPrepareDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginAuthPrepareRequestDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConfigurationUpdateRequestDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConnectionCheckDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConnectionCheckRequestDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginDTO;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginManagementService;

import java.util.List;

/**
 * 构建期 Plugin 管理面 REST 控制器。
 *
 * <p>安全边界：只路由到已安装 Plugin，未安装 id 返回 not found；认证与配置响应强制 {@code Cache-Control: no-store}，避免敏感 URL
 * 与凭据投影进入 HTTP 缓存。秘密补丁的值只在本次请求内存在，绝不回显。
 */
@AllArgsConstructor
@RequestMapping("/api/platform/plugins")
@RestController
public class PluginController {

  private static final String NO_STORE = "no-store";

  private final PluginManagementService pluginManagementService;

  @GetMapping
  public List<PluginDTO> listPlugins() {
    return pluginManagementService.listPlugins();
  }

  @GetMapping("/{pluginId}")
  public PluginDTO getPlugin(@PathVariable("pluginId") String pluginId) {
    return pluginManagementService.getPlugin(pluginId);
  }

  /**
   * 保存普通配置与秘密补丁：期望版本不可省略，冲突返回 409。
   *
   * <p>本节点应用本次保存失败时返回 {@code 202 Accepted}，已应用时返回 {@code 200 OK}；两者都强制 {@code Cache-Control:
   * no-store}。
   */
  @PutMapping("/{pluginId}/configuration")
  public ResponseEntity<PluginDTO> updateConfiguration(
      @PathVariable("pluginId") String pluginId,
      @RequestBody PluginConfigurationUpdateRequestDTO request) {
    PluginDTO plugin =
        pluginManagementService.updateConfiguration(
            pluginId,
            request.getExpectedVersion(),
            request.getValuesJson(),
            request.getSecretPatch());
    HttpStatus status = plugin.getApplicationError() == null ? HttpStatus.OK : HttpStatus.ACCEPTED;
    return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(plugin);
  }

  /**
   * 显式只读连接检查：期望版本不可省略，未安装返回 not found，尚未应用或版本不匹配返回 409，实例未声明能力返回 400。
   *
   * <p>响应只含类型化维度与本次已应用版本，强制 {@code Cache-Control: no-store}；绝不回显远端内容、URL 或凭据。
   */
  @PostMapping("/{pluginId}/check-connection")
  public ResponseEntity<PluginConnectionCheckDTO> checkConnection(
      @PathVariable("pluginId") String pluginId,
      @RequestBody PluginConnectionCheckRequestDTO request) {
    PluginConnectionCheckDTO check =
        pluginManagementService.checkConnection(pluginId, request.getExpectedVersion());
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(check);
  }

  @PostMapping("/{pluginId}/auth/prepare")
  public ResponseEntity<PluginAuthPrepareDTO> prepareAuth(
      @PathVariable("pluginId") String pluginId, @RequestBody PluginAuthPrepareRequestDTO request) {
    PluginAuthPrepareDTO prepared =
        pluginManagementService.prepareAuth(pluginId, request.getRegion());
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(prepared);
  }

  @PostMapping("/{pluginId}/auth/complete")
  public ResponseEntity<PluginDTO> completeAuth(
      @PathVariable("pluginId") String pluginId,
      @RequestBody PluginAuthCompleteRequestDTO request) {
    PluginDTO plugin =
        pluginManagementService.completeAuth(
            pluginId, request.getAuthAttemptId(), request.getCallbackUrl());
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(plugin);
  }

  @DeleteMapping("/{pluginId}/auth")
  public ResponseEntity<Void> disconnect(@PathVariable("pluginId") String pluginId) {
    pluginManagementService.disconnect(pluginId);
    return ResponseEntity.noContent().build();
  }
}
