package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Enforces the Daemon workspace boundary for every filesystem-facing tool.
 *
 * <p>Cloud permission authorizes a command; it never relaxes this local path and symlink boundary.
 */
public final class WorkspacePathBoundary {

  private final Path workspaceRoot;
  private final Path defaultWorkdir;

  public WorkspacePathBoundary(CodingToolsConfig config) {
    this.workspaceRoot = config.workspaceRoot();
    this.defaultWorkdir = config.defaultWorkdir();
  }

  /** Resolves and canonicalizes an existing work directory inside the workspace. */
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

  /** Resolves an existing file or directory and rejects symlink traversal beyond the root. */
  public Path existing(String rawPath, Path workdir) {
    Path candidate = resolve(rawPath, requireWorkdir(workdir), "path");
    if (!Files.exists(candidate)) {
      throw new IllegalArgumentException("path does not exist: " + display(rawPath));
    }
    return canonicalExisting(candidate, "path");
  }

  /** Resolves a write target whose existing ancestors cannot traverse outside the workspace. */
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
    if (!resolved.startsWith(workspaceRoot)) {
      throw new IllegalArgumentException("path escapes workspace root: " + display(rawPath));
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
    if (!candidate.startsWith(workspaceRoot)) {
      throw new IllegalArgumentException(name + " escapes workspace root: " + value);
    }
    return candidate;
  }

  private Path canonicalExisting(Path candidate, String name) {
    try {
      Path canonical = candidate.toRealPath();
      if (!canonical.startsWith(workspaceRoot)) {
        throw new IllegalArgumentException(name + " resolves outside workspace root: " + candidate);
      }
      return canonical;
    } catch (IOException error) {
      throw new IllegalArgumentException(
          name + " must exist inside workspace root: " + candidate, error);
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
