package fun.fengwk.kkstudio.web.upgrade;

import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.service.PluginConfigurationCommitService;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 操作人显式提供的静态密钥材料：只接受已安装 Plugin 的真实 {@link PluginAuthSpec.StaticKey} 声明与声明内的真实字段名，并复用生产 {@link
 * PluginConfigurationCommitService#prepareInitialStaticMaterial} 构造材料，因此不会伪造 has-key 布尔、未知字段或空凭据。
 *
 * <p>它同时保存「已录入字段名集合」这一非秘密事实（就是通过生产校验的输入键），配置历史据此写出真实的已配置字段；只有真正录入主秘密字段（{@code secretField}）时才视为
 * has-required-secret，仅录入附加秘密字段（如 {@code instanceId}）既不会谎称主秘密已给，也不会放行「启用但缺主秘密」的前置。
 *
 * <p>没有提供材料的插件完全省略，不创建空凭据；DeepLink / None 插件永远没有 configured secret fields。整个类不持有连接、不写库。
 *
 * <p>包内可见（package-private）：它只是离线升级包内部的静态材料前置，不是平台 API。
 */
final class LegacyStaticCredentials {

  /** 每个 Plugin 通过生产校验的输入字段名（非秘密）；只在那里出现一次，随后用于配置历史与 has-required-secret 判定。 */
  private final Map<String, Set<String>> providedFields;

  private final Map<String, PluginCredentialMaterial> materials;

  private LegacyStaticCredentials(
      Map<String, Set<String>> providedFields, Map<String, PluginCredentialMaterial> materials) {
    this.providedFields = Collections.unmodifiableMap(providedFields);
    this.materials = Collections.unmodifiableMap(materials);
  }

  /**
   * 校验并构造全部静态材料。
   *
   * @throws LegacyDatabaseUpgradeException 提供了未知 Plugin、非 StaticKey 声明、未知字段名、空 map 或空秘密时（固定安全原因）
   */
  static LegacyStaticCredentials validate(
      StudioPluginRegistry registry, Map<String, Map<String, String>> staticSecretsByPlugin) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(staticSecretsByPlugin, "staticSecretsByPlugin");
    Map<String, Set<String>> providedFields = new LinkedHashMap<>();
    Map<String, PluginCredentialMaterial> materials = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, String>> entry : staticSecretsByPlugin.entrySet()) {
      String pluginId = entry.getKey();
      Map<String, String> secrets = entry.getValue();
      if (pluginId == null || secrets == null || secrets.isEmpty()) {
        throw reject("static credential material must name a plugin and at least one secret value");
      }
      StudioPlugin plugin =
          registry
              .find(pluginId)
              .orElseThrow(
                  () -> reject("static credential material names a plugin that is not installed"));
      if (!(plugin.authenticationSpec() instanceof PluginAuthSpec.StaticKey)) {
        throw reject("static credential material requires a static key plugin");
      }
      try {
        materials.put(
            pluginId,
            PluginConfigurationCommitService.prepareInitialStaticMaterial(plugin, secrets));
      } catch (RuntimeException error) {
        throw reject("static credential material does not match the plugin declaration");
      }
      providedFields.put(pluginId, Set.copyOf(new LinkedHashSet<>(secrets.keySet())));
    }
    return new LegacyStaticCredentials(providedFields, materials);
  }

  /** 该 Plugin 是否已真正录入其 StaticKey 声明的主秘密字段；只录入了附加秘密字段时为 false。 */
  boolean hasRequiredSecret(StudioPluginRegistry registry, String pluginId) {
    Set<String> fields = providedFields.get(pluginId);
    if (fields == null) {
      return false;
    }
    return registry
        .find(pluginId)
        .map(StudioPlugin::authenticationSpec)
        .filter(PluginAuthSpec.StaticKey.class::isInstance)
        .map(PluginAuthSpec.StaticKey.class::cast)
        .map(staticKey -> fields.contains(staticKey.secretField()))
        .orElse(false);
  }

  /** 覆盖真实安装目录的 configured secret fields：StaticKey 插件取已验证材料对应的真实字段名集合（稳定排序），其余插件恒为空。 */
  Map<String, List<String>> configuredSecretFields(StudioPluginRegistry registry) {
    Map<String, List<String>> fields = new LinkedHashMap<>();
    for (StudioPlugin plugin : registry.plugins()) {
      String pluginId = plugin.descriptor().pluginId();
      Set<String> provided = providedFields.get(pluginId);
      fields.put(pluginId, provided == null ? List.of() : List.copyOf(new TreeSet<>(provided)));
    }
    return Collections.unmodifiableMap(fields);
  }

  Map<String, PluginCredentialMaterial> materials() {
    return materials;
  }

  private static LegacyDatabaseUpgradeException reject(String reason) {
    return new LegacyDatabaseUpgradeException(
        LegacyDatabaseUpgradeException.Stage.CREDENTIAL, reason);
  }
}
