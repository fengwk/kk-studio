package fun.fengwk.kkstudio.platform.catalog.skill;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Skill Package 请求字段的 canonical 形式与拒绝条件。
 *
 * <p>这些规则同时是 HTTP 边界、配置导入与两个发布渠道的唯一事实，因此集中在这里；每个方法都把非法输入翻译成 {@link
 * DomainException}，不接受也在同一处拒绝（{@code null} 表示省略的语义由调用方决定）。
 */
final class SkillPackageInputs {

  static final String RESOURCE = "skill_package";

  /** 40 或 64 位小写 hex object id。 */
  private static final Pattern COMMIT = Pattern.compile("^([0-9a-f]{40}|[0-9a-f]{64})$");

  /** Git repository URL：必须带受支持 scheme、无环绕空白、无控制字符、≤2048、不得内嵌 userinfo。 */
  private static final Pattern REPOSITORY_URL = Pattern.compile("^[a-z][a-z0-9+.-]*://[^\\s]+$");

  /** 本地 file 仓库可由 {@code File#toURI()} 产生单斜杠形式：{@code file:/path}。 */
  private static final Pattern FILE_URL = Pattern.compile("^file:/[^\\s]*$");

  /** 只有受网络保护覆盖的 http/https 与本地直读 file 允许进入 Git 网络层。 */
  private static final Set<String> SUPPORTED_REPOSITORY_SCHEMES = Set.of("http", "https", "file");

  private static final Pattern USERINFO = Pattern.compile("://[^/\\s]*@");

  private static final int MAX_REPOSITORY_URL_CHARS = 2048;
  private static final int MAX_BRANCH_CHARS = 255;
  private static final int MAX_VERSION_CHARS = 128;

  private SkillPackageInputs() {}

  static String requirePackageName(String raw) {
    try {
      return SkillNames.canonicalPackageName(raw);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(
          RESOURCE, "invalid package name: " + error.getMessage(), error);
    }
  }

  /** 上传渠道版本号：去环绕空白后 1–128 字符，不得包含控制字符。 */
  static String requireVersionText(String raw) {
    String version = raw == null ? null : raw.strip();
    if (version == null || version.isEmpty()) {
      throw DomainException.validation(RESOURCE, "packageVersion must not be blank");
    }
    if (version.length() > MAX_VERSION_CHARS
        || version.codePoints().anyMatch(Character::isISOControl)) {
      throw DomainException.validation(
          RESOURCE,
          "packageVersion must be at most "
              + MAX_VERSION_CHARS
              + " characters without control characters");
    }
    return version;
  }

  static String requireRepositoryUrl(String raw) {
    if (raw == null || raw.isBlank() || !raw.equals(raw.strip())) {
      throw DomainException.validation(RESOURCE, "repositoryUrl must be a non-blank, unpadded URL");
    }
    if (raw.length() > MAX_REPOSITORY_URL_CHARS
        || !(REPOSITORY_URL.matcher(raw).matches() || FILE_URL.matcher(raw).matches())
        || USERINFO.matcher(raw).find()
        || raw.codePoints().anyMatch(Character::isISOControl)) {
      throw DomainException.validation(
          RESOURCE,
          "repositoryUrl must be a supported http/https/file URL without userinfo and be at most "
              + MAX_REPOSITORY_URL_CHARS
              + " characters");
    }
    String scheme = schemeOf(raw);
    if (!SUPPORTED_REPOSITORY_SCHEMES.contains(scheme)) {
      throw DomainException.validation(RESOURCE, "unsupported repositoryUrl scheme: " + scheme);
    }
    return raw;
  }

  private static String schemeOf(String raw) {
    int index = raw.indexOf(':');
    return index <= 0 ? "" : raw.substring(0, index).toLowerCase(Locale.ROOT);
  }

  static String requireBranch(String raw) {
    if (raw == null || raw.isBlank() || !raw.equals(raw.strip())) {
      throw DomainException.validation(RESOURCE, "branch must be a non-blank, unpadded name");
    }
    if (raw.length() > MAX_BRANCH_CHARS || raw.codePoints().anyMatch(Character::isISOControl)) {
      throw DomainException.validation(
          RESOURCE,
          "branch must not contain control characters and be at most "
              + MAX_BRANCH_CHARS
              + " characters");
    }
    return raw;
  }

  static String requireCommit(String raw, String field) {
    if (raw == null || !COMMIT.matcher(raw).matches()) {
      throw DomainException.validation(
          RESOURCE, field + " must be a 40 or 64 characters long lowercase hex object id");
    }
    return raw;
  }

  /** 创建请求的秘密字段：省略表示无令牌，只接受 SET。 */
  static String createToken(SecretUpdate update) {
    if (update == null) {
      return null;
    }
    if (update.operation() != SecretUpdate.Operation.SET) {
      throw DomainException.validation(RESOURCE, "token must use the SET operation on create");
    }
    return requireToken(update.value());
  }

  static String requireToken(String raw) {
    String token = raw == null ? null : raw.strip();
    if (token == null || token.isEmpty()) {
      throw DomainException.validation(RESOURCE, "token must be a non-blank string when provided");
    }
    if (token.codePoints().anyMatch(Character::isISOControl)) {
      throw DomainException.validation(RESOURCE, "token must not contain control characters");
    }
    return token;
  }

  /** 配置导入的令牌可为空：空字符串与省略等价。 */
  static String canonicalImportToken(String raw) {
    if (raw == null) {
      return null;
    }
    String token = raw.strip();
    return token.isEmpty() ? null : token;
  }

  static long requireExpectedVersion(Long expected) {
    if (expected == null) {
      throw DomainException.validation(RESOURCE, "expectedVersion is required");
    }
    return expected;
  }

  /** 检查错误必须有界、非空且无环绕空白；只保留首个错误行以内的可读摘要，避免把远端自由文本写进持久化观察值。 */
  static String boundCheckError(RuntimeException error, int maxBytes) {
    String message =
        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    StringBuilder bounded = new StringBuilder();
    for (int index = 0; index < message.length(); index++) {
      char current = message.charAt(index);
      if (Character.isISOControl(current)) {
        break;
      }
      bounded.append(current);
    }
    String summary = bounded.toString().strip();
    if (summary.isEmpty()) {
      summary = error.getClass().getSimpleName();
    }
    while (summary.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
      summary = summary.substring(0, summary.length() - 1);
    }
    return summary.strip();
  }
}
