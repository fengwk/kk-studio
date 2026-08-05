package fun.fengwk.kkstudio.harness.daemon.identity;

import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

/**
 * Environment 的持久身份组件：在环境根下原子创建并复用唯一的 canonical UUID 身份文件。
 *
 * <p>环境根必须是已存在的真实目录（{@code toRealPath} 固定根）。全部身份读写都通过 {@link SecureDirectoryStream} 相对句柄 完成：环境根以
 * secure directory 打开（provider 不支持时失败关闭），{@code .kkstudio} 只作为根的直接子项创建/打开
 * （NOFOLLOW），身份文件的创建/读取全部相对该固定目录句柄，父目录替换/符号链接无法重定向读写。发布采用相对 {@code newByteChannel(CREATE_NEW,
 * WRITE, NOFOLLOW_LINKS)} 的 create-only 直接创建（无 temp/重开窗口），写入 canonical UUID+换行并
 * force，随后经同一句柄重新打开验证；并发失败方读取先到者。崩溃可能留下部分内容文件——严格读取会在下次启动 时确定性失败而不是静默重建（文档化，不自动再生）。读取使用小上限（64 字节）的
 * NOFOLLOW 相对通道并先取 regular-file 属性， 符号链接/竞态替换/超限/损坏全部拒绝。
 */
public final class EnvironmentIdentity {

  /** 身份文件名。 */
  public static final String FILE_NAME = "environment-id";

  /** 身份文件字节上限：canonical UUID（36）＋换行（CRLF 共 38），留足余量后仍远小于任何合法内容。 */
  private static final int MAX_IDENTITY_FILE_BYTES = 64;

  private static final Set<PosixFilePermission> PRIVATE_DIRECTORY =
      Set.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE);
  private static final Set<PosixFilePermission> PRIVATE_FILE =
      Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private EnvironmentIdentity() {}

  /**
   * 加载或原子创建环境身份。
   *
   * @param environmentRoot 环境根目录（必须已存在且为真实目录）；身份文件写入其 {@code .kkstudio} 子目录。
   * @return 文件的 canonical EnvironmentId；并发调用方收敛到同一值。
   * @throws IllegalStateException 环境根不存在/非目录、provider 不支持 secure directory、{@code .kkstudio} 为符号链接/
   *     非目录、身份文件不可读、为符号链接或内容非规范 UUID 时。
   */
  public static EnvironmentId loadOrCreate(Path environmentRoot) {
    Path root =
        Objects.requireNonNull(environmentRoot, "environmentRoot").toAbsolutePath().normalize();
    try {
      // 环境根必须已存在：toRealPath 解析并固定真实根。
      if (!Files.isDirectory(root)) {
        throw new IllegalStateException("environment root must be an existing directory: " + root);
      }
      root = root.toRealPath();
    } catch (IOException error) {
      throw new IllegalStateException(
          "environment root must be an existing directory: " + root, error);
    }
    try (SecureDirectoryStream<Path> rootStream = openSecureDirectory(root)) {
      // .kkstudio 只作为根的直接子项创建（已存在则跳过，由随后的 NOFOLLOW 打开严格校验）。
      try {
        Files.createDirectory(root.resolve(".kkstudio"));
      } catch (FileAlreadyExistsException ignored) {
        // 已存在（并发创建或预置）：继续。
      }
      try (SecureDirectoryStream<Path> identityStream =
          openChildDirectory(rootStream, ".kkstudio")) {
        makePrivateDirectory(identityStream);
        // create-only 发布：已存在（预置/并发先到者/重启复用）时保留先到者并由失败方读取；先到者写入完成前文件可能短暂
        // 部分可见，失败方做有界重试，持续损坏（崩溃残留）最终失败关闭，不自动再生。
        boolean published = publishNewIdentity(identityStream, FILE_NAME);
        return published
            ? read(identityStream, FILE_NAME)
            : readAfterConcurrentPublish(identityStream, FILE_NAME);
      }
    } catch (IOException error) {
      throw new IllegalStateException(
          "cannot create or read environment identity file in " + root, error);
    }
  }

  /**
   * 打开真实目录的 secure directory 句柄；provider 不支持时失败关闭（{@link IllegalStateException}）。包私有以便同包测试直接验证
   * 句柄语义。
   */
  static SecureDirectoryStream<Path> openSecureDirectory(Path directory) throws IOException {
    DirectoryStream<Path> stream = Files.newDirectoryStream(directory);
    if (!(stream instanceof SecureDirectoryStream<Path> secure)) {
      stream.close();
      throw new IllegalStateException(
          "environment root does not support secure directory streams: " + directory);
    }
    return secure;
  }

  /** 相对父句柄以 NOFOLLOW 打开直接子目录：符号链接/非目录/父目录被替换都会确定性失败，无法把读写重定向到其他位置。包私有以便 同包测试直接验证父目录替换/符号链接场景。 */
  static SecureDirectoryStream<Path> openChildDirectory(
      SecureDirectoryStream<Path> parent, String name) throws IOException {
    return parent.newDirectoryStream(Path.of(name), LinkOption.NOFOLLOW_LINKS);
  }

  /** 相对句柄的 NOFOLLOW 属性读取；视图不可用按失败处理。 */
  private static BasicFileAttributes readAttributes(SecureDirectoryStream<Path> stream, Path name)
      throws IOException {
    BasicFileAttributeView view =
        stream.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (view == null) {
      throw new IOException("secure directory does not expose basic attributes for " + name);
    }
    return view.readAttributes();
  }

  /**
   * create-only 发布：相对句柄单次 {@code newByteChannel(CREATE_NEW, WRITE, NOFOLLOW_LINKS)} 直接创建身份文件 （无
   * temp/重开窗口），写入 canonical UUID+换行并 force。目标已存在（并发先到者/外部占据/符号链接）时吞掉冲突并保留已有
   * 文件；崩溃可能留下部分内容文件，由严格读取在下次启动时确定性失败（文档化，不自动再生）。包私有以便同包测试直接验证 create-only 语义。
   *
   * @return {@code true} 表示本调用创建成功；{@code false} 表示目标已存在（并发先到者），由调用方读取先到者内容。
   */
  static boolean publishNewIdentity(SecureDirectoryStream<Path> stream, String fileName)
      throws IOException {
    byte[] content = (UUID.randomUUID().toString() + "\n").getBytes(StandardCharsets.US_ASCII);
    try (SeekableByteChannel channel =
        stream.newByteChannel(
            Path.of(fileName),
            Set.of(
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            privateFileAttributes())) {
      ByteBuffer buffer = ByteBuffer.wrap(content);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      if (channel instanceof FileChannel fileChannel) {
        fileChannel.force(true);
      }
      return true;
    } catch (FileAlreadyExistsException ignored) {
      // 并发启动：先到者生效，随后统一重读验证。
      return false;
    }
  }

  /** 并发创建失败方读取：先到者写入完成前文件可能短暂部分可见；以有界次数重试，持续损坏（崩溃残留）最终失败关闭，不自动再生。 */
  private static EnvironmentId readAfterConcurrentPublish(
      SecureDirectoryStream<Path> stream, String fileName) throws IOException {
    IllegalStateException lastMalformed = null;
    for (int attempt = 0; attempt < 10; attempt++) {
      try {
        return read(stream, fileName);
      } catch (IllegalStateException malformed) {
        lastMalformed = malformed;
        LockSupport.parkNanos(2_000_000L);
      }
    }
    throw lastMalformed;
  }

  private static FileAttribute<?>[] privateFileAttributes() {
    try {
      return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PRIVATE_FILE)};
    } catch (UnsupportedOperationException error) {
      // 非 POSIX 文件系统：尽力而为，不阻断启动。
      return new FileAttribute<?>[0];
    }
  }

  /** 目录权限收紧为 owner-only；非 POSIX 文件系统上尽力而为。 */
  private static void makePrivateDirectory(SecureDirectoryStream<Path> stream) {
    try {
      PosixFileAttributeView view = stream.getFileAttributeView(PosixFileAttributeView.class);
      if (view != null) {
        view.setPermissions(PRIVATE_DIRECTORY);
      }
    } catch (UnsupportedOperationException | IOException ignored) {
      // 非 POSIX 文件系统上尽力而为，不阻断启动。
    }
  }

  /**
   * 严格读取身份文件：相对句柄先取 NOFOLLOW regular-file 属性（符号链接立即拒绝），再以小上限 NOFOLLOW 相对通道读取精确
   * 字节；UTF-8、允许单个尾部换行、内容必须是 canonical 小写 UUID。包私有以便同包测试直接验证句柄级读取契约。
   */
  static EnvironmentId read(SecureDirectoryStream<Path> stream, String fileName)
      throws IOException {
    Path name = Path.of(fileName);
    BasicFileAttributes attributes = readAttributes(stream, name);
    if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
      throw new IllegalStateException(
          "environment identity file must be a regular file: " + fileName);
    }
    byte[] content;
    try (SeekableByteChannel channel =
        stream.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      long size = channel.size();
      if (size > MAX_IDENTITY_FILE_BYTES) {
        throw new IOException(
            "environment identity file is too large: " + size + " > " + MAX_IDENTITY_FILE_BYTES);
      }
      content = new byte[(int) size];
      ByteBuffer buffer = ByteBuffer.wrap(content);
      while (buffer.hasRemaining()) {
        int read = channel.read(buffer);
        if (read < 0) {
          throw new IOException("environment identity file truncated while reading: " + fileName);
        }
      }
    }
    String text = new String(content, StandardCharsets.UTF_8);
    if (text.endsWith("\n")) {
      text = text.substring(0, text.length() - 1);
      if (text.endsWith("\r")) {
        text = text.substring(0, text.length() - 1);
      }
    }
    try {
      return new EnvironmentId(text);
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException(
          "environment identity file is malformed (expected one canonical lowercase UUID): "
              + fileName,
          error);
    }
  }
}
