package fun.fengwk.kkstudio.harness.daemon.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** {@link SkillPackageInstaller} 的原子安装、回滚、自愈与安全隔离测试。 */
class SkillPackageInstallerTest {

  @TempDir Path tempDir;

  /** Git 取消观察使用测试自有的 executor；用完即关闭，模拟 runtime 生命周期。 */
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  private Path remoteRepoDir;
  private Path skillsRoot;
  private Path stagingRoot;
  private Path backupRoot;
  private SkillPackageInstaller installer;

  @BeforeEach
  void setUp() {
    remoteRepoDir = tempDir.resolve("remote-repo");
    skillsRoot = tempDir.resolve("skills");
    stagingRoot = tempDir.resolve("staging");
    backupRoot = tempDir.resolve("backup");
    installer = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, executor);
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  /** 验证按精确 commit 安装能物化包含 SKILL.md 的目录树，写入 .kkstudio-commit 元数据，并返回规范化本地路径。 */
  @Test
  void exactCommitInstallProducesExpectedTreeAndMetadata() throws Exception {
    String commitId;
    try (Git git = Git.init().setDirectory(remoteRepoDir.toFile()).call()) {
      Path skillDir = remoteRepoDir.resolve("weather-skill");
      Files.createDirectories(skillDir);
      Files.writeString(skillDir.resolve("SKILL.md"), "# Weather Skill\nVersion 1.0\n");
      git.add().addFilepattern(".").call();
      RevCommit commit = git.commit().setMessage("initial commit").call();
      commitId = commit.getId().name();
    }

    String remoteUrl = remoteRepoDir.toUri().toString();
    InstalledSkillPackage installed =
        installer.install("weather-package", remoteUrl, "master", commitId);

    assertEquals("weather-package", installed.packageName());
    assertEquals(commitId, installed.installedCommit());
    Path expectedPackageRoot = skillsRoot.resolve("weather-package");
    assertEquals(expectedPackageRoot.toString(), installed.localPath());

    Path skillFile = expectedPackageRoot.resolve("weather-skill/SKILL.md");
    assertTrue(Files.isRegularFile(skillFile));
    assertEquals("# Weather Skill\nVersion 1.0\n", Files.readString(skillFile));

    Path commitFile = expectedPackageRoot.resolve(".kkstudio-commit");
    assertTrue(Files.isRegularFile(commitFile));
    assertEquals(commitId + "\n", Files.readString(commitFile));
  }

  /** 验证安装新 commit 会原子替换已有安装树，旧文件被清除且新文件正确存在。 */
  @Test
  void installingNewerCommitReplacesExistingTree() throws Exception {
    String commit1Id;
    String commit2Id;
    try (Git git = Git.init().setDirectory(remoteRepoDir.toFile()).call()) {
      Path skillDir1 = remoteRepoDir.resolve("skill-one");
      Files.createDirectories(skillDir1);
      Files.writeString(skillDir1.resolve("SKILL.md"), "# Skill One\n");
      git.add().addFilepattern(".").call();
      RevCommit commit1 = git.commit().setMessage("commit 1").call();
      commit1Id = commit1.getId().name();

      Files.delete(skillDir1.resolve("SKILL.md"));
      Files.delete(skillDir1);

      Path skillDir2 = remoteRepoDir.resolve("skill-two");
      Files.createDirectories(skillDir2);
      Files.writeString(skillDir2.resolve("SKILL.md"), "# Skill Two\n");
      git.add().addFilepattern(".").call();
      RevCommit commit2 = git.commit().setMessage("commit 2").call();
      commit2Id = commit2.getId().name();
    }

    String remoteUrl = remoteRepoDir.toUri().toString();
    installer.install("replace-pkg", remoteUrl, "master", commit1Id);
    Path pkgDir = skillsRoot.resolve("replace-pkg");
    assertTrue(Files.exists(pkgDir.resolve("skill-one/SKILL.md")));

    InstalledSkillPackage installed2 =
        installer.install("replace-pkg", remoteUrl, "master", commit2Id);
    assertEquals("replace-pkg", installed2.packageName());
    assertEquals(commit2Id, installed2.installedCommit());

    assertFalse(Files.exists(pkgDir.resolve("skill-one/SKILL.md")));
    assertTrue(Files.exists(pkgDir.resolve("skill-two/SKILL.md")));
    assertEquals("# Skill Two\n", Files.readString(pkgDir.resolve("skill-two/SKILL.md")));
    assertEquals(commit2Id + "\n", Files.readString(pkgDir.resolve(".kkstudio-commit")));
  }

  /** 验证含有符号链接的 commit 会被安全拒绝，且原有已安装包保持完整可用。 */
  @Test
  void commitWithSymlinkIsRejectedAndPreservesPreviousInstall() throws Exception {
    String validCommitId;
    String symlinkCommitId;
    try (Git git = Git.init().setDirectory(remoteRepoDir.toFile()).call()) {
      Path skillDir = remoteRepoDir.resolve("safe-skill");
      Files.createDirectories(skillDir);
      Files.writeString(skillDir.resolve("SKILL.md"), "# Safe Skill\n");
      git.add().addFilepattern(".").call();
      RevCommit validCommit = git.commit().setMessage("valid").call();
      validCommitId = validCommit.getId().name();

      Path linkPath = remoteRepoDir.resolve("bad-link");
      Files.createSymbolicLink(linkPath, Path.of("safe-skill/SKILL.md"));
      git.add().addFilepattern(".").call();
      RevCommit symCommit = git.commit().setMessage("add symlink").call();
      symlinkCommitId = symCommit.getId().name();
    }

    String remoteUrl = remoteRepoDir.toUri().toString();
    installer.install("symlink-pkg", remoteUrl, "master", validCommitId);
    Path pkgDir = skillsRoot.resolve("symlink-pkg");
    assertTrue(Files.exists(pkgDir.resolve("safe-skill/SKILL.md")));

    SkillSyncException error =
        assertThrows(
            SkillSyncException.class,
            () -> installer.install("symlink-pkg", remoteUrl, "master", symlinkCommitId));
    assertEquals("UNSAFE_PACKAGE_ENTRY", error.code());

    // 验证先前安装 byte-for-byte 可用
    assertTrue(Files.exists(pkgDir.resolve("safe-skill/SKILL.md")));
    assertEquals("# Safe Skill\n", Files.readString(pkgDir.resolve("safe-skill/SKILL.md")));
    assertEquals(validCommitId + "\n", Files.readString(pkgDir.resolve(".kkstudio-commit")));

    // 验证 staging 与 backup 无残留
    assertDirectoryEmpty(stagingRoot);
    assertDirectoryEmpty(backupRoot);
  }

  /** 验证未知 commit 与缺失分支的回退失败保留已有安装、返回稳定错误，且 staging/backup 不遗留临时产物。 */
  @Test
  void fetchFailurePreservesPreviousInstallAndLeavesNoArtifacts() throws Exception {
    String validCommitId;
    try (Git git = Git.init().setDirectory(remoteRepoDir.toFile()).call()) {
      Path skillDir = remoteRepoDir.resolve("persist-skill");
      Files.createDirectories(skillDir);
      Files.writeString(skillDir.resolve("SKILL.md"), "# Persist\n");
      git.add().addFilepattern(".").call();
      RevCommit validCommit = git.commit().setMessage("valid").call();
      validCommitId = validCommit.getId().name();
    }

    String remoteUrl = remoteRepoDir.toUri().toString();
    installer.install("persist-pkg", remoteUrl, "master", validCommitId);
    Path pkgDir = skillsRoot.resolve("persist-pkg");

    String unknownCommit = "0123456789012345678901234567890123456789";
    SkillSyncException error =
        assertThrows(
            SkillSyncException.class,
            () -> installer.install("persist-pkg", remoteUrl, "master", unknownCommit));
    assertEquals("COMMIT_NOT_FOUND", error.code());

    // 精确对象未广告时允许回退；回退分支缺失仍须稳定分类并保持原安装。
    SkillSyncException fallback =
        assertThrows(
            SkillSyncException.class,
            () -> installer.install("persist-pkg", remoteUrl, "missing", unknownCommit));
    assertEquals("GIT_FETCH_FAILED", fallback.code());
    assertEquals("Git branch fetch failed", fallback.getMessage());

    // 验证已有安装完好无损
    assertTrue(Files.exists(pkgDir.resolve("persist-skill/SKILL.md")));
    assertEquals("# Persist\n", Files.readString(pkgDir.resolve("persist-skill/SKILL.md")));
    assertEquals(validCommitId + "\n", Files.readString(pkgDir.resolve(".kkstudio-commit")));

    // 验证 staging 与 backup 无残留产物
    assertDirectoryEmpty(stagingRoot);
    assertDirectoryEmpty(backupRoot);
  }

  /**
   * 测试意图：同名 Package 的 cache 只按 origin URL 判定 provenance。Package 删除后以同名重建并指向另一个仓库时，旧仓库的对象绝不参与 commit
   * 判定：新仓库提供不了的 commit 必须失败并保留旧安装，重建后的 cache 只物化新仓库的内容。
   */
  @Test
  void cacheFromAnotherRepositoryIsNeverTrusted() throws Exception {
    String oldCommitId;
    try (Git git = Git.init().setDirectory(remoteRepoDir.toFile()).call()) {
      Path skillDir = remoteRepoDir.resolve("old-skill");
      Files.createDirectories(skillDir);
      Files.writeString(skillDir.resolve("SKILL.md"), "# Old Skill\n");
      git.add().addFilepattern(".").call();
      oldCommitId = git.commit().setMessage("old repository commit").call().getId().name();
    }

    Path anotherRepoDir = tempDir.resolve("another-repo");
    String newCommitId;
    try (Git git = Git.init().setDirectory(anotherRepoDir.toFile()).call()) {
      Path skillDir = anotherRepoDir.resolve("new-skill");
      Files.createDirectories(skillDir);
      Files.writeString(skillDir.resolve("SKILL.md"), "# New Skill\n");
      git.add().addFilepattern(".").call();
      newCommitId = git.commit().setMessage("new repository commit").call().getId().name();
    }

    String oldUrl = remoteRepoDir.toUri().toString();
    String newUrl = anotherRepoDir.toUri().toString();
    installer.install("rotated-pkg", oldUrl, "master", oldCommitId);
    Path pkgDir = skillsRoot.resolve("rotated-pkg");
    assertTrue(Files.exists(pkgDir.resolve("old-skill/SKILL.md")));

    // 同名 Package 改指另一个仓库：缓存的旧对象对新仓库毫无意义，因此该 commit 必须解析失败。
    SkillSyncException error =
        assertThrows(
            SkillSyncException.class,
            () -> installer.install("rotated-pkg", newUrl, "master", oldCommitId));
    assertEquals("COMMIT_NOT_FOUND", error.code());
    assertTrue(Files.exists(pkgDir.resolve("old-skill/SKILL.md")));
    assertEquals(oldCommitId + "\n", Files.readString(pkgDir.resolve(".kkstudio-commit")));

    // 重建后的缓存属于新仓库：安装它自己的 commit 只物化新仓库内容。
    InstalledSkillPackage installed =
        installer.install("rotated-pkg", newUrl, "master", newCommitId);
    assertEquals(newCommitId, installed.installedCommit());
    assertFalse(Files.exists(pkgDir.resolve("old-skill/SKILL.md")));
    assertEquals("# New Skill\n", Files.readString(pkgDir.resolve("new-skill/SKILL.md")));
    assertEquals(newCommitId + "\n", Files.readString(pkgDir.resolve(".kkstudio-commit")));

    assertDirectoryEmpty(stagingRoot);
    assertDirectoryEmpty(backupRoot);
  }

  /**
   * 意图：恢复只处理产品命名的 staging `package.UUID[.clone]` 与 backup `package.UUID`——缺失目标的 backup 被恢复，目标已存在的
   * backup 被清理，任何未知命名条目都必须原样保留，绝不误删用户数据。
   */
  @Test
  void recoverArtifactsOnlyTouchesProductNamedArtifacts() throws Exception {
    // 1. 产品命名的 staging 残留（含与不含 clone 后缀）
    Path leftoverStaging = stagingRoot.resolve("leftover-pkg." + UUID.randomUUID());
    Files.createDirectories(leftoverStaging);
    Files.writeString(leftoverStaging.resolve("temp.txt"), "leftover");

    Path leftoverClone = stagingRoot.resolve("leftover-pkg." + UUID.randomUUID() + ".clone");
    Files.createDirectories(leftoverClone);
    Files.writeString(leftoverClone.resolve("temp.txt"), "leftover-clone");

    // 2. 非产品命名的 staging 条目：绝不删除
    Path foreignStagingFile = stagingRoot.resolve("manual-unrelated.txt");
    Files.writeString(foreignStagingFile, "keep-me");
    Path foreignStagingDir = stagingRoot.resolve("unrelated-dir");
    Files.createDirectories(foreignStagingDir);

    // 3. 缺失目标的 backup 目录（应被恢复）
    Path restorableBackup = backupRoot.resolve("restored-pkg." + UUID.randomUUID());
    Files.createDirectories(restorableBackup);
    Files.writeString(restorableBackup.resolve("recovered.txt"), "good");

    // 4. 目标已存在的 backup 目录（应被安全删除）
    Path existingPackage = skillsRoot.resolve("existing-pkg");
    Files.createDirectories(existingPackage);
    Files.writeString(existingPackage.resolve("current.txt"), "current");

    Path existingBackup = backupRoot.resolve("existing-pkg." + UUID.randomUUID());
    Files.createDirectories(existingBackup);
    Files.writeString(existingBackup.resolve("old.txt"), "old");

    // 5. 非产品命名的 backup 条目（UUID 非法或包名非法）：绝不删除
    Path corruptBackup = backupRoot.resolve("corrupted-file.bin");
    Files.writeString(corruptBackup, "garbage");
    Path badPackageBackup = backupRoot.resolve("bad@name." + UUID.randomUUID());
    Files.createDirectories(badPackageBackup);

    installer.recoverArtifacts();

    // 产品命名 staging 已清理，未知条目原样保留
    assertFalse(Files.exists(leftoverStaging));
    assertFalse(Files.exists(leftoverClone));
    assertTrue(Files.exists(foreignStagingFile));
    assertTrue(Files.exists(foreignStagingDir));

    // 缺失目标的包已被恢复
    Path restoredDir = skillsRoot.resolve("restored-pkg");
    assertTrue(Files.exists(restoredDir));
    assertEquals("good", Files.readString(restoredDir.resolve("recovered.txt")));

    // 目标已存在的包未被备份覆盖，其冗余 backup 被清理
    assertTrue(Files.exists(existingPackage.resolve("current.txt")));
    assertFalse(Files.exists(existingPackage.resolve("old.txt")));
    assertFalse(Files.exists(existingBackup));

    // 未知命名 backup 原样保留
    assertTrue(Files.exists(corruptBackup));
    assertEquals("garbage", Files.readString(corruptBackup));
    assertTrue(Files.exists(badPackageBackup));
  }

  /**
   * 意图（负向对照）：后端提交对象是 SHA-1；64 位 SHA-256 commit 必须在任何 clone/fetch/临时目录副作用之前以固定 INVALID_TARGET_COMMIT
   * 拒绝。旧实现把它放进 Git 层，会退化为 INSTALL_FAILED/COMMIT_NOT_FOUND 等其它失败。
   */
  @Test
  void sha256CommitIsRejectedBeforeAnyCloneOrFetch() {
    // 故意指向不存在的仓库：只有校验先于 I/O，这里才可能得到 INVALID_TARGET_COMMIT 而不是 fetch 失败。
    String missingRepositoryUrl = tempDir.resolve("missing-repo").toUri().toString();
    String sha256Commit = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    SkillSyncException error =
        assertThrows(
            SkillSyncException.class,
            () -> installer.install("sha256-pkg", missingRepositoryUrl, "master", sha256Commit));

    assertEquals("INVALID_TARGET_COMMIT", error.code());
    assertFalse(error.getMessage().contains(sha256Commit));
    assertDirectoryEmpty(stagingRoot);
    assertDirectoryEmpty(backupRoot);
    assertFalse(Files.exists(skillsRoot.resolve("sha256-pkg")));
  }

  /** 意图：产品命名 backup 恢复失败时绝不删除可能唯一的数据副本，而是以受控失败上报；恢复在构造期完成，因此失败立刻暴露。 */
  @Test
  void recoveryFailureKeepsBackupAndReportsControlledFailure() throws Exception {
    Path restorableBackup = backupRoot.resolve("fail-restore-pkg." + UUID.randomUUID());
    Files.createDirectories(restorableBackup);
    Files.writeString(restorableBackup.resolve("important.txt"), "safe");

    UncheckedIOException failure =
        assertThrows(
            UncheckedIOException.class,
            () ->
                new SkillPackageInstaller(
                    skillsRoot,
                    stagingRoot,
                    backupRoot,
                    1000,
                    1000,
                    executor,
                    (source, target) -> {
                      throw new IOException("simulated restore failure");
                    }));

    assertTrue(failure.getMessage().contains("restore skill backup"), failure.getMessage());
    // 备份必须原样保留
    assertTrue(Files.exists(restorableBackup));
    assertEquals("safe", Files.readString(restorableBackup.resolve("important.txt")));
    assertFalse(Files.exists(skillsRoot.resolve("fail-restore-pkg")));
  }

  /** 意图：恢复期目录流本身失败（staging/backup 根被移除）也必须以受控 UncheckedIOException 上报，绝不静默吞掉。 */
  @Test
  void recoverySurfacesDirectoryStreamFailuresAsControlledErrors() throws Exception {
    Files.delete(stagingRoot);
    UncheckedIOException stagingFailure =
        assertThrows(UncheckedIOException.class, installer::recoverArtifacts);
    assertTrue(
        stagingFailure.getMessage().contains("clean staging root"), stagingFailure.getMessage());

    // 以独立 installer 隔离 staging 步骤，验证 backup 根缺失同样受控失败
    Path otherSkills = tempDir.resolve("skills-other");
    Path otherStaging = tempDir.resolve("staging-other");
    Path otherBackup = tempDir.resolve("backup-other");
    SkillPackageInstaller other =
        new SkillPackageInstaller(otherSkills, otherStaging, otherBackup, executor);
    Files.delete(otherBackup);
    UncheckedIOException backupFailure =
        assertThrows(UncheckedIOException.class, other::recoverArtifacts);
    assertTrue(
        backupFailure.getMessage().contains("recover backup root"), backupFailure.getMessage());
  }

  /** 意图：交换失败后回滚也失败时，必须给出区别于普通安装失败的安全提示，并把主错误保留为 cause、回滚错误保留为 suppressed， 既不伪装成功也不吞掉任一失败。 */
  @Test
  void publishSwapAndRollbackDoubleFailureSurfacesDistinctSafetyMessage() throws Exception {
    String commit1Id;
    String commit2Id;
    try (Git git = Git.init().setDirectory(remoteRepoDir.toFile()).call()) {
      Path skillDir = remoteRepoDir.resolve("rollback-skill");
      Files.createDirectories(skillDir);
      Files.writeString(skillDir.resolve("SKILL.md"), "# Version 1\n");
      git.add().addFilepattern(".").call();
      commit1Id = git.commit().setMessage("v1").call().getId().name();

      Files.writeString(skillDir.resolve("SKILL.md"), "# Version 2\n");
      git.add().addFilepattern(".").call();
      commit2Id = git.commit().setMessage("v2").call().getId().name();
    }

    String remoteUrl = remoteRepoDir.toUri().toString();
    installer.install("rollback-pkg", remoteUrl, "master", commit1Id);
    Path pkgDir = skillsRoot.resolve("rollback-pkg");
    assertEquals("# Version 1\n", Files.readString(pkgDir.resolve("rollback-skill/SKILL.md")));

    SkillPackageInstaller doubleFaultInstaller =
        new SkillPackageInstaller(
            skillsRoot,
            stagingRoot,
            backupRoot,
            1000,
            1000,
            executor,
            (source, target) -> {
              if (source.equals(pkgDir) && target.startsWith(backupRoot)) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                return;
              }
              if (source.startsWith(stagingRoot) && target.equals(pkgDir)) {
                throw new IOException("simulated initial swap failure");
              }
              if (source.startsWith(backupRoot) && target.equals(pkgDir)) {
                throw new IOException("simulated rollback failure");
              }
              Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            });

    SkillSyncException error =
        assertThrows(
            SkillSyncException.class,
            () -> doubleFaultInstaller.install("rollback-pkg", remoteUrl, "master", commit2Id));

    assertEquals("INSTALL_FAILED", error.code());
    assertFalse(
        error.getMessage().equals("Failed to swap installed package directory"),
        "double failure must not reuse the ordinary swap-failure message");
    assertTrue(error.getMessage().contains("rollback"), error.getMessage());

    // 主错误保留为 cause，回滚错误保留为 suppressed，二者都不被吞掉
    Throwable primary = error.getCause();
    assertInstanceOf(IOException.class, primary);
    assertTrue(primary.getMessage().contains("initial swap"), primary.getMessage());
    assertEquals(1, primary.getSuppressed().length);
    assertTrue(primary.getSuppressed()[0].getMessage().contains("rollback"));

    // 明确记录数据状态：旧包已移入 backup 且不再位于 skills，绝不伪装成功
    assertFalse(Files.exists(pkgDir));
  }

  /** 验证包名、commit、URL 以及分支等非法参数在产生任何文件系统副作用前被拒绝。 */
  @Test
  void invalidArgumentsRejectedWithoutFilesystemSideEffects() {
    String validUrl = "https://example.com/repo.git";
    String validCommit = "0123456789012345678901234567890123456789";
    String validBranch = "main";

    // 非法包名
    for (String badPackage :
        new String[] {
          null,
          "",
          " ",
          "bad/name",
          "bad\\name",
          "bad:name",
          "bad@name",
          ".",
          "..",
          " leading",
          "trailing ",
          "bad\u0001name",
          "a".repeat(129)
        }) {
      SkillSyncException error =
          assertThrows(
              SkillSyncException.class,
              () -> installer.install(badPackage, validUrl, validBranch, validCommit),
              "rejected: " + badPackage);
      assertEquals("INVALID_PACKAGE_NAME", error.code());
    }

    // 非法 commit
    for (String badCommit :
        new String[] {
          null,
          "",
          " ",
          "short",
          "012345678901234567890123456789012345678", // 39 字符
          "0123456789012345678901234567890123456789a", // 41 字符
          "012345678901234567890123456789012345678G", // 非十六进制
          "ABCDEF0123456789ABCDEF0123456789ABCDEF01" // 大写十六进制
        }) {
      SkillSyncException error =
          assertThrows(
              SkillSyncException.class,
              () -> installer.install("valid-pkg", validUrl, validBranch, badCommit),
              "rejected: " + badCommit);
      assertEquals("INVALID_TARGET_COMMIT", error.code());
    }

    // 非法 URL
    for (String badUrl :
        new String[] {
          null,
          "",
          " ",
          "not-a-uri",
          "relative/path",
          "https://user:pass@example.com/repo.git",
          "https://user@example.com/repo.git",
          "https://" + "a".repeat(2048) + ".com"
        }) {
      SkillSyncException error =
          assertThrows(
              SkillSyncException.class,
              () -> installer.install("valid-pkg", badUrl, validBranch, validCommit),
              "rejected: " + badUrl);
      assertEquals("INVALID_REPOSITORY_URL", error.code());
    }

    // 非法 scheme 与含凭据的 URL：错误码稳定，且错误信息绝不回显原始输入或凭据。
    for (String secretUrl :
        new String[] {
          "git://example.com/repo.git",
          "ssh://example.com/repo.git",
          "https://user:pass@example.com/repo.git"
        }) {
      SkillSyncException error =
          assertThrows(
              SkillSyncException.class,
              () -> installer.install("valid-pkg", secretUrl, validBranch, validCommit));
      assertEquals("INVALID_REPOSITORY_URL", error.code());
      assertFalse(error.getMessage().contains(secretUrl));
      assertFalse(error.getMessage().contains("user:pass"));
    }

    // 非法 branch
    for (String badBranch :
        new String[] {null, "", " ", " leading", "trailing ", "bad\u0001branch", "a".repeat(256)}) {
      SkillSyncException error =
          assertThrows(
              SkillSyncException.class,
              () -> installer.install("valid-pkg", validUrl, badBranch, validCommit),
              "rejected: " + badBranch);
      assertEquals("INVALID_BRANCH", error.code());
    }

    // 确认 staging 与 backup 没有生成任何文件
    assertDirectoryEmpty(stagingRoot);
    assertDirectoryEmpty(backupRoot);
  }

  /** 验证物化后 swap/原子替换失败时能够自动回滚并保留已有安装，staging 与 backup 均无残留。 */
  @Test
  void publishSwapFailurePreservesPreviousInstall() throws Exception {
    String commit1Id;
    String commit2Id;
    try (Git git = Git.init().setDirectory(remoteRepoDir.toFile()).call()) {
      Path skillDir = remoteRepoDir.resolve("swap-skill");
      Files.createDirectories(skillDir);
      Files.writeString(skillDir.resolve("SKILL.md"), "# Version 1\n");
      git.add().addFilepattern(".").call();
      commit1Id = git.commit().setMessage("v1").call().getId().name();

      Files.writeString(skillDir.resolve("SKILL.md"), "# Version 2\n");
      git.add().addFilepattern(".").call();
      commit2Id = git.commit().setMessage("v2").call().getId().name();
    }

    String remoteUrl = remoteRepoDir.toUri().toString();
    installer.install("swap-pkg", remoteUrl, "master", commit1Id);
    Path pkgDir = skillsRoot.resolve("swap-pkg");
    assertEquals("# Version 1\n", Files.readString(pkgDir.resolve("swap-skill/SKILL.md")));
    assertEquals(commit1Id + "\n", Files.readString(pkgDir.resolve(".kkstudio-commit")));

    // 构造一个在 staging -> packageDir 移动时模拟故障的 swapper
    AtomicBoolean failSwap = new AtomicBoolean(true);
    SkillPackageInstaller faultyInstaller =
        new SkillPackageInstaller(
            skillsRoot,
            stagingRoot,
            backupRoot,
            1000,
            1000,
            executor,
            (source, target) -> {
              if (failSwap.get() && source.startsWith(stagingRoot) && target.equals(pkgDir)) {
                throw new IOException("simulated atomic swap failure");
              }
              try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
              } catch (AtomicMoveNotSupportedException error) {
                Files.move(source, target);
              }
            });

    SkillSyncException error =
        assertThrows(
            SkillSyncException.class,
            () -> faultyInstaller.install("swap-pkg", remoteUrl, "master", commit2Id));
    assertEquals("INSTALL_FAILED", error.code());

    // 验证先前安装 byte-for-byte 得到回滚与保留
    assertTrue(Files.exists(pkgDir.resolve("swap-skill/SKILL.md")));
    assertEquals("# Version 1\n", Files.readString(pkgDir.resolve("swap-skill/SKILL.md")));
    assertEquals(commit1Id + "\n", Files.readString(pkgDir.resolve(".kkstudio-commit")));

    // 验证 staging 与 backup 无残留
    assertDirectoryEmpty(stagingRoot);
    assertDirectoryEmpty(backupRoot);
  }

  /** 验证认证失败被分类为 GIT_AUTHENTICATION_FAILED，保留已有安装，且错误信息与异常链不泄漏 URL 或凭据。 */
  @Test
  void authenticationFailureSurfacesCodedErrorWithoutEchoingSecretOrUrl() throws Exception {
    String commitId;
    try (Git git =
        Git.init().setDirectory(remoteRepoDir.toFile()).setInitialBranch("main").call()) {
      Path skillDir = remoteRepoDir.resolve("auth-skill");
      Files.createDirectories(skillDir);
      Files.writeString(skillDir.resolve("SKILL.md"), "# Auth Skill\n");
      git.add().addFilepattern(".").call();
      commitId = git.commit().setMessage("initial").call().name();
    }

    String remoteUrl = remoteRepoDir.toUri().toString();
    installer.install("auth-pkg", remoteUrl, "main", commitId);
    Path pkgDir = skillsRoot.resolve("auth-pkg");
    assertTrue(Files.exists(pkgDir.resolve("auth-skill/SKILL.md")));

    String secretCredential = "ghp_super_secret_token_1234567890";
    try (LocalGitHttpServer server =
        new LocalGitHttpServer(remoteRepoDir.resolve(".git"), commitId)) {
      server.rejectUnauthorized = true;

      SkillSyncException error =
          assertThrows(
              SkillSyncException.class,
              () ->
                  installer.install("auth-pkg", server.url(), "main", commitId, secretCredential));
      assertEquals("GIT_AUTHENTICATION_FAILED", error.code());
      assertFalse(error.getMessage().contains(secretCredential));
      assertFalse(error.getMessage().contains(server.url()));

      // 验证先前安装完好保留
      assertTrue(Files.exists(pkgDir.resolve("auth-skill/SKILL.md")));
      assertEquals("# Auth Skill\n", Files.readString(pkgDir.resolve("auth-skill/SKILL.md")));
      assertEquals(commitId + "\n", Files.readString(pkgDir.resolve(".kkstudio-commit")));

      assertDirectoryEmpty(stagingRoot);
      assertDirectoryEmpty(backupRoot);
    }
  }

  /** 验证携带有效凭据安装能物化预期的目录树与元数据。 */
  @Test
  void exactCommitInstallWithCredentialProducesExpectedTreeAndMetadata() throws Exception {
    String commitId;
    try (Git git = Git.init().setDirectory(remoteRepoDir.toFile()).call()) {
      Path skillDir = remoteRepoDir.resolve("cred-skill");
      Files.createDirectories(skillDir);
      Files.writeString(skillDir.resolve("SKILL.md"), "# Credential Skill\n");
      git.add().addFilepattern(".").call();
      commitId = git.commit().setMessage("initial with cred").call().getId().name();
    }

    String remoteUrl = remoteRepoDir.toUri().toString();
    InstalledSkillPackage installed =
        installer.install("cred-package", remoteUrl, "master", commitId, "valid-access-token-123");

    assertEquals("cred-package", installed.packageName());
    assertEquals(commitId, installed.installedCommit());
    Path expectedPackageRoot = skillsRoot.resolve("cred-package");
    assertEquals(expectedPackageRoot.toString(), installed.localPath());

    Path skillFile = expectedPackageRoot.resolve("cred-skill/SKILL.md");
    assertTrue(Files.isRegularFile(skillFile));
    assertEquals("# Credential Skill\n", Files.readString(skillFile));

    Path commitFile = expectedPackageRoot.resolve(".kkstudio-commit");
    assertTrue(Files.isRegularFile(commitFile));
    assertEquals(commitId + "\n", Files.readString(commitFile));
  }

  private static void assertDirectoryEmpty(Path dir) {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      assertFalse(stream.iterator().hasNext(), "directory must be empty: " + dir);
    } catch (IOException error) {
      throw new AssertionError(error);
    }
  }
}
