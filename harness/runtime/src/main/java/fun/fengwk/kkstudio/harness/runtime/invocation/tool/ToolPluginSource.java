package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 冻结 Tool 的插件来源身份：安装身份、代码版本、配置修订与能力/凭据上下文指纹。
 *
 * <p>只记录身份事实，不携带任何明文或密文凭据 Key；凭据只在宿主执行边界按冻结引用解析。{@code pluginId} 与 {@code pluginVersion}
 * 与安装描述符同源，canonical 校验不宽松标准化；指纹必须是 64 位小写 SHA-256，{@code authGeneration} 必须是认证代际 UUID。
 *
 * <p>{@code configRevision} 为正且不晚于所属 {@link ToolBinding} 的 {@code catalogEpoch}；内置与 MCP 工具没有插件来源，由
 * {@code null} 明确表示，而不是伪造成匿名插件。
 */
public record ToolPluginSource(
    String pluginId,
    String pluginVersion,
    long configRevision,
    String capabilityFingerprint,
    String contextFingerprint,
    UUID authGeneration) {

  /** 与 {@code plugin_credential.plugin_id} 列一致的安装身份长度上限。 */
  public static final int MAX_PLUGIN_ID_LENGTH = 64;

  /** 插件代码版本长度上限。 */
  public static final int MAX_PLUGIN_VERSION_LENGTH = 64;

  private static final Pattern PLUGIN_ID_SYNTAX = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");
  private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

  public ToolPluginSource {
    requirePluginId(pluginId);
    pluginVersion = requireVersion(pluginVersion);
    if (configRevision <= 0) {
      throw new IllegalArgumentException("configRevision must be positive");
    }
    capabilityFingerprint = requireFingerprint(capabilityFingerprint, "capabilityFingerprint");
    contextFingerprint = requireFingerprint(contextFingerprint, "contextFingerprint");
    authGeneration = Objects.requireNonNull(authGeneration, "authGeneration");
  }

  /** 是否是一个合法的 64 位小写 SHA-256 指纹。 */
  public static boolean isFingerprint(String value) {
    return value != null && FINGERPRINT.matcher(value).matches();
  }

  private static void requirePluginId(String pluginId) {
    if (pluginId == null
        || pluginId.length() > MAX_PLUGIN_ID_LENGTH
        || !PLUGIN_ID_SYNTAX.matcher(pluginId).matches()) {
      throw new IllegalArgumentException("pluginId must be a canonical lowercase identifier");
    }
  }

  private static String requireVersion(String version) {
    if (version == null
        || version.isBlank()
        || version.length() > MAX_PLUGIN_VERSION_LENGTH
        || !version.equals(version.strip())) {
      throw new IllegalArgumentException("pluginVersion must be a bounded non-blank text");
    }
    return version;
  }

  private static String requireFingerprint(String value, String field) {
    if (!isFingerprint(value)) {
      throw new IllegalArgumentException(field + " must be a 64-char lowercase SHA-256");
    }
    return value;
  }
}
