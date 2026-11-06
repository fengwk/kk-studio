package fun.fengwk.kkstudio.platform.environment;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.environment.dto.DaemonConfigurationCodec;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentInstallConfigDTO;

import java.util.Set;

/** 配置同步与持久化共用的安装设置边界；只检查输入，不探测服务器上的宿主路径。 */
public final class EnvironmentInstallConfigs {
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  /** 可由安装脚本部署的操作系统 family；WSL 在宿主侧按 Linux 安装，不作为安装目标。 */
  private static final Set<DaemonOperatingSystem> INSTALLABLE =
      Set.of(
          DaemonOperatingSystem.LINUX, DaemonOperatingSystem.MACOS, DaemonOperatingSystem.WINDOWS);

  private EnvironmentInstallConfigs() {}

  public static EnvironmentInstallConfigDTO parse(JsonNode node) {
    fields(node, Set.of("operatingSystem", "daemon"), "installConfig");
    EnvironmentInstallConfigDTO result = new EnvironmentInstallConfigDTO();
    String operatingSystem = text(node.get("operatingSystem"), "installConfig.operatingSystem");
    try {
      result.setOperatingSystem(DaemonOperatingSystem.fromWireValue(operatingSystem));
    } catch (IllegalArgumentException error) {
      throw invalid("installConfig.operatingSystem must be linux, macos or windows");
    }
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
    DaemonOperatingSystem os = value.getOperatingSystem();
    if (os == null || !INSTALLABLE.contains(os)) {
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

  private static String text(JsonNode node, String path) {
    if (node == null || node.isNull() || !node.isTextual()) {
      throw invalid(path + " must be a string");
    }
    return node.textValue();
  }

  private static DomainException invalid(String message) {
    return DomainException.validation("environment", message);
  }
}
