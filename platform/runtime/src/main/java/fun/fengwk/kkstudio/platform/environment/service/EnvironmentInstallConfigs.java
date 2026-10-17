package fun.fengwk.kkstudio.platform.environment.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.DaemonConfigurationCodec;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallConfigDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallConfigUpdateDTO;

import java.util.Set;

/** HTTP、配置同步和持久化共用的安装设置边界；只检查输入，不探测服务器上的宿主路径。 */
public final class EnvironmentInstallConfigs {
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private EnvironmentInstallConfigs() {}

  public static EnvironmentInstallConfigUpdateDTO parseUpdate(JsonNode node) {
    fields(node, Set.of("expectedVersion", "installConfig"), "request");
    EnvironmentInstallConfigUpdateDTO result = new EnvironmentInstallConfigUpdateDTO();
    result.setExpectedVersion(text(node.get("expectedVersion"), "expectedVersion", true));
    result.setInstallConfig(parse(node.get("installConfig")));
    return result;
  }

  public static EnvironmentInstallConfigDTO parse(JsonNode node) {
    fields(node, Set.of("operatingSystem", "daemon"), "installConfig");
    EnvironmentInstallConfigDTO result = new EnvironmentInstallConfigDTO();
    result.setOperatingSystem(
        text(node.get("operatingSystem"), "installConfig.operatingSystem", true));
    try {
      result.setDaemon(DaemonConfigurationCodec.parse(node.get("daemon")));
    } catch (IllegalArgumentException error) {
      // Shared codec errors contain only paths/rules, never submitted values.
      throw invalid("installConfig." + error.getMessage());
    }
    return validate(result);
  }

  public static EnvironmentInstallConfigDTO validate(EnvironmentInstallConfigDTO value) {
    if (value == null) {
      throw invalid("installConfig must be an object");
    }
    String os = value.getOperatingSystem();
    if (!Set.of("linux", "macos", "windows").contains(os == null ? "" : os)) {
      throw invalid("installConfig.operatingSystem must be linux, macos or windows");
    }
    EnvironmentInstallConfigDTO result = new EnvironmentInstallConfigDTO();
    result.setOperatingSystem(os);
    try {
      result.setDaemon(DaemonConfigurationCodec.validate(value.getDaemon()));
    } catch (IllegalArgumentException error) {
      throw invalid("installConfig." + error.getMessage());
    }
    return result;
  }

  public static JsonNode tree(Object value) {
    return JSON.valueToTree(value);
  }

  public static String write(EnvironmentInstallConfigDTO value) {
    if (value == null) {
      return null;
    }
    try {
      return JSON.writeValueAsString(value);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot serialize installConfig", error);
    }
  }

  public static EnvironmentInstallConfigDTO read(String value) {
    if (value == null) {
      return null;
    }
    try {
      return parse(JSON.readTree(value));
    } catch (JacksonException error) {
      throw new IllegalStateException("invalid persisted installConfig", error);
    }
  }

  private static void fields(JsonNode node, Set<String> allowed, String path) {
    if (node == null || !node.isObject()) {
      throw invalid(path + " must be an object");
    }
    node.propertyNames()
        .forEach(
            key -> {
              if (!allowed.contains(key)) {
                throw invalid(path + " has an unknown field");
              }
            });
  }

  private static String text(JsonNode node, String path, boolean required) {
    if (node == null || node.isNull()) {
      if (required) {
        throw invalid(path + " must be a string");
      }
      return null;
    }
    if (!node.isTextual()) {
      throw invalid(path + " must be a string");
    }
    return node.textValue();
  }

  private static AiValidationException invalid(String message) {
    return new AiValidationException("environment", message);
  }
}
