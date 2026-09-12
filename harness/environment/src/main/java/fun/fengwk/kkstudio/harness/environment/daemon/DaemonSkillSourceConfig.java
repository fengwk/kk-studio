package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Platform 冻结给 Daemon 执行的单份 Skill 来源配置。
 *
 * <p>来源唯一身份是 {@code sourceId}；{@code sourceVersion} 是该配置行的版本，Daemon 只按给定版本扫描并回报，不自行推断配置代际。 {@code
 * sourceSetVersion} 是<strong>全局来源集合</strong>（{@code activeSourceIds}）的版本，与行级 {@code sourceVersion}
 * 是两个独立维度：Daemon 据此拒绝延迟到达的旧集合快照，避免用旧集合裁剪掉更新的来源。PATH 来源只带 {@code path} 与 {@code defaultSource}；GIT
 * 来源只带 {@code url}、可空 {@code ref} 与仓库内 {@code scanPath}。 {@code currentlyAppliedRevision} 只属于 GIT
 * 来源，且必须是完整小写 commit id：Platform 记录的上一次已应用 revision，用于 GIT 固定版本更新， 绝不是“期望新版本”；它拒绝空值与任意文本，避免把非法形状带进
 * checkout 路径。 {@code ref} 拒绝以 {@code -} 开头的文本（不是有意义的 ref， 且会被 git 当作选项）。 {@code activeSourceIds}
 * 是本次操作后仍然有效的来源集合（必须包含自身），Daemon 据此裁剪本地快照。
 *
 * <p>本对象只描述配置事实，既不带 workdir 字段，也不构成任何工具的默认目录。
 */
public record DaemonSkillSourceConfig(
    UUID sourceId,
    long sourceVersion,
    long sourceSetVersion,
    DaemonSkillSourceType type,
    String path,
    boolean defaultSource,
    String url,
    String ref,
    String scanPath,
    String currentlyAppliedRevision,
    Set<UUID> activeSourceIds) {

  /** path/url/scanPath 的字符数上限。 */
  public static final int MAX_LOCATION_CHARS = 4096;

  /** ref 的字符数上限。 */
  public static final int MAX_REFERENCE_CHARS = 1024;

  /** activeSourceIds 的元素上限。 */
  public static final int MAX_ACTIVE_SOURCES = 512;

  /** SHA-1 仓库的 commit id 字符数。 */
  public static final int COMMIT_ID_CHARS_SHA1 = 40;

  /** SHA-256 仓库的 commit id 字符数。 */
  public static final int COMMIT_ID_CHARS_SHA256 = 64;

  public DaemonSkillSourceConfig {
    sourceId = Objects.requireNonNull(sourceId, "sourceId");
    if (sourceVersion < 0) {
      throw new IllegalArgumentException("sourceVersion must not be negative");
    }
    if (sourceSetVersion < 0) {
      throw new IllegalArgumentException("sourceSetVersion must not be negative");
    }
    type = Objects.requireNonNull(type, "type");
    Objects.requireNonNull(activeSourceIds, "activeSourceIds");
    if (activeSourceIds.isEmpty()) {
      throw new IllegalArgumentException("activeSourceIds must not be empty");
    }
    if (activeSourceIds.size() > MAX_ACTIVE_SOURCES) {
      throw new IllegalArgumentException(
          "activeSourceIds must not exceed " + MAX_ACTIVE_SOURCES + " entries");
    }
    activeSourceIds = Set.copyOf(activeSourceIds);
    if (!activeSourceIds.contains(sourceId)) {
      throw new IllegalArgumentException("activeSourceIds must contain the operated source");
    }
    if (type == DaemonSkillSourceType.PATH) {
      path = locatedText(path, "path");
      url = requireAbsent(url, "url");
      ref = requireAbsent(ref, "ref");
      scanPath = requireAbsent(scanPath, "scanPath");
      // PATH 来源没有 git 版本概念：已应用 revision 只属于 GIT 来源，避免把它当作可用的版本事实。
      currentlyAppliedRevision =
          requireAbsent(currentlyAppliedRevision, "currentlyAppliedRevision");
    } else {
      path = requireAbsent(path, "path");
      if (defaultSource) {
        throw new IllegalArgumentException("defaultSource is only valid for PATH sources");
      }
      url = locatedText(url, "url");
      ref = ref == null ? null : gitRef(ref);
      if (scanPath != null) {
        String normalizedScanPath = repositoryRelativePath(scanPath);
        scanPath = normalizedScanPath.isEmpty() ? null : normalizedScanPath;
      }
      currentlyAppliedRevision = appliedRevision(currentlyAppliedRevision);
    }
  }

  /** PATH 来源配置。{@code path} 必须是目标 OS 绝对路径或以 {@code ~/} 开头的用户目录路径。 */
  public static DaemonSkillSourceConfig path(
      UUID sourceId,
      long sourceVersion,
      long sourceSetVersion,
      String path,
      boolean defaultSource,
      Set<UUID> activeSourceIds) {
    return new DaemonSkillSourceConfig(
        sourceId,
        sourceVersion,
        sourceSetVersion,
        DaemonSkillSourceType.PATH,
        path,
        defaultSource,
        null,
        null,
        null,
        null,
        activeSourceIds);
  }

  /** GIT 来源配置。{@code scanPath} 是仓库内相对目录，{@code ref} 可空表示跟踪远端默认 HEAD。 */
  public static DaemonSkillSourceConfig git(
      UUID sourceId,
      long sourceVersion,
      long sourceSetVersion,
      String url,
      String ref,
      String scanPath,
      String currentlyAppliedRevision,
      Set<UUID> activeSourceIds) {
    return new DaemonSkillSourceConfig(
        sourceId,
        sourceVersion,
        sourceSetVersion,
        DaemonSkillSourceType.GIT,
        null,
        false,
        url,
        ref,
        scanPath,
        currentlyAppliedRevision,
        activeSourceIds);
  }

  /** 校验仓库内相对路径：拒绝绝对路径、盘符与任何 {@code ..} 段；空文本或 {@code .} 归一为仓库根。 */
  public static String repositoryRelativePath(String value) {
    String text = locatedText(value, "scanPath");
    if (text.startsWith("/") || text.startsWith("\\") || hasWindowsDrivePrefix(text)) {
      throw new IllegalArgumentException("scanPath must be a repository-relative path");
    }
    String unified = text.replace('\\', '/');
    List<String> segments = new ArrayList<>();
    for (String segment : unified.split("/", -1)) {
      if ("..".equals(segment)) {
        throw new IllegalArgumentException("scanPath must not traverse to a parent directory");
      }
      if (segment.isEmpty() || ".".equals(segment)) {
        continue;
      }
      segments.add(segment);
    }
    return String.join("/", segments);
  }

  private static boolean hasWindowsDrivePrefix(String value) {
    if (value.length() < 2) {
      return false;
    }
    char letter = value.charAt(0);
    boolean asciiLetter = (letter >= 'a' && letter <= 'z') || (letter >= 'A' && letter <= 'Z');
    return asciiLetter && value.charAt(1) == ':';
  }

  private static String requireAbsent(String value, String field) {
    if (value != null) {
      throw new IllegalArgumentException(field + " is not valid for this source type");
    }
    return null;
  }

  /**
   * 校验 git ref 文本。
   *
   * <p>以 {@code -} 开头的文本不是任何有意义的 ref（git 自身也会把它读成选项），因此在配置边界直接拒绝，避免把用户输入 当作命令行选项解释。错误只报告结构性问题，不回显
   * ref 值。
   */
  private static String gitRef(String value) {
    String text = referenceText(value, "ref");
    if (text.startsWith("-")) {
      throw new IllegalArgumentException("ref must not begin with '-'");
    }
    return text;
  }

  /**
   * 校验已应用 revision 文本：必须是完整的小写 40 位（SHA-1）或 64 位（SHA-256）十六进制 commit id。
   *
   * <p>已应用 revision 会被 Daemon 当作 {@code checkouts/<sourceId>/<revision>} 的路径段与 {@code git checkout}
   * 参数使用，因此在进入任何路径或子进程边界之前就必须是 Git object id 形状；缺省（null）表示尚未应用任何版本。 错误文本不回显 revision 值。
   */
  private static String appliedRevision(String value) {
    if (value == null) {
      return null;
    }
    if (!isCommitId(value)) {
      throw new IllegalArgumentException(
          "currentlyAppliedRevision must be a lowercase 40- or 64-hex Git commit id");
    }
    return value;
  }

  /** 判断文本是否是完整的小写 40 位或 64 位十六进制 Git object id。 */
  public static boolean isCommitId(String value) {
    if (value == null
        || (value.length() != COMMIT_ID_CHARS_SHA1 && value.length() != COMMIT_ID_CHARS_SHA256)) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char ch = value.charAt(index);
      boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
      if (!hex) {
        return false;
      }
    }
    return true;
  }

  private static String referenceText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not have surrounding whitespace");
    }
    if (value.length() > MAX_REFERENCE_CHARS) {
      throw new IllegalArgumentException(
          field + " must be at most " + MAX_REFERENCE_CHARS + " characters");
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " must not contain control characters");
    }
    return value;
  }

  private static String locatedText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not have surrounding whitespace");
    }
    if (value.length() > MAX_LOCATION_CHARS) {
      throw new IllegalArgumentException(
          field + " must be at most " + MAX_LOCATION_CHARS + " characters");
    }
    if (value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " must not contain control characters");
    }
    return value;
  }
}
