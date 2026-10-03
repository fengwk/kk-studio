package fun.fengwk.kkstudio.share.configsync;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 配置同步的七个可选配置集合。
 *
 * <p>wire 值固定为 CONTRACT.md 中的小驼峰集合名；YAML 顶层键、inventory、导出选择与导入结果都用同一个值，前后端共享。
 */
public enum ConfigSyncKind {
  PROVIDERS("providers"),
  MODELS("models"),
  AGENTS("agents"),
  SKILL_PACKAGES("skillPackages"),
  ENVIRONMENTS("environments"),
  MCP_SERVERS("mcpServers"),
  SETTINGS("settings");

  private final String wireValue;

  ConfigSyncKind(String wireValue) {
    this.wireValue = wireValue;
  }

  /** 固定 wire 值。 */
  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  /** 按 wire 值解析；未知类别抛 {@link IllegalArgumentException}。 */
  @JsonCreator
  public static ConfigSyncKind fromWireValue(String value) {
    return Arrays.stream(values())
        .filter(kind -> kind.wireValue.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown config sync kind: " + value));
  }
}
