package fun.fengwk.kkstudio.harness.environment.capability;

/**
 * Environment Capability 结果中使用的稳定错误码。
 *
 * <p>错误码进入结果 details 的结构化字段，用于跨进程的确定性判定（例如 Platform 把不可加载的 Skill 标记为配置错误）；它不是本地错误消息的
 * 替代品，也不携带任何本地事实。码只允许 {@code UPPER_SNAKE} 形状，因此可以安全地进入 wire 与调用方判定。
 */
public final class EnvironmentCapabilityResultCodes {

  /**
   * 请求的冻结资源版本在本 Environment 上已不可用。
   *
   * <p>典型场景：{@code skill.load} 请求的 {@code (sourceId, name, revision)} 已不再保留。调用方必须把该结果视为资源已变化，
   * 绝不能把当前版本的内容冒充为被请求的旧版本。
   */
  public static final String RESOURCE_CHANGED = "RESOURCE_CHANGED";

  /** 错误码的字符数上限。 */
  public static final int MAX_CODE_CHARS = 64;

  private EnvironmentCapabilityResultCodes() {}

  /**
   * 校验错误码是安全的稳定码：非空白、至多 {@link #MAX_CODE_CHARS} 个字符、且满足 {@code [A-Z][A-Z0-9_]*}。
   *
   * <p>受限语法保证码可以安全进入结构化 details 与调用方判定，不会夹带本地路径、消息或任意用户输入。
   */
  public static String requireCode(String code) {
    if (code == null || code.isEmpty()) {
      throw new IllegalArgumentException("code must not be blank");
    }
    if (code.length() > MAX_CODE_CHARS) {
      throw new IllegalArgumentException("code must be at most " + MAX_CODE_CHARS + " characters");
    }
    char first = code.charAt(0);
    if (first < 'A' || first > 'Z') {
      throw new IllegalArgumentException("code must start with an uppercase letter");
    }
    for (int index = 1; index < code.length(); index++) {
      char ch = code.charAt(index);
      boolean allowed = (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_';
      if (!allowed) {
        throw new IllegalArgumentException(
            "code must contain only uppercase letters, digits and underscores");
      }
    }
    return code;
  }
}
