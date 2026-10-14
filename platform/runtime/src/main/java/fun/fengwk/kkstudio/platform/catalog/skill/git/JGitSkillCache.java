package fun.fengwk.kkstudio.platform.catalog.skill.git;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.context.annotation.DependsOn;

import fun.fengwk.kkstudio.harness.common.skill.SkillNames;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Platform 拥有的 Git Skill cache 默认实现。
 *
 * <p>底层按 temporary clone -> materialize/checkout exact commit -> validate manifest -> atomic
 * publish 流程维护物化目录缓存，读取与扫描均直接面向发布目录。
 */
@Slf4j
@DependsOn("systemProxySelector")
public class JGitSkillCache implements SkillGitCache {

  private static final Pattern COMMIT_PATTERN = Pattern.compile("^([0-9a-f]{40}|[0-9a-f]{64})$");

  /** 受网络保护覆盖或本地直读的仓库 scheme；其他 scheme 在直接入口 fail-closed。 */
  private static final Set<String> SUPPORTED_REPOSITORY_SCHEMES = Set.of("http", "https", "file");

  private final Path cacheRoot;
  private final int connectMillis;
  private final int readMillis;
  private final ConcurrentMap<String, Object> packageLocks = new ConcurrentHashMap<>();

  /**
   * 构造 JGitSkillCache，若 cacheRoot 目录不存在则自动创建。
   *
   * @param cacheRoot 仓库的根缓存目录
   */
  public JGitSkillCache(Path cacheRoot) {
    this(cacheRoot, GitHttpConnectionFactory.CONNECT_MILLIS, GitHttpConnectionFactory.READ_MILLIS);
  }

  JGitSkillCache(Path cacheRoot, int connectMillis, int readMillis) {
    Objects.requireNonNull(cacheRoot, "cacheRoot");
    this.connectMillis = connectMillis;
    this.readMillis = readMillis;
    this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
    try {
      Files.createDirectories(this.cacheRoot);
    } catch (IOException e) {
      throw new SkillGitException("Failed to create cache root directory: " + this.cacheRoot, e);
    }
  }

  @Override
  public String resolveBranchHead(String repositoryUrl, String branch, String token) {
    validateRepositoryUrl(repositoryUrl);
    if (branch == null || branch.isBlank()) {
      throw new SkillGitException("branch must not be blank");
    }
    if (branch.codePoints().anyMatch(Character::isISOControl)) {
      throw new SkillGitException("branch must not contain control characters");
    }

    String targetRefName = "refs/heads/" + branch;
    CredentialsProvider credentials = credentialsProvider(token);
    try (GitHttpConnectionFactory network =
        new GitHttpConnectionFactory(connectMillis, readMillis)) {
      var lsRemoteCommand =
          Git.lsRemoteRepository()
              .setRemote(repositoryUrl)
              .setTransportConfigCallback(network.callback());
      if (credentials != null) {
        lsRemoteCommand.setCredentialsProvider(credentials);
      }
      Map<String, Ref> refMap = lsRemoteCommand.callAsMap();

      Ref ref = refMap.get(targetRefName);
      if (ref == null || ref.getObjectId() == null) {
        throw new SkillGitException(
            "Branch '"
                + branch
                + "' ("
                + targetRefName
                + ") not found in repository "
                + repositoryUrl);
      }

      String commitId = ref.getObjectId().getName();
      if (commitId == null || !COMMIT_PATTERN.matcher(commitId).matches()) {
        throw new SkillGitException(
            "Branch head commit id is invalid for branch '" + branch + "': " + commitId);
      }
      return commitId;
    } catch (SkillGitException e) {
      throw e;
    } catch (Exception e) {
      if (GitHttpConnectionFactory.isAuthenticationFailure(e)) {
        throw authenticationFailed(e);
      }
      throw new SkillGitException(
          GitHttpConnectionFactory.failureCode(e)
              + ": Failed to resolve branch head for "
              + repositoryUrl
              + " branch "
              + branch
              + ": "
              + e.getMessage(),
          e);
    }
  }

  @Override
  public void ensureCommit(String packageName, String repositoryUrl, String commit, String token) {
    validateCommit(commit);
    validatePackageName(packageName);
    validateRepositoryUrl(repositoryUrl);

    Object lock = packageLocks.computeIfAbsent(packageName, k -> new Object());
    synchronized (lock) {
      Path publishedDir = resolvePublishedDir(packageName);
      Path workRoot = cacheRoot.resolve(".work").normalize();

      Path commitMarker = publishedDir.resolve(".kkstudio-commit");
      if (Files.isRegularFile(commitMarker, LinkOption.NOFOLLOW_LINKS)) {
        try {
          String cachedCommit = Files.readString(commitMarker, StandardCharsets.UTF_8).trim();
          if (commit.equals(cachedCommit)) {
            return;
          }
        } catch (IOException ignored) {
          // If reading fails, proceed to clone and publish
        }
      }

      try {
        Files.createDirectories(workRoot);
      } catch (IOException e) {
        throw new SkillGitException("Failed to create work directory: " + workRoot, e);
      }

      String uuid = UUID.randomUUID().toString();
      Path cloneDir = workRoot.resolve(packageName + "." + uuid + ".clone");
      Path stagingDir = workRoot.resolve(packageName + "." + uuid + ".staging");
      Path oldDir = workRoot.resolve(packageName + "." + uuid + ".old");

      try {
        CredentialsProvider credentials = credentialsProvider(token);
        Git git;
        try (GitHttpConnectionFactory network =
            new GitHttpConnectionFactory(connectMillis, readMillis)) {
          CloneCommand cloneCommand =
              Git.cloneRepository()
                  .setURI(repositoryUrl)
                  .setDirectory(cloneDir.toFile())
                  .setCloneAllBranches(true)
                  .setTransportConfigCallback(network.callback());
          if (credentials != null) {
            cloneCommand.setCredentialsProvider(credentials);
          }
          try {
            git = cloneCommand.call();
          } catch (Exception e) {
            if (GitHttpConnectionFactory.isAuthenticationFailure(e)) {
              throw authenticationFailed(e);
            }
            throw new SkillGitException(
                GitHttpConnectionFactory.failureCode(e) + ": Failed to clone repository", e);
          }
        }

        try (git) {
          Repository repo = git.getRepository();
          ObjectId commitId = ObjectId.fromString(commit);
          if (!repo.getObjectDatabase().has(commitId)) {
            throw new SkillGitException("Commit " + commit + " does not exist in repository");
          }

          RevCommit revCommit;
          try (RevWalk revWalk = new RevWalk(repo)) {
            revCommit = revWalk.parseCommit(commitId);
          } catch (Exception e) {
            throw new SkillGitException(
                "Invalid commit object " + commit + " in repository: " + e.getMessage(), e);
          }

          try {
            Files.createDirectories(stagingDir);
          } catch (IOException e) {
            throw new SkillGitException("Failed to create staging directory: " + stagingDir, e);
          }

          try (TreeWalk treeWalk = new TreeWalk(repo)) {
            treeWalk.addTree(revCommit.getTree());
            treeWalk.setRecursive(false);
            while (treeWalk.next()) {
              String name = treeWalk.getNameString();
              SkillManifestScanner.validateEntrySegment(name);

              String pathString = treeWalk.getPathString();
              Path target = stagingDir.resolve(pathString).normalize();
              if (!target.startsWith(stagingDir) || target.equals(stagingDir)) {
                throw new SkillGitException("Path escapes staging directory: " + pathString);
              }

              FileMode fileMode = treeWalk.getFileMode(0);
              if (fileMode == FileMode.TREE) {
                try {
                  Files.createDirectories(target);
                } catch (IOException e) {
                  throw new SkillGitException("Failed to create directory: " + pathString, e);
                }
                treeWalk.enterSubtree();
              } else if (fileMode == FileMode.REGULAR_FILE
                  || fileMode == FileMode.EXECUTABLE_FILE) {
                Path parent = target.getParent();
                if (parent != null) {
                  try {
                    Files.createDirectories(parent);
                  } catch (IOException e) {
                    throw new SkillGitException(
                        "Failed to create parent directory for: " + pathString, e);
                  }
                }
                ObjectId blobId = treeWalk.getObjectId(0);
                try {
                  ObjectLoader loader = repo.open(blobId, Constants.OBJ_BLOB);
                  Files.write(target, loader.getBytes());
                } catch (IOException e) {
                  throw new SkillGitException("Failed to write blob for: " + pathString, e);
                }
              } else {
                throw new SkillGitException(
                    "Forbidden file mode " + fileMode + " for entry: " + pathString);
              }
            }
          } catch (SkillGitException e) {
            throw e;
          } catch (Exception e) {
            throw new SkillGitException(
                "Failed to materialize commit tree for " + commit + ": " + e.getMessage(), e);
          }
        }

        SkillManifestScanner.scan(stagingDir, packageName);

        try {
          Files.writeString(
              stagingDir.resolve(".kkstudio-commit"), commit + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
          throw new SkillGitException("Failed to write commit marker file in staging directory", e);
        }

        boolean backedUp = false;
        if (Files.exists(publishedDir, LinkOption.NOFOLLOW_LINKS)) {
          try {
            Files.move(publishedDir, oldDir, StandardCopyOption.ATOMIC_MOVE);
            backedUp = true;
          } catch (IOException e) {
            throw new SkillGitException(
                "Failed to backup existing package directory: " + publishedDir, e);
          }
        }

        try {
          Files.move(stagingDir, publishedDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception moveError) {
          if (backedUp) {
            try {
              Files.move(oldDir, publishedDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception restoreError) {
              moveError.addSuppressed(restoreError);
            }
          }
          throw new SkillGitException(
              "Failed to publish skill cache directory for package " + packageName, moveError);
        }

        if (backedUp) {
          deleteRecursivelyQuietly(oldDir);
        }
      } catch (SkillGitException e) {
        throw e;
      } catch (Exception e) {
        throw new SkillGitException(
            "Failed to ensure commit "
                + commit
                + " for package "
                + packageName
                + ": "
                + e.getMessage(),
            e);
      } finally {
        deleteRecursivelyQuietly(cloneDir);
        deleteRecursivelyQuietly(stagingDir);
      }
    }
  }

  /** 仅在提供非空白令牌时构造 JGit 凭据；令牌只进入 HTTP authorization，绝不拼入 URL 或日志。 */
  private static CredentialsProvider credentialsProvider(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    return new UsernamePasswordCredentialsProvider("x-access-token", token);
  }

  /** 权限失败的稳定收敛：附带错误码但不含 URL、令牌或自由文本。 */
  private static SkillGitException authenticationFailed(Throwable cause) {
    return new SkillGitException(
        SkillGitException.CODE_AUTHENTICATION_FAILED, "repository authentication failed", cause);
  }

  @Override
  public List<SkillManifestEntry> scanManifest(String packageName, String commit) {
    validateCommit(commit);
    validatePackageName(packageName);

    Path publishedDir = resolvePublishedDir(packageName);
    Path commitMarker = publishedDir.resolve(".kkstudio-commit");
    if (!Files.isRegularFile(commitMarker, LinkOption.NOFOLLOW_LINKS)) {
      throw new SkillGitException(
          "Commit " + commit + " is not published for package " + packageName);
    }

    String publishedCommit;
    try {
      publishedCommit = Files.readString(commitMarker, StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new SkillGitException(
          "Failed to read commit marker for package " + packageName + ": " + e.getMessage(), e);
    }

    if (!commit.equals(publishedCommit)) {
      throw new SkillGitException(
          "Commit " + commit + " is not published for package " + packageName);
    }

    return SkillManifestScanner.scan(publishedDir, packageName);
  }

  @Override
  public byte[] readFile(String packageName, String commit, String path) {
    validateCommit(commit);
    validatePackageName(packageName);
    validatePath(path);

    Path publishedDir = resolvePublishedDir(packageName);
    Path commitMarker = publishedDir.resolve(".kkstudio-commit");
    if (!Files.isRegularFile(commitMarker, LinkOption.NOFOLLOW_LINKS)) {
      throw new SkillGitException(
          "Commit " + commit + " is not published for package " + packageName);
    }

    String publishedCommit;
    try {
      publishedCommit = Files.readString(commitMarker, StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new SkillGitException(
          "Failed to read commit marker for package " + packageName + ": " + e.getMessage(), e);
    }

    if (!commit.equals(publishedCommit)) {
      throw new SkillGitException(
          "Commit " + commit + " is not published for package " + packageName);
    }

    Path target = publishedDir.resolve(path).normalize();
    if (!target.startsWith(publishedDir) || target.equals(publishedDir)) {
      throw new SkillGitException("Path escapes package directory: " + path);
    }

    BasicFileAttributes attrs;
    try {
      attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      throw new SkillGitException(
          "File not found: " + path + " in package " + packageName + " commit " + commit, e);
    } catch (IOException e) {
      throw new SkillGitException(
          "Failed to read file attributes: " + path + " in package " + packageName, e);
    }

    if (!attrs.isRegularFile()) {
      throw new SkillGitException("Path '" + path + "' is not a regular file");
    }

    try {
      return Files.readAllBytes(target);
    } catch (IOException e) {
      throw new SkillGitException(
          "Failed to read file '"
              + path
              + "' in package "
              + packageName
              + " commit "
              + commit
              + ": "
              + e.getMessage(),
          e);
    }
  }

  private static void validateCommit(String commit) {
    if (commit == null || !COMMIT_PATTERN.matcher(commit).matches()) {
      throw new SkillGitException("Invalid commit format: " + commit);
    }
  }

  /** 直接入口同样 fail-closed：只接受受保护 HTTP(S) 或本地 file 仓库。 */
  private static void validateRepositoryUrl(String repositoryUrl) {
    if (repositoryUrl == null || repositoryUrl.isBlank()) {
      throw new SkillGitException("repositoryUrl must not be blank");
    }
    URI uri;
    try {
      uri = URI.create(repositoryUrl);
    } catch (IllegalArgumentException error) {
      throw new SkillGitException("repositoryUrl must be a valid URL without userinfo");
    }
    if (uri.getRawUserInfo() != null
        || (uri.getRawAuthority() != null && uri.getRawAuthority().contains("@"))) {
      throw new SkillGitException("repositoryUrl must not contain userinfo");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!SUPPORTED_REPOSITORY_SCHEMES.contains(scheme)) {
      throw new SkillGitException(
          "UNSUPPORTED_REPOSITORY_SCHEME: unsupported repository scheme: " + scheme);
    }
  }

  private static void validatePackageName(String packageName) {
    try {
      String canonical = SkillNames.canonicalPackageName(packageName);
      if (!packageName.equals(canonical)) {
        throw new SkillGitException("Package name '" + packageName + "' is not canonical");
      }
    } catch (IllegalArgumentException e) {
      throw new SkillGitException(
          "Invalid package name: " + packageName + ": " + e.getMessage(), e);
    }
  }

  private static void validatePath(String path) {
    if (path == null || path.isEmpty()) {
      throw new SkillGitException("Path must not be null or empty");
    }
    if (path.startsWith("/")) {
      throw new SkillGitException("Path must not start with '/': " + path);
    }
    if (path.contains("\\")) {
      throw new SkillGitException("Path must not contain backslashes: " + path);
    }
    if (path.codePoints().anyMatch(Character::isISOControl)) {
      throw new SkillGitException("Path must not contain control characters: " + path);
    }
    String[] segments = path.split("/", -1);
    for (String segment : segments) {
      if (segment.isEmpty()) {
        throw new SkillGitException("Path must not contain empty segments: " + path);
      }
      if (".".equals(segment) || "..".equals(segment)) {
        throw new SkillGitException("Path must not contain '.' or '..' segments: " + path);
      }
    }
  }

  private Path resolvePublishedDir(String packageName) {
    Path publishedDir = cacheRoot.resolve(packageName).normalize();
    if (!publishedDir.startsWith(cacheRoot) || publishedDir.equals(cacheRoot)) {
      throw new SkillGitException("Package path escapes cache root: " + packageName);
    }
    return publishedDir;
  }

  private static void deleteRecursivelyQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        return;
      }
      try (var stream = Files.walk(path)) {
        stream
            .sorted(Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException e) {
                    try {
                      p.toFile().setWritable(true);
                      Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                  }
                });
      }
    } catch (Exception ignored) {
    }
  }
}
