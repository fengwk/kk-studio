package fun.fengwk.kkstudio.platform.configsync;

import static fun.fengwk.kkstudio.platform.configsync.ConfigSyncEntrySupport.RESOURCE;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncKind;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncSkipped;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsCodec;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSectionsDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * settings 段落的读取与解析期契约检查。
 *
 * <p>settings 是单例段落而不是条目列表：未知字段逐项上报为 skip，而剩余结构必须在解析期满足当前完整六节契约，避免对必然无效的 文件做外部 Git/MCP 准备。
 */
final class ConfigSyncSettingsEntries {

  static final String SETTINGS_ITEM_NAME = "settings";

  private final ConfigSyncYaml yaml;
  private final SystemSettingsCodec systemSettingsCodec;

  ConfigSyncSettingsEntries(ConfigSyncYaml yaml, SystemSettingsCodec systemSettingsCodec) {
    this.yaml = yaml;
    this.systemSettingsCodec = systemSettingsCodec;
  }

  SystemSettingsSectionsDTO parseSettings(
      Map<String, Object> document, List<ConfigSyncSkipped> skipped) {
    String key = ConfigSyncKind.SETTINGS.wireValue();
    if (!document.containsKey(key)) {
      return null;
    }
    Object node = document.get(key);
    if (!(node instanceof Map<?, ?> rawMap)) {
      throw DomainException.validation(RESOURCE, "settings must be an object");
    }
    if (rawMap.containsKey("network")) {
      // network 不属于当前六节契约：必须在导入前置契约检查中明确拒绝，不能按未知字段跳过。
      throw DomainException.validation(
          RESOURCE, "settings.network is no longer supported; use backendNetwork");
    }
    Map<String, Object> raw = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
      raw.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    // 未知字段移除并报告；剩余结构必须满足当前完整六节契约。
    List<String> removed = new ArrayList<>();
    Object cleaned =
        yaml.removeUnknownProperties(SystemSettingsSectionsDTO.class, raw, key, removed);
    for (String path : removed) {
      skipped.add(
          new ConfigSyncSkipped(
              ConfigSyncKind.SETTINGS, SETTINGS_ITEM_NAME, "unsupported field: " + path));
    }
    SystemSettingsSectionsDTO sections =
        yaml.convert(cleaned, SystemSettingsSectionsDTO.class, key);
    try {
      systemSettingsCodec.fromDto(sections);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(RESOURCE, "settings are invalid");
    }
    return sections;
  }
}
