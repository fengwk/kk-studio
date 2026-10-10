package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 针对 {@link OwnerOnlyFiles} 的 owner-only 原语断言：POSIX 创建即 0700/0600，ACL 平台（Windows）创建即单条当前用户 ALLOW
 * 并读回校验，且可信根之下的符号链接、路径逃逸与非目录分量全部失败关闭。
 *
 * <p>受控临时资源的安全性依赖这些原语：权限必须与创建一起提交，链接/逃逸必须在创建前拒绝而不是创建后再修补。ACL 相关用例只在文件系统不支持 acl 视图时跳过，绝不用于回避真实的
 * Windows ACL 断言。
 */
class OwnerOnlyFilesTest {

  @TempDir Path trustedRoot;

  /** 目录必须以 0700 创建（含新建的父级链）并在重复调用时保持 owner-only，不产生“先建后改权限”的窗口。 */
  @Test
  void ensureOwnerOnlyDirectoryCreatesAndConvergesToOwnerOnly() throws IOException {
    assumeTrue(isPosixSupported(), "需要 POSIX 文件系统验证权限位");
    Path directory = trustedRoot.resolve("data").resolve("tmp").resolve("workspaces");

    Path first = OwnerOnlyFiles.ensureOwnerOnlyDirectory(trustedRoot, directory);
    assertEquals(directory, first);

    Set<PosixFilePermission> ownerOnlyDirectory =
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    assertEquals(
        ownerOnlyDirectory,
        Files.getPosixFilePermissions(trustedRoot.resolve("data")),
        "新建的父级目录必须以 0700 创建");
    assertEquals(
        ownerOnlyDirectory,
        Files.getPosixFilePermissions(trustedRoot.resolve("data").resolve("tmp")),
        "新建的中间目录必须以 0700 创建");

    // 放宽权限后再确保一次：收敛回 0700。
    Files.setPosixFilePermissions(directory, Set.of(PosixFilePermission.OWNER_READ));
    Path second = OwnerOnlyFiles.ensureOwnerOnlyDirectory(trustedRoot, directory);
    assertEquals(directory, second);
    assertEquals(ownerOnlyDirectory, Files.getPosixFilePermissions(directory));
  }

  /** 新建中转文件必须以 owner-only 0600 直接创建。 */
  @Test
  void createOwnerOnlyFileIsOwnerOnly() throws IOException {
    assumeTrue(isPosixSupported(), "需要 POSIX 文件系统验证权限位");
    Path candidate = trustedRoot.resolve("staging.part");

    OwnerOnlyFiles.createOwnerOnlyFile(trustedRoot, candidate);

    assertTrue(Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS));
    assertEquals(
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        Files.getPosixFilePermissions(candidate));
  }

  /** ACL 平台：ensure 必须以当前进程用户为唯一主体建立 owner-only ACL，且新建的每一级父目录都同样收敛。 */
  @Test
  void ensureOwnerOnlyDirectoryAppliesOwnerOnlyAcl() throws IOException {
    assumeTrue(isAclSupported(), "需要支持 acl 文件属性视图的文件系统验证 owner-only ACL");
    Path directory = trustedRoot.resolve("data").resolve("tmp").resolve("workspaces");

    OwnerOnlyFiles.ensureOwnerOnlyDirectory(trustedRoot, directory);

    assertSingleOwnerOnlyAcl(directory, expectedDirectoryAcl());
    assertSingleOwnerOnlyAcl(trustedRoot.resolve("data"), expectedDirectoryAcl());
    assertSingleOwnerOnlyAcl(trustedRoot.resolve("data").resolve("tmp"), expectedDirectoryAcl());
  }

  /** ACL 平台：中转文件必须只授权当前进程用户，且本人仍能正常写入并读回。 */
  @Test
  void createOwnerOnlyFileAppliesOwnerOnlyAcl() throws IOException {
    assumeTrue(isAclSupported(), "需要支持 acl 文件属性视图的文件系统验证 owner-only ACL");
    Path candidate = trustedRoot.resolve("staging.part");

    OwnerOnlyFiles.createOwnerOnlyFile(trustedRoot, candidate);

    assertTrue(Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS));
    assertSingleOwnerOnlyAcl(candidate, expectedFileAcl());
    Files.writeString(candidate, "payload", StandardOpenOption.WRITE);
    assertEquals("payload", Files.readString(candidate));
  }

  /** ACL 平台：目录的继承标志必须让后续普通创建的子文件/子目录同样只授权当前进程用户，不引入其他主体。 */
  @Test
  void aclDirectoryKeepsDescendantsOwnerOnly() throws IOException {
    assumeTrue(isAclSupported(), "需要支持 acl 文件属性视图的文件系统验证 ACL 继承");
    Path directory = trustedRoot.resolve("workspaces");
    OwnerOnlyFiles.ensureOwnerOnlyDirectory(trustedRoot, directory);

    Path childFile = Files.createFile(directory.resolve("child.txt"));
    Path childDirectory = Files.createDirectory(directory.resolve("child"));

    assertGrantsOnlyCurrentUser(childFile);
    assertGrantsOnlyCurrentUser(childDirectory);
  }

  /** ACL 平台：已存在的宽 ACL 必须在重复 ensure 时被收敛回仅当前进程用户，绝不保留其他主体的授权。 */
  @Test
  void ensureOwnerOnlyDirectoryStripsBroadAcl() throws IOException {
    assumeTrue(isAclSupported(), "需要支持 acl 文件属性视图的文件系统验证 ACL 收敛");
    Path directory = trustedRoot.resolve("workspaces");
    OwnerOnlyFiles.ensureOwnerOnlyDirectory(trustedRoot, directory);

    // 先人为注入一条其他主体的宽授权，再确认 ensure 会把它收敛掉。
    aclView(directory).setAcl(List.of(expectedDirectoryAcl(), broadAclEntry()));

    OwnerOnlyFiles.ensureOwnerOnlyDirectory(trustedRoot, directory);

    assertSingleOwnerOnlyAcl(directory, expectedDirectoryAcl());
  }

  /** 可信根之下出现符号链接分量时必须失败关闭，绝不跟随链接创建受控产物。 */
  @Test
  void rejectsSymlinkedComponentBelowTrustedRoot() throws IOException {
    assumeTrue(supportsSymlinks(), "需要支持符号链接的文件系统");
    Path outside = Files.createDirectories(trustedRoot.resolve("outside"));
    Path link = trustedRoot.resolve("linked");
    Files.createSymbolicLink(link, outside);

    assertThrows(
        IOException.class,
        () -> OwnerOnlyFiles.createOwnerOnlyDirectory(trustedRoot, link.resolve("child")));
    assertThrows(
        IOException.class,
        () -> OwnerOnlyFiles.createOwnerOnlyFile(trustedRoot, link.resolve("file.part")));
  }

  /** 目标逃出可信根时必须失败关闭，绝不把受控产物写到根外。 */
  @Test
  void rejectsPathEscapingTrustedRoot() {
    Path escaped = trustedRoot.getParent().resolve("outside-the-root");

    assertThrows(
        IOException.class, () -> OwnerOnlyFiles.createOwnerOnlyDirectory(trustedRoot, escaped));
  }

  /** 中间分量已存在但不是目录时必须失败关闭，而不是顺着普通文件继续下探。 */
  @Test
  void rejectsNonDirectoryIntermediateComponent() throws IOException {
    Path file = Files.writeString(trustedRoot.resolve("not-a-dir"), "x");

    assertThrows(
        IOException.class,
        () -> OwnerOnlyFiles.createOwnerOnlyDirectory(trustedRoot, file.resolve("child")));
  }

  /** ensure 目标已存在但不是目录时必须失败关闭，绝不改写已有普通文件。 */
  @Test
  void ensureOwnerOnlyDirectoryRejectsExistingNonDirectory() throws IOException {
    Path file = Files.writeString(trustedRoot.resolve("afile"), "payload");

    assertThrows(
        IOException.class, () -> OwnerOnlyFiles.ensureOwnerOnlyDirectory(trustedRoot, file));
    assertEquals("payload", Files.readString(file), "被拒绝的输入不得被改写");
  }

  private static AclEntry expectedDirectoryAcl() throws IOException {
    return AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(currentUserPrincipal())
        .setPermissions(ownerOnlyAclPermissions(true))
        .setFlags(EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT))
        .build();
  }

  private static AclEntry expectedFileAcl() throws IOException {
    return AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(currentUserPrincipal())
        .setPermissions(ownerOnlyAclPermissions(false))
        .build();
  }

  private static Set<AclEntryPermission> ownerOnlyAclPermissions(boolean directory) {
    EnumSet<AclEntryPermission> permissions =
        EnumSet.of(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.EXECUTE,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.DELETE,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.WRITE_ACL,
            AclEntryPermission.SYNCHRONIZE);
    if (directory) {
      permissions.add(AclEntryPermission.DELETE_CHILD);
    }
    return permissions;
  }

  /** 一个明确不同的主体（Everyone），用来验证 ensure 会剥离并非当前用户的授权。 */
  private static AclEntry broadAclEntry() throws IOException {
    UserPrincipal everyone =
        FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName("Everyone");
    return AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(everyone)
        .setPermissions(AclEntryPermission.READ_DATA)
        .build();
  }

  /** 精确断言 ACL 只有预期的单条条目：任何被平台追加的其他主体或多余条目都会失败。 */
  private static void assertSingleOwnerOnlyAcl(Path target, AclEntry expected) throws IOException {
    assertEquals(List.of(expected), aclView(target).getAcl(), "ACL 必须是仅当前进程用户的一条授权: " + target);
  }

  /** 断言 ACL 非空、每条都是 ALLOW 且主体是当前进程用户，并至少保留写数据权限。 */
  private static void assertGrantsOnlyCurrentUser(Path target) throws IOException {
    UserPrincipal owner = currentUserPrincipal();
    List<AclEntry> acl = aclView(target).getAcl();
    assertFalse(acl.isEmpty(), "继承后仍必须有当前用户的授权: " + target);
    for (AclEntry entry : acl) {
      assertEquals(AclEntryType.ALLOW, entry.type(), "不得出现非 ALLOW 条目: " + target);
      assertEquals(owner, entry.principal(), "不得向其他主体授权: " + target);
    }
    assertTrue(
        acl.stream().anyMatch(entry -> entry.permissions().contains(AclEntryPermission.WRITE_DATA)),
        "当前用户必须保留写入权限: " + target);
  }

  private static AclFileAttributeView aclView(Path target) {
    return Files.getFileAttributeView(
        target, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
  }

  private static UserPrincipal currentUserPrincipal() throws IOException {
    return FileSystems.getDefault()
        .getUserPrincipalLookupService()
        .lookupPrincipalByName(System.getProperty("user.name"));
  }

  private static boolean isPosixSupported() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }

  private static boolean isAclSupported() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("acl");
  }

  private static boolean supportsSymlinks() {
    Path probe = null;
    try {
      Path base = Files.createTempDirectory("symlink-probe");
      Path target = Files.createDirectories(base.resolve("target"));
      probe = base.resolve("link");
      Files.createSymbolicLink(probe, target);
      return true;
    } catch (IOException | UnsupportedOperationException error) {
      return false;
    } finally {
      if (probe != null) {
        try {
          Files.deleteIfExists(probe);
          Files.deleteIfExists(probe.getParent());
        } catch (IOException ignored) {
          // 探测目录残留不影响测试结论。
        }
      }
    }
  }
}
