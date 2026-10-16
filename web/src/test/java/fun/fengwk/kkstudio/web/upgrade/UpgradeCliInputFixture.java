package fun.fengwk.kkstudio.web.upgrade;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * CLI 测试的受保护输入文件基座：owner-only 目录与 0600 文件，以及固定连接 JSON 片段，供参数/JSON/key 边界与真实 PG 用例复用。
 *
 * <p>包内可见（package-private）：只是升级 CLI 测试的物理输入基座，不是生产代码。
 */
final class UpgradeCliInputFixture {

  private static final Set<PosixFilePermission> OWNER_ONLY =
      PosixFilePermissions.fromString("rw-------");

  private static final Set<PosixFilePermission> GROUP_READABLE =
      PosixFilePermissions.fromString("rw-r--r--");

  static final String USERNAME = "upgrade-operator";

  static final String PASSWORD = "protected-database-password";

  private UpgradeCliInputFixture() {}

  /** 把临时目录收紧为 owner-only（0700），使目录内的受保护文件不会被目录权限挡在门外。 */
  static Path ownerOnlyDir(Path dir) throws IOException {
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    return dir;
  }

  static Path config(Path dir, String name, String content) throws IOException {
    return ownerOnlyFile(dir, name, content.getBytes(StandardCharsets.UTF_8));
  }

  static Path key(Path dir, String name, byte[] raw) throws IOException {
    return ownerOnlyFile(dir, name, raw);
  }

  static Path ownerOnlyFile(Path dir, String name, byte[] content) throws IOException {
    Path path = dir.resolve(name);
    Files.write(path, content);
    Files.setPosixFilePermissions(path, OWNER_ONLY);
    return path;
  }

  /** 组可读文件：用于验证受保护文件权限过宽的拒绝路径。 */
  static Path groupReadableConfig(Path dir, String name, String content) throws IOException {
    Path path = dir.resolve(name);
    Files.writeString(path, content);
    Files.setPosixFilePermissions(path, GROUP_READABLE);
    return path;
  }

  static String endpoint(String url) {
    return endpoint(url, USERNAME, PASSWORD);
  }

  static String endpoint(String url, String username, String password) {
    return "{\"url\":\""
        + url
        + "\",\"username\":\""
        + username
        + "\",\"password\":\""
        + password
        + "\"}";
  }

  static String prepareConfig(String targetUrl) {
    return prepareConfig(targetUrl, USERNAME, PASSWORD);
  }

  static String prepareConfig(String targetUrl, String username, String password) {
    return "{\"target\":" + endpoint(targetUrl, username, password) + "}";
  }
}
