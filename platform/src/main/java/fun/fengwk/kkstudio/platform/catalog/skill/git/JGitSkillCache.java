package fun.fengwk.kkstudio.platform.catalog.skill.git;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;

import fun.fengwk.kkstudio.harness.common.skill.SkillNames;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Platform 拥有的 Git Skill bare cache 默认实现。
 *
 * <p>底层在 {@code <cacheRoot>/<packageName>.git} 维护 bare repository，严格按 exact commit 补齐与读取对象。
 */
@Slf4j
public class JGitSkillCache implements SkillGitCache {

  private static final Pattern COMMIT_PATTERN = Pattern.compile("^([0-9a-f]{40}|[0-9a-f]{64})$");

  private final Path cacheRoot;
  private final ConcurrentMap<String, Object> packageLocks = new ConcurrentHashMap<>();

  /**
   * 构造 JGitSkillCache，若 cacheRoot 目录不存在则自动创建。
   *
   * @param cacheRoot bare 仓库的根缓存目录
   */
  public JGitSkillCache(Path cacheRoot) {
    Objects.requireNonNull(cacheRoot, "cacheRoot");
    this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
    try {
      Files.createDirectories(this.cacheRoot);
    } catch (IOException e) {
      throw new SkillGitException("Failed to create cache root directory: " + this.cacheRoot, e);
    }
  }

  @Override
  public String resolveBranchHead(String repositoryUrl, String branch) {
    if (repositoryUrl == null || repositoryUrl.isBlank()) {
      throw new SkillGitException("repositoryUrl must not be blank");
    }
    if (branch == null || branch.isBlank()) {
      throw new SkillGitException("branch must not be blank");
    }
    if (branch.codePoints().anyMatch(Character::isISOControl)) {
      throw new SkillGitException("branch must not contain control characters");
    }

    String targetRefName = "refs/heads/" + branch;
    try {
      Map<String, Ref> refMap = Git.lsRemoteRepository().setRemote(repositoryUrl).callAsMap();

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
      throw new SkillGitException(
          "Failed to resolve branch head for "
              + repositoryUrl
              + " branch "
              + branch
              + ": "
              + e.getMessage(),
          e);
    }
  }

  @Override
  public void ensureCommit(String packageName, String repositoryUrl, String commit) {
    validateCommit(commit);
    validatePackageName(packageName);
    if (repositoryUrl == null || repositoryUrl.isBlank()) {
      throw new SkillGitException("repositoryUrl must not be blank");
    }

    Object lock = packageLocks.computeIfAbsent(packageName, k -> new Object());
    synchronized (lock) {
      Path repoPath = resolveRepoPath(packageName);
      File repoDir = repoPath.toFile();

      if (!Files.exists(repoPath)) {
        try {
          Files.createDirectories(repoPath.getParent());
          try (Git git = Git.init().setBare(true).setDirectory(repoDir).call()) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", repositoryUrl);
            config.save();
          }
        } catch (Exception e) {
          throw new SkillGitException(
              "Failed to initialize bare repository for package "
                  + packageName
                  + ": "
                  + e.getMessage(),
              e);
        }
      }

      try (Repository repo =
          new FileRepositoryBuilder().setGitDir(repoDir).setMustExist(true).build()) {
        ObjectId commitId = ObjectId.fromString(commit);
        if (repo.getObjectDatabase().has(commitId)) {
          // commit 已存在时必须是 no-op（不产生额外网络/fetch 副作用）
          return;
        }

        try (Git git = new Git(repo)) {
          boolean fetched = false;
          try {
            git.fetch().setRemote(repositoryUrl).setRefSpecs(new RefSpec(commit)).call();
            if (repo.getObjectDatabase().has(commitId)) {
              fetched = true;
            }
          } catch (Exception e) {
            log.debug(
                "Direct commit fetch failed for {}, falling back to branch fetch: {}",
                commit,
                e.getMessage());
          }

          if (!fetched) {
            try {
              git.fetch()
                  .setRemote(repositoryUrl)
                  .setRefSpecs(new RefSpec("+refs/heads/*:refs/remotes/origin/*"))
                  .call();
            } catch (Exception e) {
              throw new SkillGitException(
                  "Failed to fetch branches from " + repositoryUrl + ": " + e.getMessage(), e);
            }
          }

          if (!repo.getObjectDatabase().has(commitId)) {
            throw new SkillGitException(
                "Commit " + commit + " does not exist in repository " + repositoryUrl);
          }
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
      }
    }
  }

  @Override
  public List<SkillManifestEntry> scanManifest(String packageName, String commit) {
    validateCommit(commit);
    validatePackageName(packageName);

    Path repoPath = resolveRepoPath(packageName);
    if (!Files.isDirectory(repoPath)) {
      throw new SkillGitException("Cache repository not found for package: " + packageName);
    }

    try (Repository repo =
        new FileRepositoryBuilder().setGitDir(repoPath.toFile()).setMustExist(true).build()) {
      ObjectId commitId = ObjectId.fromString(commit);
      if (!repo.getObjectDatabase().has(commitId)) {
        throw new SkillGitException(
            "Commit " + commit + " does not exist in cache repository for package " + packageName);
      }
      return SkillManifestScanner.scan(repo, commitId, packageName);
    } catch (SkillGitException e) {
      throw e;
    } catch (Exception e) {
      throw new SkillGitException(
          "Failed to scan manifest for package "
              + packageName
              + " commit "
              + commit
              + ": "
              + e.getMessage(),
          e);
    }
  }

  @Override
  public byte[] readFile(String packageName, String commit, String path) {
    validateCommit(commit);
    validatePackageName(packageName);
    validatePath(path);

    Path repoPath = resolveRepoPath(packageName);
    if (!Files.isDirectory(repoPath)) {
      throw new SkillGitException("Cache repository not found for package: " + packageName);
    }

    try (Repository repo =
        new FileRepositoryBuilder().setGitDir(repoPath.toFile()).setMustExist(true).build()) {
      ObjectId commitId = ObjectId.fromString(commit);
      if (!repo.getObjectDatabase().has(commitId)) {
        throw new SkillGitException(
            "Commit " + commit + " does not exist in cache repository for package " + packageName);
      }

      RevCommit revCommit;
      try (RevWalk revWalk = new RevWalk(repo)) {
        revCommit = revWalk.parseCommit(commitId);
      } catch (Exception e) {
        throw new SkillGitException(
            "Invalid commit object "
                + commit
                + " in package "
                + packageName
                + ": "
                + e.getMessage(),
            e);
      }

      try (TreeWalk treeWalk = TreeWalk.forPath(repo, path, revCommit.getTree())) {
        if (treeWalk == null) {
          throw new SkillGitException(
              "File not found: " + path + " in package " + packageName + " commit " + commit);
        }
        FileMode fileMode = treeWalk.getFileMode(0);
        if (fileMode != FileMode.REGULAR_FILE && fileMode != FileMode.EXECUTABLE_FILE) {
          throw new SkillGitException(
              "Path '" + path + "' is not a regular file (mode=" + fileMode + ")");
        }
        ObjectId blobId = treeWalk.getObjectId(0);
        ObjectLoader loader = repo.open(blobId, Constants.OBJ_BLOB);
        return loader.getBytes();
      }
    } catch (SkillGitException e) {
      throw e;
    } catch (Exception e) {
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

  private Path resolveRepoPath(String packageName) {
    Path repoPath = cacheRoot.resolve(packageName + ".git").normalize();
    if (!repoPath.startsWith(cacheRoot)) {
      throw new SkillGitException("Package path escapes cache root: " + packageName);
    }
    return repoPath;
  }
}
