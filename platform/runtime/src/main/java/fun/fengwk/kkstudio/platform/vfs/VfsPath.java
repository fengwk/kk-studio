package fun.fengwk.kkstudio.platform.vfs;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Platform VFS URI 纯词法解析与规范化。
 *
 * <p>支持的命名空间种类（Kind）：
 * <ul>
 *   <li>{@link Kind#ROOT}：{@code vfs:/} 或 {@code vfs:}
 *   <li>{@link Kind#SESSIONS_ROOT}：{@code vfs:/sessions} 或 {@code vfs:/sessions/}
 *   <li>{@link Kind#SKILLS}：{@code vfs:/skills} 或 {@code vfs:/skills/}
 *   <li>{@link Kind#SKILLS_PATH}：{@code vfs:/skills/<package>/<skill>/<relativePath>}
 *   <li>{@link Kind#RESOURCE}：{@code vfs:/resources/<blobId>}
 *   <li>{@link Kind#RESOURCES_ROOT}：{@code vfs:/resources} 或 {@code vfs:/resources/}
 *   <li>{@link Kind#SESSION_DIR}：{@code vfs:/sessions/<sessionId>} 或 {@code vfs:/sessions/<sessionId>/}
 *   <li>{@link Kind#SESSION_FILE}：{@code vfs:/sessions/<sessionId>/<sessionPath>}
 * </ul>
 */
public final class VfsPath {

  private static final Pattern RELATIVE_DOT_SEGMENT_PATTERN =
      Pattern.compile("(^|/)\\.\\.?(?:/|$)");

  public enum Kind {
    ROOT,
    SESSIONS_ROOT,
    SKILLS,
    SKILLS_PATH,
    RESOURCE,
    RESOURCES_ROOT,
    SESSION_DIR,
    SESSION_FILE
  }

  private final Kind kind;
  private final String rawPath;
  private final String normalizedPath;
  private final UUID sessionId;
  private final String sessionPath;
  private final UUID threadId;
  private final UUID blobId;
  private final String skillPackage;
  private final String skillName;
  private final String skillRelativePath;
  private final boolean trailingSlash;

  private VfsPath(
      Kind kind,
      String rawPath,
      String normalizedPath,
      UUID sessionId,
      String sessionPath,
      UUID threadId,
      UUID blobId,
      String skillPackage,
      String skillName,
      String skillRelativePath,
      boolean trailingSlash) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.rawPath = Objects.requireNonNull(rawPath, "rawPath");
    this.normalizedPath = Objects.requireNonNull(normalizedPath, "normalizedPath");
    this.sessionId = sessionId;
    this.sessionPath = sessionPath;
    this.threadId = threadId;
    this.blobId = blobId;
    this.skillPackage = skillPackage;
    this.skillName = skillName;
    this.skillRelativePath = skillRelativePath;
    this.trailingSlash = trailingSlash;
  }

  public static VfsPath parse(String uri) {
    if (uri == null || uri.isBlank()) {
      throw new IllegalArgumentException("path must not be blank");
    }
    if (uri.contains("\\")) {
      throw new IllegalArgumentException("path must not contain backslashes: " + uri);
    }
    if ("vfs:".equals(uri)) {
      return new VfsPath(
          Kind.ROOT, uri, "vfs:/", null, null, null, null, null, null, null, false);
    }
    if (!uri.startsWith("vfs:/")) {
      throw new IllegalArgumentException("path must start with vfs:/: " + uri);
    }
    if (uri.startsWith("vfs://") || uri.contains("//")) {
      throw new IllegalArgumentException("path must not contain empty segments: " + uri);
    }
    if (RELATIVE_DOT_SEGMENT_PATTERN.matcher(uri).find()) {
      throw new IllegalArgumentException("path must not contain relative dot segments: " + uri);
    }

    boolean trailingSlash = uri.endsWith("/");
    String remainder = uri.substring(5); // strip "vfs:/"
    if (remainder.isEmpty()) {
      return new VfsPath(
          Kind.ROOT, uri, "vfs:/", null, null, null, null, null, null, null, true);
    }

    int firstSlash = remainder.indexOf('/');
    String top = firstSlash < 0 ? remainder : remainder.substring(0, firstSlash);
    String afterTop = firstSlash < 0 ? "" : remainder.substring(firstSlash + 1);

    switch (top) {
      case "skills":
        if (afterTop.isEmpty()) {
          return new VfsPath(
              Kind.SKILLS,
              uri,
              trailingSlash ? "vfs:/skills/" : "vfs:/skills",
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              trailingSlash);
        }
        int s1 = afterTop.indexOf('/');
        if (s1 <= 0) {
          throw new IllegalArgumentException(
              "invalid skills path (expected vfs:/skills/<package>/<skill>/<file>): " + uri);
        }
        String pkg = afterTop.substring(0, s1);
        String sub = afterTop.substring(s1 + 1);
        int s2 = sub.indexOf('/');
        if (s2 <= 0) {
          throw new IllegalArgumentException(
              "invalid skills path (expected vfs:/skills/<package>/<skill>/<file>): " + uri);
        }
        String skill = sub.substring(0, s2);
        String rel = sub.substring(s2 + 1);
        if (rel.isEmpty() || rel.startsWith("/")) {
          throw new IllegalArgumentException(
              "invalid skills path (relative path must not be empty): " + uri);
        }
        String normalizedSkillPath = "vfs:/skills/" + pkg + "/" + skill + "/" + rel;
        return new VfsPath(
            Kind.SKILLS_PATH,
            uri,
            normalizedSkillPath,
            null,
            null,
            null,
            null,
            pkg,
            skill,
            rel,
            trailingSlash);

      case "resources":
        if (afterTop.isEmpty()) {
          return new VfsPath(
              Kind.RESOURCES_ROOT,
              uri,
              trailingSlash ? "vfs:/resources/" : "vfs:/resources",
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              trailingSlash);
        }
        if (afterTop.contains("/")) {
          throw new IllegalArgumentException("invalid resource path: " + uri);
        }
        UUID blobId = parseCanonicalUuid(afterTop);
        return new VfsPath(
            Kind.RESOURCE,
            uri,
            "vfs:/resources/" + blobId,
            null,
            null,
            null,
            blobId,
            null,
            null,
            null,
            false);

      case "sessions":
        if (afterTop.isEmpty()) {
          return new VfsPath(
              Kind.SESSIONS_ROOT,
              uri,
              trailingSlash ? "vfs:/sessions/" : "vfs:/sessions",
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              trailingSlash);
        }
        int sessionSlash = afterTop.indexOf('/');
        String sessionIdStr = sessionSlash < 0 ? afterTop : afterTop.substring(0, sessionSlash);
        String sessionRelative = sessionSlash < 0 ? "" : afterTop.substring(sessionSlash + 1);
        UUID sessionId = parseCanonicalUuid(sessionIdStr);

        if (sessionRelative.isEmpty()) {
          return new VfsPath(
              Kind.SESSION_DIR,
              uri,
              "vfs:/sessions/" + sessionId + "/",
              sessionId,
              "",
              null,
              null,
              null,
              null,
              null,
              trailingSlash);
        }

        UUID threadId = extractThreadId(sessionRelative);
        return new VfsPath(
            Kind.SESSION_FILE,
            uri,
            "vfs:/sessions/" + sessionId + "/" + sessionRelative,
            sessionId,
            sessionRelative,
            threadId,
            null,
            null,
            null,
            null,
            trailingSlash);

      default:
        throw new IllegalArgumentException("unknown vfs namespace: " + top);
    }
  }

  public Kind kind() {
    return kind;
  }

  public String rawPath() {
    return rawPath;
  }

  public String normalizedPath() {
    return normalizedPath;
  }

  public UUID sessionId() {
    return sessionId;
  }

  public String sessionPath() {
    return sessionPath;
  }

  public UUID threadId() {
    return threadId;
  }

  public UUID blobId() {
    return blobId;
  }

  public String skillPackage() {
    return skillPackage;
  }

  public String skillName() {
    return skillName;
  }

  public String skillRelativePath() {
    return skillRelativePath;
  }

  public boolean trailingSlash() {
    return trailingSlash;
  }

  public boolean isReadOnly() {
    return kind != Kind.SESSION_FILE;
  }

  public boolean isUnderSkillsRoot() {
    return kind == Kind.SKILLS || kind == Kind.SKILLS_PATH;
  }

  public boolean isUnderResourcesRoot() {
    return kind == Kind.RESOURCES_ROOT || kind == Kind.RESOURCE;
  }

  public boolean isThreadScoped() {
    return sessionPath != null
        && sessionPath.startsWith("threads/")
        && !"threads/".equals(sessionPath)
        && !"threads".equals(sessionPath);
  }

  private static UUID parseCanonicalUuid(String str) {
    if (str == null || str.length() != 36) {
      throw new IllegalArgumentException("invalid canonical UUID: " + str);
    }
    UUID uuid;
    try {
      uuid = UUID.fromString(str);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("invalid canonical UUID: " + str);
    }
    if (!uuid.toString().equals(str)) {
      throw new IllegalArgumentException("invalid canonical UUID: " + str);
    }
    return uuid;
  }

  private static UUID extractThreadId(String sessionRel) {
    if (sessionRel.startsWith("threads/")) {
      String after = sessionRel.substring("threads/".length());
      int slash = after.indexOf('/');
      String threadStr = slash < 0 ? after : after.substring(0, slash);
      if (threadStr.length() == 36) {
        try {
          UUID candidate = UUID.fromString(threadStr);
          if (candidate.toString().equals(threadStr)) {
            return candidate;
          }
        } catch (IllegalArgumentException ignored) {
          // not a canonical UUID
        }
      }
    }
    return null;
  }
}
