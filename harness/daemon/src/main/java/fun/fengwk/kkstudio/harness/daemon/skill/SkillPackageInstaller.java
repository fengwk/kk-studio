package fun.fengwk.kkstudio.harness.daemon.skill;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;

import fun.fengwk.kkstudio.harness.daemon.DaemonDataDirectory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 守护进程侧原子 Git skill 包安装器。
 *
 * <p>通过 bare Git 缓存仓库拉取、staging 临时目录物化并原子替换到 skills 目录，支持失败回滚与重启自愈。
 */
public final class SkillPackageInstaller {

  private static final Pattern COMMIT_PATTERN = Pattern.compile("^[0-9a-f]{40}$|^[0-9a-f]{64}$");

  private final Path skillsRoot;
  private final Path cacheRoot;
  private final Path stagingRoot;
  private final Path backupRoot;
  private final ConcurrentHashMap<String, Object> packageLocks = new ConcurrentHashMap<>();

  public SkillPackageInstaller(Path skillsRoot, Path cacheRoot, Path stagingRoot, Path backupRoot) {
    this.skillsRoot = Objects.requireNonNull(skillsRoot, "skillsRoot").toAbsolutePath().normalize();
    this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot").toAbsolutePath().normalize();
    this.stagingRoot =
        Objects.requireNonNull(stagingRoot, "stagingRoot").toAbsolutePath().normalize();
    this.backupRoot = Objects.requireNonNull(backupRoot, "backupRoot").toAbsolutePath().normalize();

    try {
      createOwnerOnlyDirectory(this.skillsRoot);
      createOwnerOnlyDirectory(this.cacheRoot);
      createOwnerOnlyDirectory(this.stagingRoot);
      createOwnerOnlyDirectory(this.backupRoot);
    } catch (IOException error) {
      throw new UncheckedIOException("failed to create skill directory roots", error);
    }

    assertRealDirectory(this.skillsRoot, "skillsRoot");
    assertRealDirectory(this.cacheRoot, "cacheRoot");
    assertRealDirectory(this.stagingRoot, "stagingRoot");
    assertRealDirectory(this.backupRoot, "backupRoot");

    recoverArtifacts();
  }

  public static SkillPackageInstaller open(DaemonDataDirectory dataDirectory) {
    Objects.requireNonNull(dataDirectory, "dataDirectory");
    return new SkillPackageInstaller(
        dataDirectory.skills(),
        dataDirectory.skillCache(),
        dataDirectory.skillStaging(),
        dataDirectory.skillBackup());
  }

  public Path skillsRoot() {
    return skillsRoot;
  }

  public Path cacheRoot() {
    return cacheRoot;
  }

  public Path stagingRoot() {
    return stagingRoot;
  }

  public Path backupRoot() {
    return backupRoot;
  }

  public InstalledSkillPackage install(
      String packageName, String repositoryUrl, String branch, String targetCommit) {
    validatePackageName(packageName);
    validateTargetCommit(targetCommit);
    validateRepositoryUrl(repositoryUrl);
    validateBranch(branch);

    Object lock = packageLocks.computeIfAbsent(packageName, k -> new Object());
    synchronized (lock) {
      return doInstall(packageName, repositoryUrl, branch, targetCommit);
    }
  }

  private InstalledSkillPackage doInstall(
      String packageName, String repositoryUrl, String branch, String targetCommit) {
    Path cacheGitDir = cacheRoot.resolve(packageName + ".git");
    prepareCache(cacheGitDir, repositoryUrl);

    try (Repository repo =
        new FileRepositoryBuilder().setGitDir(cacheGitDir.toFile()).setMustExist(true).build()) {
      ObjectId commitId = ObjectId.fromString(targetCommit);
      if (!repo.getObjectDatabase().has(commitId)) {
        try (Git git = new Git(repo)) {
          boolean fetched = false;
          try {
            git.fetch().setRemote(repositoryUrl).setRefSpecs(new RefSpec(targetCommit)).call();
            fetched = repo.getObjectDatabase().has(commitId);
          } catch (Exception ignored) {
            // 回退到按分支拉取
          }
          if (!fetched) {
            try {
              git.fetch()
                  .setRemote(repositoryUrl)
                  .setRefSpecs(
                      new RefSpec("+refs/heads/" + branch + ":refs/remotes/origin/" + branch))
                  .call();
            } catch (Exception ignored) {
              // 在下方统一检查 commitId 是否存在
            }
          }
        }
      }

      if (!repo.getObjectDatabase().has(commitId)) {
        throw new SkillSyncException(
            "COMMIT_NOT_FOUND", "Target commit could not be resolved from remote repository");
      }

      RevCommit revCommit;
      try (RevWalk revWalk = new RevWalk(repo)) {
        revCommit = revWalk.parseCommit(commitId);
      } catch (Exception error) {
        throw new SkillSyncException(
            "COMMIT_NOT_FOUND", "Target commit could not be parsed as a commit", error);
      }

      String uuid = UUID.randomUUID().toString();
      Path stagingDir = stagingRoot.resolve(packageName + "." + uuid);
      Path backupDir = backupRoot.resolve(packageName + "." + uuid);
      Path packageDir = skillsRoot.resolve(packageName);
      boolean backedUp = false;

      try {
        createOwnerOnlyDirectory(stagingDir);

        try (TreeWalk treeWalk = new TreeWalk(repo)) {
          treeWalk.addTree(revCommit.getTree());
          treeWalk.setRecursive(true);
          while (treeWalk.next()) {
            FileMode fileMode = treeWalk.getFileMode(0);
            if (fileMode.equals(FileMode.SYMLINK)) {
              throw new SkillSyncException(
                  "UNSAFE_PACKAGE_ENTRY", "Symbolic links are not allowed in skill package");
            }
            if (fileMode.equals(FileMode.GITLINK)) {
              throw new SkillSyncException(
                  "UNSAFE_PACKAGE_ENTRY", "Submodules are not allowed in skill package");
            }
            if (!fileMode.equals(FileMode.REGULAR_FILE)
                && !fileMode.equals(FileMode.EXECUTABLE_FILE)) {
              throw new SkillSyncException(
                  "UNSAFE_PACKAGE_ENTRY", "Unsupported file mode in skill package");
            }

            String pathString = treeWalk.getPathString();
            validatePackageEntryPath(pathString);

            Path targetFile = stagingDir.resolve(pathString).normalize();
            if (!targetFile.startsWith(stagingDir.normalize())
                || targetFile.equals(stagingDir.normalize())) {
              throw new SkillSyncException(
                  "UNSAFE_PACKAGE_ENTRY", "Path traversal detected in package entry");
            }

            Path parent = targetFile.getParent();
            if (parent != null && !parent.equals(stagingDir)) {
              createParentDirectoriesOwnerOnly(stagingDir, parent);
            }

            ObjectId blobId = treeWalk.getObjectId(0);
            ObjectLoader loader = repo.open(blobId, Constants.OBJ_BLOB);
            writeFileOwnerOnly(targetFile, loader, fileMode.equals(FileMode.EXECUTABLE_FILE));
          }
        }

        Path commitFile = stagingDir.resolve(".kkstudio-commit");
        Files.deleteIfExists(commitFile);
        Files.writeString(
            commitFile,
            targetCommit + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
        applyOwnerOnlyFilePermissions(commitFile, false);

        if (Files.exists(packageDir, LinkOption.NOFOLLOW_LINKS)) {
          moveDirectory(packageDir, backupDir);
          backedUp = true;
        }

        try {
          moveDirectory(stagingDir, packageDir);
        } catch (Exception moveError) {
          if (backedUp) {
            try {
              moveDirectory(backupDir, packageDir);
            } catch (Exception rollbackError) {
              moveError.addSuppressed(rollbackError);
            }
          }
          throw new SkillSyncException(
              "INSTALL_FAILED", "Failed to swap installed package directory", moveError);
        }

        if (backedUp) {
          deleteRecursivelyQuietly(backupDir);
        }

        return new InstalledSkillPackage(
            packageName, targetCommit, packageDir.toAbsolutePath().normalize().toString());
      } catch (SkillSyncException error) {
        throw error;
      } catch (Exception error) {
        throw new SkillSyncException("INSTALL_FAILED", "Skill package installation failed", error);
      } finally {
        deleteRecursivelyQuietly(stagingDir);
      }
    } catch (SkillSyncException error) {
      throw error;
    } catch (Exception error) {
      throw new SkillSyncException("INSTALL_FAILED", "Skill package installation failed", error);
    }
  }

  /**
   * 复用或重建该 Package 的 bare cache。
   *
   * <p>cache 的 provenance 只由 origin URL 表达，因此它是唯一可信依据：Package 删除后以同名重建可能指向完全不同的仓库，旧对象对新仓库毫无 意义（同名
   * commit 可能来自另一个仓库的历史），所以 origin URL 不一致或不可读时整份丢弃重建，绝不复用既有对象。
   */
  private void prepareCache(Path cacheGitDir, String repositoryUrl) {
    if (Files.exists(cacheGitDir, LinkOption.NOFOLLOW_LINKS)) {
      if (repositoryUrl.equals(readOriginUrl(cacheGitDir))) {
        return;
      }
      deleteRecursivelyQuietly(cacheGitDir);
      if (Files.exists(cacheGitDir, LinkOption.NOFOLLOW_LINKS)) {
        throw new SkillSyncException(
            "GIT_CACHE_RESET_FAILED", "Failed to discard the stale skill cache repository");
      }
    }
    try {
      try (Git git = Git.init().setBare(true).setDirectory(cacheGitDir.toFile()).call()) {
        StoredConfig config = git.getRepository().getConfig();
        config.setString("remote", "origin", "url", repositoryUrl);
        config.save();
      }
    } catch (Exception error) {
      throw new SkillSyncException(
          "GIT_INIT_FAILED", "Failed to initialize bare cache repository", error);
    }
  }

  /** 读取 bare cache 的 origin URL；缺失、损坏或不可读时返回 null，调用方据此丢弃重建。 */
  private static String readOriginUrl(Path cacheGitDir) {
    try (Repository repo =
        new FileRepositoryBuilder().setGitDir(cacheGitDir.toFile()).setMustExist(true).build()) {
      return repo.getConfig().getString("remote", "origin", "url");
    } catch (Exception error) {
      return null;
    }
  }

  public void recoverArtifacts() {
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(stagingRoot)) {
      for (Path entry : entries) {
        deleteRecursivelyQuietly(entry);
      }
    } catch (IOException error) {
      throw new UncheckedIOException("failed to clean staging root during recovery", error);
    }

    try (DirectoryStream<Path> entries = Files.newDirectoryStream(backupRoot)) {
      for (Path entry : entries) {
        String fileName = entry.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0) {
          deleteRecursivelyQuietly(entry);
          continue;
        }
        String pkgName = fileName.substring(0, lastDot);
        String uuidPart = fileName.substring(lastDot + 1);
        if (!isValidUuid(uuidPart) || !isValidPackageNameQuietly(pkgName)) {
          deleteRecursivelyQuietly(entry);
          continue;
        }
        Path target = skillsRoot.resolve(pkgName);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
          try {
            moveDirectory(entry, target);
          } catch (IOException moveError) {
            deleteRecursivelyQuietly(entry);
          }
        } else {
          deleteRecursivelyQuietly(entry);
        }
      }
    } catch (IOException error) {
      throw new UncheckedIOException("failed to recover backup root during recovery", error);
    }
  }

  private static void validatePackageName(String packageName) {
    if (packageName == null || packageName.isBlank()) {
      throw new SkillSyncException("INVALID_PACKAGE_NAME", "Package name must not be blank");
    }
    if (packageName.length() > 128) {
      throw new SkillSyncException(
          "INVALID_PACKAGE_NAME", "Package name must not exceed 128 characters");
    }
    if (!packageName.equals(packageName.strip())) {
      throw new SkillSyncException(
          "INVALID_PACKAGE_NAME", "Package name must not contain leading or trailing whitespace");
    }
    for (int index = 0; index < packageName.length(); index++) {
      char ch = packageName.charAt(index);
      if (Character.isISOControl(ch)) {
        throw new SkillSyncException(
            "INVALID_PACKAGE_NAME", "Package name must not contain control characters");
      }
      if (ch == ':' || ch == '/' || ch == '@' || ch == '\\') {
        throw new SkillSyncException(
            "INVALID_PACKAGE_NAME", "Package name contains invalid characters");
      }
    }
    if (".".equals(packageName) || "..".equals(packageName)) {
      throw new SkillSyncException("INVALID_PACKAGE_NAME", "Package name must not be . or ..");
    }
  }

  private static void validateTargetCommit(String targetCommit) {
    if (targetCommit == null || !COMMIT_PATTERN.matcher(targetCommit).matches()) {
      throw new SkillSyncException(
          "INVALID_TARGET_COMMIT", "Target commit must be 40 or 64 lowercase hex characters");
    }
  }

  private static void validateRepositoryUrl(String repositoryUrl) {
    if (repositoryUrl == null || repositoryUrl.isBlank()) {
      throw new SkillSyncException("INVALID_REPOSITORY_URL", "Repository URL must not be blank");
    }
    if (repositoryUrl.length() > 2048) {
      throw new SkillSyncException(
          "INVALID_REPOSITORY_URL", "Repository URL must not exceed 2048 characters");
    }
    URI uri;
    try {
      uri = URI.create(repositoryUrl);
    } catch (IllegalArgumentException error) {
      throw new SkillSyncException("INVALID_REPOSITORY_URL", "Repository URL is malformed");
    }
    if (!uri.isAbsolute() || uri.getScheme() == null || uri.getScheme().isBlank()) {
      throw new SkillSyncException(
          "INVALID_REPOSITORY_URL", "Repository URL must be an absolute URI with scheme");
    }
    if (uri.getUserInfo() != null || uri.getRawUserInfo() != null) {
      throw new SkillSyncException(
          "INVALID_REPOSITORY_URL", "Repository URL must not contain user info");
    }
    String rawAuthority = uri.getRawAuthority();
    if (rawAuthority != null && rawAuthority.contains("@")) {
      throw new SkillSyncException(
          "INVALID_REPOSITORY_URL", "Repository URL must not contain user info");
    }
  }

  private static void validateBranch(String branch) {
    if (branch == null || branch.isBlank()) {
      throw new SkillSyncException("INVALID_BRANCH", "Branch must not be blank");
    }
    if (branch.length() > 255) {
      throw new SkillSyncException("INVALID_BRANCH", "Branch must not exceed 255 characters");
    }
    if (!branch.equals(branch.strip())) {
      throw new SkillSyncException(
          "INVALID_BRANCH", "Branch must not contain leading or trailing whitespace");
    }
    for (int index = 0; index < branch.length(); index++) {
      if (Character.isISOControl(branch.charAt(index))) {
        throw new SkillSyncException(
            "INVALID_BRANCH", "Branch must not contain control characters");
      }
    }
  }

  private static void validatePackageEntryPath(String pathString) {
    if (pathString == null || pathString.isBlank()) {
      throw new SkillSyncException("UNSAFE_PACKAGE_ENTRY", "Package entry path must not be blank");
    }
    if (pathString.startsWith("/")
        || pathString.startsWith("\\")
        || Path.of(pathString).isAbsolute()) {
      throw new SkillSyncException(
          "UNSAFE_PACKAGE_ENTRY", "Package entry path must not be absolute");
    }
    String[] segments = pathString.split("[/\\\\]");
    for (String segment : segments) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new SkillSyncException(
            "UNSAFE_PACKAGE_ENTRY", "Package entry path contains invalid segment");
      }
    }
  }

  private static void createParentDirectoriesOwnerOnly(Path root, Path target) throws IOException {
    Path relative = root.relativize(target);
    Path current = root;
    for (Path part : relative) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current)) {
        throw new SkillSyncException("UNSAFE_PACKAGE_ENTRY", "Symbolic link encountered in path");
      }
      if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        Files.createDirectory(current);
        applyOwnerOnlyDirectoryPermissions(current);
      } else if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
        throw new SkillSyncException("UNSAFE_PACKAGE_ENTRY", "Path element is not a directory");
      }
    }
  }

  private static void writeFileOwnerOnly(Path targetFile, ObjectLoader loader, boolean executable)
      throws IOException {
    if (Files.exists(targetFile, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(targetFile)) {
      throw new SkillSyncException(
          "UNSAFE_PACKAGE_ENTRY", "Target file already exists or is a symbolic link");
    }
    try (OutputStream out =
        Files.newOutputStream(
            targetFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      loader.copyTo(out);
    }
    applyOwnerOnlyFilePermissions(targetFile, executable);
  }

  private static Path createOwnerOnlyDirectory(Path directory) throws IOException {
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectories(directory);
    }
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(directory)) {
      throw new IllegalArgumentException("directory path must be a real directory: " + directory);
    }
    applyOwnerOnlyDirectoryPermissions(directory);
    return directory;
  }

  private static void applyOwnerOnlyDirectoryPermissions(Path directory) throws IOException {
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(
          directory,
          Set.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE));
      return;
    }
    File file = directory.toFile();
    file.setReadable(false, false);
    file.setReadable(true, true);
    file.setWritable(false, false);
    file.setWritable(true, true);
    file.setExecutable(false, false);
    file.setExecutable(true, true);
  }

  private static void applyOwnerOnlyFilePermissions(Path file, boolean executable)
      throws IOException {
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Set<PosixFilePermission> permissions =
          executable
              ? Set.of(
                  PosixFilePermission.OWNER_READ,
                  PosixFilePermission.OWNER_WRITE,
                  PosixFilePermission.OWNER_EXECUTE)
              : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
      Files.setPosixFilePermissions(file, permissions);
      return;
    }
    File target = file.toFile();
    target.setReadable(false, false);
    target.setReadable(true, true);
    target.setWritable(false, false);
    target.setWritable(true, true);
    if (executable) {
      target.setExecutable(false, false);
      target.setExecutable(true, true);
    }
  }

  private static void assertRealDirectory(Path dir, String name) {
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException(name + " does not exist: " + dir);
    }
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dir)) {
      throw new IllegalArgumentException(name + " must be a real non-symlink directory: " + dir);
    }
  }

  private static void moveDirectory(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException error) {
      Files.move(source, target);
    }
  }

  private static void deleteRecursivelyQuietly(Path path) {
    if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try {
      deleteRecursively(path);
    } catch (Exception ignored) {
    }
  }

  private static void deleteRecursively(Path path) throws IOException {
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
        for (Path entry : entries) {
          deleteRecursively(entry);
        }
      }
    }
    Files.deleteIfExists(path);
  }

  private static boolean isValidPackageNameQuietly(String name) {
    if (name == null || name.isBlank() || name.length() > 128 || !name.equals(name.strip())) {
      return false;
    }
    if (".".equals(name) || "..".equals(name)) {
      return false;
    }
    for (int index = 0; index < name.length(); index++) {
      char ch = name.charAt(index);
      if (Character.isISOControl(ch) || ch == ':' || ch == '/' || ch == '@' || ch == '\\') {
        return false;
      }
    }
    return true;
  }

  private static boolean isValidUuid(String text) {
    if (text == null || text.length() != 36) {
      return false;
    }
    try {
      UUID.fromString(text);
      return true;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }
}
