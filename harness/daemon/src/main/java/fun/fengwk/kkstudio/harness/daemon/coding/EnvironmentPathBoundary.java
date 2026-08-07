package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 为每个面向文件系统的 tool 强制执行 Daemon environment root 边界。
 *
 * <p>Platform permission 负责授权命令；它绝不会放宽本地的路径与 symlink 边界。
 */
public final class EnvironmentPathBoundary {

  private final Path environmentRoot;
  private final Path defaultWorkdir;

  public EnvironmentPathBoundary(CodingToolsConfig config) {
    this.environmentRoot = config.environmentRoot();
    this.defaultWorkdir = config.defaultWorkdir();
  }

  /** 解析并 canonicalize environment root 内已存在的 work directory。 */
  public Path workdir(String rawWorkdir) {
    if (rawWorkdir == null || rawWorkdir.isBlank()) {
      return defaultWorkdir;
    }
    Path candidate = resolve(rawWorkdir, defaultWorkdir, "workdir");
    if (!Files.isDirectory(candidate)) {
      throw new IllegalArgumentException(
          "workdir must be an existing directory: " + display(rawWorkdir));
    }
    return canonicalExisting(candidate, "workdir");
  }

  /** 解析已存在的文件或目录，并拒绝越出 root 的 symlink 穿越。 */
  public Path existing(String rawPath, Path workdir) {
    Path candidate = resolve(rawPath, requireWorkdir(workdir), "path");
    if (!Files.exists(candidate)) {
      throw new IllegalArgumentException("path does not exist: " + display(rawPath));
    }
    return canonicalExisting(candidate, "path");
  }

  /** 解析写目标：其已存在的祖先路径不得穿越到 environment root 之外。 */
  public Path writable(String rawPath, Path workdir) {
    Path candidate = resolve(rawPath, requireWorkdir(workdir), "path");
    Path existing = candidate;
    while (!Files.exists(existing)) {
      existing = existing.getParent();
      if (existing == null) {
        throw new IllegalArgumentException("path has no existing ancestor: " + display(rawPath));
      }
    }
    Path canonicalAncestor = canonicalExisting(existing, "path ancestor");
    Path resolved = canonicalAncestor.resolve(existing.relativize(candidate)).normalize();
    if (!resolved.startsWith(environmentRoot)) {
      throw new IllegalArgumentException("path escapes environment root: " + display(rawPath));
    }
    if (Files.exists(candidate)) {
      return canonicalExisting(candidate, "path");
    }
    return resolved;
  }

  private Path requireWorkdir(Path workdir) {
    Objects.requireNonNull(workdir, "workdir");
    return canonicalExisting(workdir, "workdir");
  }

  private Path resolve(String raw, Path base, String name) {
    String value = stripPrefix(raw, name);
    Path requested;
    try {
      requested = Path.of(value);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(name + " is not a valid path: " + value, error);
    }
    Path candidate = (requested.isAbsolute() ? requested : base.resolve(requested)).normalize();
    if (!candidate.startsWith(environmentRoot)) {
      throw new IllegalArgumentException(name + " escapes environment root: " + value);
    }
    return candidate;
  }

  private Path canonicalExisting(Path candidate, String name) {
    try {
      Path canonical = candidate.toRealPath();
      if (!canonical.startsWith(environmentRoot)) {
        throw new IllegalArgumentException(
            name + " resolves outside environment root: " + candidate);
      }
      return canonical;
    } catch (IOException error) {
      throw new IllegalArgumentException(
          name + " must exist inside environment root: " + candidate, error);
    }
  }

  private static String stripPrefix(String raw, String name) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    String value = raw.startsWith("@") ? raw.substring(1) : raw;
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  private static String display(String value) {
    return value == null ? "<missing>" : value;
  }
}
