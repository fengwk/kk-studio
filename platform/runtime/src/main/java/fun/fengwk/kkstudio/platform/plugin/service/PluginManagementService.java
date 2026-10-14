package fun.fengwk.kkstudio.platform.plugin.service;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthHandler;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialProjection;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthKindDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthPrepareDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginDTO;

import java.util.List;
import java.util.Objects;

/**
 * Plugin 管理面用例：列出/读取安全投影，以及 {@code DEEP_LINK} 认证的 prepare / complete / disconnect。
 *
 * <p>它只路由到已安装 Plugin：未安装的 {@code pluginId} 返回 not found，绝不加载或合成 Plugin。认证协议是封闭的，请求与响应都只有共享 DTO
 * 声明的字段；回调地址只在本次调用内短暂存在，既不落库也不进日志、响应或异常消息。
 */
@Slf4j
public final class PluginManagementService {

  private static final String RESOURCE = "plugin";

  private final StudioPluginRegistry registry;
  private final PluginCredentialStore credentialStore;

  public PluginManagementService(
      StudioPluginRegistry registry, PluginCredentialStore credentialStore) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
  }

  /** 已安装 Plugin 的安全投影，顺序跟随启动期冻结的安装目录。 */
  public List<PluginDTO> listPlugins() {
    return registry.plugins().stream().map(this::describe).toList();
  }

  /** 单个已安装 Plugin 的安全投影；未安装 id 返回 not found。 */
  public PluginDTO getPlugin(String pluginId) {
    StudioPlugin plugin = requirePlugin(pluginId);
    return describe(plugin);
  }

  /** 选择固定 region 并返回该 region 的官方登录地址。 */
  public PluginAuthPrepareDTO prepareAuth(String pluginId, String region) {
    StudioPlugin plugin = requirePlugin(pluginId);
    PluginAuthHandler handler = requireAuthHandler(plugin);
    if (!plugin.descriptor().acceptsRegion(region)) {
      throw new AiValidationException(RESOURCE, "region must be one of the declared candidates");
    }
    String loginUrl;
    try {
      loginUrl = handler.loginUrl(region);
    } catch (RuntimeException error) {
      throw new AiValidationException(RESOURCE, "plugin rejected the selected region", error);
    }
    if (loginUrl == null || loginUrl.isBlank()) {
      throw new AiValidationException(RESOURCE, "plugin did not provide a login url");
    }
    PluginAuthPrepareDTO response = new PluginAuthPrepareDTO();
    response.setLoginUrl(loginUrl);
    return response;
  }

  /**
   * 校验回调、把 opaque 凭据材料加密落库，并返回新的安全投影。
   *
   * <p>Plugin 抛出的任何异常都收敛为一次校验失败：回调原文绝不进入异常消息或日志。
   */
  public PluginDTO completeAuth(String pluginId, String callbackUrl) {
    StudioPlugin plugin = requirePlugin(pluginId);
    PluginAuthHandler handler = requireAuthHandler(plugin);
    if (callbackUrl == null || callbackUrl.isBlank()) {
      throw new AiValidationException(RESOURCE, "callbackUrl must not be blank");
    }
    PluginCredentialMaterial material;
    try {
      material = handler.complete(callbackUrl);
    } catch (RuntimeException error) {
      log.debug(
          "Plugin {} rejected the auth callback: {}", pluginId, error.getClass().getSimpleName());
      throw new AiValidationException(RESOURCE, "plugin rejected the auth callback", error);
    }
    if (material == null || !plugin.descriptor().acceptsRegion(material.region())) {
      throw new AiValidationException(
          RESOURCE, "plugin returned a credential outside its declared regions");
    }
    credentialStore.save(pluginId, material);
    return describe(plugin);
  }

  /** 断连：删除凭据行；未连接时同样成功返回（幂等）。 */
  public void disconnect(String pluginId) {
    requirePlugin(pluginId);
    credentialStore.delete(pluginId);
  }

  private StudioPlugin requirePlugin(String pluginId) {
    return registry.find(pluginId).orElseThrow(() -> new AiResourceNotFoundException(RESOURCE));
  }

  private static PluginAuthHandler requireAuthHandler(StudioPlugin plugin) {
    return plugin
        .authHandler()
        .orElseThrow(
            () ->
                new AiValidationException(
                    RESOURCE, "plugin does not provide interactive authentication"));
  }

  private PluginDTO describe(StudioPlugin plugin) {
    PluginDescriptor descriptor = plugin.descriptor();
    PluginCredentialProjection projection = credentialStore.projection(descriptor.pluginId());
    PluginDTO dto = new PluginDTO();
    dto.setPluginId(descriptor.pluginId());
    dto.setName(descriptor.name());
    dto.setVersion(descriptor.version());
    dto.setAuthKind(
        descriptor.supportsAuthentication()
            ? new PluginAuthKindDTO.DeepLink(descriptor.regions())
            : null);
    dto.setStatus(projection.status().name());
    dto.setRegion(projection.region());
    dto.setExpiresAt(projection.expiresAt());
    dto.setNextRefreshAt(projection.nextRefreshAt());
    dto.setLastRefreshedAt(projection.lastRefreshedAt());
    dto.setLastRefreshError(projection.lastRefreshError());
    return dto;
  }
}
