package fun.fengwk.kkstudio.platform.catalog.skill.git;

import lombok.extern.slf4j.Slf4j;
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
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.context.annotation.DependsOn;

import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * JGit 实现：branch HEAD 用 {@code ls-remote} 观察，发布用 depth 1 的 exact commit 浅拉取，直接遍历对象树。
 *
 * <p>临时 bare repository 每次发布独立创建并在 finally 中删除，因此 Studio 不保留任何本地技能内容；读取对象前先用对象元信息检查上限，避免先无界加载。
 * 符号链接、子模块与特殊文件在对象树阶段直接拒绝，并给出具体路径。
 */
@Slf4j
@DependsOn("systemProxySelector")
public class JGitSkillRepository implements SkillGitRepository {

  private static final Pattern COMMIT_PATTERN = Pattern.compile("^([0-9a-f]{40}|[0-9a-f]{64})$");

  private static final Set<String> SUPPORTED_REPOSITORY_SCHEMES = Set.of("http", "https", "file");

  private final int connectMillis;
  private final int readMillis;

  public JGitSkillRepository() {
    this(GitHttpConnectionFactory.CONNECT_MILLIS, GitHttpConnectionFactory.READ_MILLIS);
  }

  JGitSkillRepository(int connectMillis, int readMillis) {
    if (connectMillis <= 0 || readMillis <= 0) {
      throw new IllegalArgumentException("Git network timeouts must be positive");
    }
    this.connectMillis = connectMillis;
    this.readMillis = readMillis;
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
  public SkillArchiveBuilder.Result buildArchive(
      String repositoryUrl, String commit, String token) {
    validateRepositoryUrl(repositoryUrl);
    validateCommit(commit);

    Path work = null;
    try {
      work = Files.createTempDirectory("kkstudio-skill-");
      try (Git git = Git.init().setDirectory(work.toFile()).setBare(true).call()) {
        fetchCommit(git, repositoryUrl, commit, token);
        Repository repository = git.getRepository();
        ObjectId commitId = ObjectId.fromString(commit);
        if (!repository.getObjectDatabase().has(commitId)) {
          throw new SkillGitException("Commit " + commit + " does not exist in repository");
        }
        try (RevWalk revWalk = new RevWalk(repository)) {
          RevCommit revCommit = parseCommit(revWalk, commitId, commit);
          List<SkillArchiveBuilder.InputEntry> entries = readTree(repository, revCommit);
          return new SkillArchiveBuilder().fromEntries(entries);
        }
      }
    } catch (SkillGitException e) {
      throw e;
    } catch (Exception e) {
      if (GitHttpConnectionFactory.isAuthenticationFailure(e)) {
        throw authenticationFailed(e);
      }
      throw new SkillGitException(
          GitHttpConnectionFactory.failureCode(e)
              + ": Failed to build skill archive for "
              + repositoryUrl
              + " commit "
              + commit
              + ": "
              + e.getMessage(),
          e);
    } finally {
      deleteRecursively(work);
    }
  }

  /** 每次操作独立的 HTTP 保护：连接/读取超时与关闭断连由 factory 绑定的 transport 生效。 */
  private void fetchCommit(Git git, String repositoryUrl, String commit, String token) {
    CredentialsProvider credentials = credentialsProvider(token);
    try (GitHttpConnectionFactory network =
        new GitHttpConnectionFactory(connectMillis, readMillis)) {
      var fetch =
          git.fetch()
              .setRemote(repositoryUrl)
              .setRefSpecs(new RefSpec(commit + ":refs/kkstudio/observed"))
              .setDepth(1)
              .setTransportConfigCallback(network.callback());
      if (credentials != null) {
        fetch.setCredentialsProvider(credentials);
      }
      fetch.call();
    } catch (SkillGitException e) {
      throw e;
    } catch (Exception e) {
      if (GitHttpConnectionFactory.isAuthenticationFailure(e)) {
        throw authenticationFailed(e);
      }
      throw new SkillGitException(
          GitHttpConnectionFactory.failureCode(e)
              + ": Failed to fetch commit "
              + commit
              + ": "
              + e.getMessage(),
          e);
    }
  }

  private static RevCommit parseCommit(RevWalk revWalk, ObjectId commitId, String commit) {
    try {
      return revWalk.parseCommit(commitId);
    } catch (Exception e) {
      throw new SkillGitException(
          "Invalid commit object " + commit + " in repository: " + e.getMessage(), e);
    }
  }

  /**
   * 递归遍历对象树，把普通文件与可执行文件读成归档输入。
   *
   * <p>每个 blob 在打开之前先按对象元信息检查单文件与累计上限；符号链接、子模块与特殊文件直接拒绝并给出路径。
   */
  private static List<SkillArchiveBuilder.InputEntry> readTree(
      Repository repository, RevCommit revCommit) throws IOException {
    List<SkillArchiveBuilder.InputEntry> entries = new ArrayList<>();
    long total = 0L;
    int paths = 0;
    try (TreeWalk treeWalk = new TreeWalk(repository)) {
      treeWalk.addTree(revCommit.getTree());
      treeWalk.setRecursive(true);
      while (treeWalk.next()) {
        String path = treeWalk.getPathString();
        if (path.codePoints().anyMatch(Character::isISOControl)) {
          throw new SkillGitException("Entry path contains control characters: " + path);
        }
        if (++paths > SkillArchiveBuilder.MAX_PATHS) {
          throw new SkillGitException("Repository has too many paths: " + path);
        }
        FileMode mode = treeWalk.getFileMode(0);
        if (mode == FileMode.GITLINK) {
          throw new SkillGitException("Forbidden submodule entry: " + path);
        }
        if (mode == FileMode.SYMLINK) {
          throw new SkillGitException("Forbidden symbolic link entry: " + path);
        }
        if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) {
          throw new SkillGitException("Forbidden file mode " + mode + " for entry: " + path);
        }
        ObjectId blobId = treeWalk.getObjectId(0);
        ObjectLoader loader = repository.open(blobId, Constants.OBJ_BLOB);
        long size = loader.getSize();
        if (size > SkillArchiveBuilder.MAX_FILE_BYTES) {
          throw new SkillGitException("File exceeds the size limit: " + path);
        }
        if ((total += size) > SkillArchiveBuilder.MAX_TOTAL_BYTES) {
          throw new SkillGitException("Repository content exceeds the size limit: " + path);
        }
        entries.add(
            new SkillArchiveBuilder.InputEntry(
                path,
                SkillArchiveBuilder.Type.FILE,
                mode == FileMode.EXECUTABLE_FILE,
                loader.getBytes()));
      }
    }
    entries.sort(Comparator.comparing(SkillArchiveBuilder.InputEntry::path));
    return entries;
  }

  private static void validateCommit(String commit) {
    if (commit == null || !COMMIT_PATTERN.matcher(commit).matches()) {
      throw new SkillGitException("commit must be a 40 or 64 character lowercase hex object id");
    }
  }

  private static void validateRepositoryUrl(String repositoryUrl) {
    if (repositoryUrl == null) {
      throw new SkillGitException("repositoryUrl must not be null");
    }
    int schemeSeparator = repositoryUrl.indexOf("://");
    if (schemeSeparator <= 0) {
      throw new SkillGitException("repositoryUrl must include a scheme");
    }
    String scheme = repositoryUrl.substring(0, schemeSeparator).toLowerCase(Locale.ROOT);
    String authority = repositoryUrl.substring(schemeSeparator + 3);
    int authorityEnd = authority.indexOf('/');
    String userInfo = authorityEnd < 0 ? authority : authority.substring(0, authorityEnd);
    if (!SUPPORTED_REPOSITORY_SCHEMES.contains(scheme)
        || repositoryUrl.codePoints().anyMatch(Character::isISOControl)
        || userInfo.contains("@")) {
      throw new SkillGitException("unsupported repository URL");
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

  private static void deleteRecursively(Path root) {
    if (root == null) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException error) {
                  log.warn("failed to delete temporary git path {}", path);
                }
              });
    } catch (IOException error) {
      log.warn("failed to delete temporary git repository {}", root);
    }
  }
}
