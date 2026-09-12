package fun.fengwk.kkstudio.platform.environment.skill;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceType;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

/**
 * Skill 来源配置的结构校验与规范化。
 *
 * <p>只做词法校验：Backend 不解析远端路径、不访问文件系统。PATH 必须是 {@code ~/} 前导形式或至少一种受支持目标 OS 形态的词法绝对路径 （Unix、Windows
 * 盘符根、UNC）；GIT 的 {@code ref}/{@code scanPath} 约束与 {@link DaemonSkillSourceConfig} 完全一致（无 {@code
 * ..}、非绝对 scanPath、ref 不以 {@code -} 开头、canonical 文本与长度上限），因此 Platform 接受的 配置一定能被 Daemon 解码。
 *
 * <p>错误消息只报告字段与结构性问题，绝不回显 url/ref/path 原值：这些字段可能携带用户凭据。
 */
public final class SkillSourceConfigValidator {

  /** 来源配置资源标签。 */
  public static final String RESOURCE = "environment_skill_source";

  private SkillSourceConfigValidator() {}

  /**
   * 规范化创建/更新请求的来源配置。
   *
   * <p>可选字段的空白文本按缺省处理（表单清空可选输入即“不指定”）；非空文本必须 canonical：不允许环绕空白、控制字符或超出契约长度。
   */
  public static NormalizedConfig normalize(
      String type, String path, String gitUrl, String gitRef, String scanPath) {
    DaemonSkillSourceType sourceType = requireType(type);
    if (sourceType == DaemonSkillSourceType.PATH) {
      requireAbsent(gitUrl, "gitUrl");
      requireAbsent(gitRef, "gitRef");
      requireAbsent(scanPath, "scanPath");
      return NormalizedConfig.path(requireSourcePath(path));
    }
    requireAbsent(path, "path");
    return NormalizedConfig.git(
        requireGitUrl(gitUrl), optionalRef(gitRef), optionalScanPath(scanPath));
  }

  private static DaemonSkillSourceType requireType(String type) {
    if (type == null || type.isBlank()) {
      throw new AiValidationException(RESOURCE, "type must not be blank");
    }
    if (!type.equals(type.strip())) {
      throw new AiValidationException(RESOURCE, "type must not have surrounding whitespace");
    }
    try {
      return DaemonSkillSourceType.fromWireValue(type);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, "type must be one of: path, git");
    }
  }

  /** PATH 来源目录：{@code ~/} 前导形式或词法绝对路径；不做任何展开，也不访问文件系统。 */
  private static String requireSourcePath(String path) {
    String text = requireText(path, "path", DaemonSkillSourceConfig.MAX_LOCATION_CHARS, true);
    if (text.startsWith("~/")) {
      return text;
    }
    if (isLexicallyAbsolute(text)) {
      return text;
    }
    throw new AiValidationException(RESOURCE, "path must be an absolute path or start with '~/'");
  }

  /** 至少一种受支持目标 OS 形态的词法绝对路径：Unix、Windows 盘符根、UNC。 */
  private static boolean isLexicallyAbsolute(String value) {
    if (value.startsWith("/")) {
      return true;
    }
    if (isUncPath(value)) {
      return true;
    }
    return hasWindowsDriveRoot(value);
  }

  private static boolean isUncPath(String value) {
    boolean backslashUnc = value.startsWith("\\\\");
    boolean slashUnc = value.startsWith("//");
    if (!backslashUnc && !slashUnc) {
      return false;
    }
    String[] segments = value.substring(2).split("[/\\\\]", -1);
    return segments.length >= 2 && !segments[0].isBlank() && !segments[1].isBlank();
  }

  private static boolean hasWindowsDriveRoot(String value) {
    if (value.length() < 3) {
      return false;
    }
    char letter = value.charAt(0);
    boolean asciiLetter = (letter >= 'a' && letter <= 'z') || (letter >= 'A' && letter <= 'Z');
    if (!asciiLetter || value.charAt(1) != ':') {
      return false;
    }
    char separator = value.charAt(2);
    return separator == '/' || separator == '\\';
  }

  private static String requireGitUrl(String gitUrl) {
    return requireText(gitUrl, "gitUrl", DaemonSkillSourceConfig.MAX_LOCATION_CHARS, true);
  }

  private static String optionalRef(String gitRef) {
    String text = requireText(gitRef, "gitRef", DaemonSkillSourceConfig.MAX_REFERENCE_CHARS, false);
    if (text != null && text.startsWith("-")) {
      throw new AiValidationException(RESOURCE, "gitRef must not begin with '-'");
    }
    return text;
  }

  /** 仓库内相对扫描目录：复用 Daemon 规则，因此拒绝绝对路径、盘符与 {@code ..}，并把空文本与 {@code .} 归一为仓库根。 */
  private static String optionalScanPath(String scanPath) {
    String text =
        requireText(scanPath, "scanPath", DaemonSkillSourceConfig.MAX_LOCATION_CHARS, false);
    if (text == null) {
      return null;
    }
    String normalized;
    try {
      normalized = DaemonSkillSourceConfig.repositoryRelativePath(text);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage());
    }
    return normalized.isEmpty() ? null : normalized;
  }

  private static void requireAbsent(String value, String field) {
    if (value != null) {
      throw new AiValidationException(RESOURCE, field + " is not valid for this source type");
    }
  }

  private static String requireText(String value, String field, int maxLength, boolean required) {
    if (value == null || value.isBlank()) {
      if (required) {
        throw new AiValidationException(RESOURCE, field + " must not be blank");
      }
      return null;
    }
    if (!value.equals(value.strip())) {
      throw new AiValidationException(RESOURCE, field + " must not have surrounding whitespace");
    }
    if (value.length() > maxLength) {
      throw new AiValidationException(
          RESOURCE, field + " must be at most " + maxLength + " characters");
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new AiValidationException(RESOURCE, field + " must not contain control characters");
    }
    return value;
  }

  /** 归一化后的来源配置；GIT 字段对 PATH 来源恒为 null，{@code scanPath} 已归一为仓库根表示。 */
  public record NormalizedConfig(
      DaemonSkillSourceType type, String path, String gitUrl, String gitRef, String scanPath) {

    static NormalizedConfig path(String path) {
      return new NormalizedConfig(DaemonSkillSourceType.PATH, path, null, null, null);
    }

    static NormalizedConfig git(String gitUrl, String gitRef, String scanPath) {
      return new NormalizedConfig(DaemonSkillSourceType.GIT, null, gitUrl, gitRef, scanPath);
    }

    @Override
    public String toString() {
      return "NormalizedConfig[type=" + type + "]";
    }
  }
}
