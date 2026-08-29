package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceRef;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 独立 Daemon 的私有本地 resource store：内容寻址的不可变文件导出根。
 *
 * <p>{@code store} 只在私有扁平导出根目录下写入 SHA-256 命名的普通文件：temp 使用同根随机名并以单次 {@code
 * FileChannel.open(CREATE_NEW, WRITE, NOFOLLOW_LINKS)} 创建（杜绝 createTempFile 后重开竞态），写入后 force
 * 并记录文件身份（fileKey）；发布前验证 temp 仍是同一 NOFOLLOW 普通文件。发布采用 create-only（hard link 原子失败于
 * 已存在目标，失败时保留先到者；不支持 hard link 的文件系统退化为 create-only copy）。发布后无论自己胜出还是冲突收敛，都必须以 单个 NOFOLLOW_LINKS
 * FileChannel 重新打开并精确验证最终目标的 size/sha；若自己胜出但验证失败（发布后即被篡改），仅当目标 与 temp 仍是同一 inode
 * 时才清理，绝不删除无关并发目标；temp 保留到最终验证完成再由调用方统一清理。构造期固定真实根并记录根 fileKey，每次操作前验证根身份未被替换（不声称防御任意同 UID
 * 竞态，但关闭操作前替换与 create/reopen 利用）。
 *
 * <p>返回的 ref 是携带非空 size/sha 的规范 {@code file:///} URI。{@code read} 只解析本组件生成的 digest 名文件：
 * root-pinned、拒绝符号链接与路径穿越，并以 NOFOLLOW_LINKS FileChannel 先取精确 size 再分配，读取完成后复查最终 size 以检测并发增长。单个
 * resource 的声明与实际字节数都不得超过 {@link #DEFAULT_MAX_RESOURCE_BYTES}（默认 8 MiB，与 gateway 16 MiB
 * 字符入站上限对齐——Base64 编码后约 10.7 MiB 字符，可单条入站承载）。
 */
public final class LocalFileResourceStore implements ResourceStore {

  private static final Pattern DIGEST_NAME = Pattern.compile("[0-9a-f]{64}");

  /** 默认单资源字节上限：8 MiB，Base64 后约 10.7 MiB 字符，适配 gateway 默认 16 MiB 入站文本上限。 */
  public static final long DEFAULT_MAX_RESOURCE_BYTES = 8L * 1024 * 1024;

  private final Path directory;
  private final long maxResourceBytes;
  private final Object directoryKey;

  public LocalFileResourceStore(Path directory) {
    this(directory, DEFAULT_MAX_RESOURCE_BYTES);
  }

  /**
   * @param maxResourceBytes 单个 resource 的声明/实际字节数上限，必须为正。
   */
  public LocalFileResourceStore(Path directory, long maxResourceBytes) {
    if (maxResourceBytes <= 0) {
      throw new IllegalArgumentException("maxResourceBytes must be positive");
    }
    this.maxResourceBytes = maxResourceBytes;
    Path absolute = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    try {
      Files.createDirectories(absolute);
      // 固定真实根：此后所有所有权判断都基于解析后的规范路径，导出根自身的符号链接在构造期即被解析。
      this.directory = absolute.toRealPath();
      // 记录根身份（inode）：每次操作前验证根未被替换。
      this.directoryKey = fileKeyOf(this.directory);
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "cannot create or canonicalize resource export root: " + directory, error);
    }
    // 导出根必须能渲染为协议接受的规范 file URI（无空格/非 ASCII/需转义字符），否则 store 生成的 ref 永远无法通过 ResourceRef 校验。
    URI probe = this.directory.resolve("probe").toUri();
    try {
      new DaemonResourceRef(
          probe.toString(), "application/octet-stream", null, 0L, sha256Hex(new byte[0]));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "resource export root cannot produce a canonical file resource URI: " + directory, error);
    }
  }

  /** 每次操作前验证导出根未被替换：NOFOLLOW 属性必须仍是构造期固定的同一目录 inode。 */
  private void verifyRootIdentity() throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink()
        || !attributes.isDirectory()
        || !Objects.equals(attributes.fileKey(), directoryKey)) {
      throw new IOException("resource export root was replaced: " + directory);
    }
  }

  /** 读取路径的 NOFOLLOW fileKey（文件身份）；符号链接/缺失会失败。包私有以便同包测试。 */
  static Object fileKeyOf(Path path) throws IOException {
    return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
        .fileKey();
  }

  /**
   * 创建同根随机名 temp 并单次打开写入：{@code CREATE_NEW} 原子独占，杜绝“createTempFile 后重开”的替换窗口；写入完成后 force
   * 再关闭。包私有以便同包测试直接验证 temp 身份语义。
   */
  static Path writeOwnedTemp(Path directory, byte[] bytes) throws IOException {
    for (int attempt = 0; attempt < 10; attempt++) {
      Path candidate = directory.resolve(".resource-" + UUID.randomUUID() + ".tmp");
      try (FileChannel channel =
          FileChannel.open(
              candidate,
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
        return candidate;
      } catch (FileAlreadyExistsException error) {
        // 随机名碰撞：重试。
      }
    }
    throw new IOException("cannot create a unique resource temp file in " + directory);
  }

  /** 发布前验证 temp 仍是记录的同一 NOFOLLOW 普通文件（fileKey 一致）；被替换/删除时确定性失败。 */
  void verifyOwnedTemp(Path temp, Object fileKey) throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(temp, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
      throw new IOException("resource temp file was replaced by a non-regular file: " + temp);
    }
    if (!Objects.equals(attributes.fileKey(), fileKey)) {
      throw new IOException("resource temp file was replaced: " + temp);
    }
  }

  /**
   * 仅当 {@code target} 与 {@code reference} 是同一 NOFOLLOW 普通文件（同一 inode）时删除 {@code target}；绝不删除无关
   * 并发目标。用于“自己发布的目标未通过最终验证”时的防御性清理（check-then-delete 的残余窗口属于同 UID 竞态范畴，本方法不声称
   * 防御）。包私有以便同包测试直接验证清理边界。
   */
  static void deleteTargetIfSameInode(Path reference, Path target) throws IOException {
    BasicFileAttributes referenceAttributes =
        Files.readAttributes(reference, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    BasicFileAttributes targetAttributes =
        Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (referenceAttributes.isRegularFile()
        && targetAttributes.isRegularFile()
        && !targetAttributes.isSymbolicLink()
        && Objects.equals(referenceAttributes.fileKey(), targetAttributes.fileKey())) {
      Files.delete(target);
    }
  }

  @Override
  public DaemonResourceRef store(byte[] bytes, String mediaType) throws IOException {
    Objects.requireNonNull(bytes, "bytes");
    if (bytes.length > maxResourceBytes) {
      throw new IllegalArgumentException(
          "resource bytes exceed maxResourceBytes: " + bytes.length + " > " + maxResourceBytes);
    }
    if (mediaType == null || mediaType.isBlank()) {
      throw new IllegalArgumentException("mediaType must not be blank");
    }
    verifyRootIdentity();
    String digest = sha256Hex(bytes);
    Path target = directory.resolve(digest);
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
        // digest 名被目录/符号链接等非普通文件占据：拒绝而不是静默覆盖，保持只读导出的完整性契约。
        throw new IOException("resource digest name is occupied by a non-regular file: " + target);
      }
      // 复用前精确校验既有 digest 文件（size/sha），任何分配之前完成。
      verifyDigestFile(target, bytes.length, digest);
      return new DaemonResourceRef(
          target.toUri().toString(), mediaType, null, (long) bytes.length, digest);
    }
    Path temp = writeOwnedTemp(directory, bytes);
    Object tempKey = fileKeyOf(temp);
    boolean published = false;
    try {
      verifyOwnedTemp(temp, tempKey);
      published = publish(temp, target);
      // 无论自己发布成功还是并发冲突收敛到先到者，都必须重新打开并精确验证最终目标：发布后即被篡改/损坏时确定性失败。
      verifyDigestFile(target, bytes.length, digest);
    } catch (IOException error) {
      if (published) {
        // 自己发布的目标未通过验证：仅当目标仍是与 temp 同一 inode（原地篡改）时清理，绝不删除无关并发目标。
        deleteTargetIfSameInode(temp, target);
      }
      throw error;
    } finally {
      // temp 保留到最终目标验证完成后再清理。
      Files.deleteIfExists(temp);
    }
    return new DaemonResourceRef(
        target.toUri().toString(), mediaType, null, (long) bytes.length, digest);
  }

  @Override
  public byte[] read(DaemonResourceRef ref) throws IOException {
    Objects.requireNonNull(ref, "ref");
    verifyRootIdentity();
    if (ref.size() != null && ref.size() > maxResourceBytes) {
      throw new IOException(
          "declared resource size exceeds maxResourceBytes: "
              + ref.size()
              + " > "
              + maxResourceBytes);
    }
    Path resolved = resolveOwned(ref);
    // NOFOLLOW_LINKS 打开：即使解析与打开之间文件被换成符号链接，也会确定性失败而不是跟随读取。
    try (FileChannel channel =
        FileChannel.open(resolved, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      long actual = channel.size();
      if (actual > maxResourceBytes || actual > Integer.MAX_VALUE) {
        throw new IOException(
            "resource size exceeds the readable limit: " + actual + " > " + maxResourceBytes);
      }
      if (ref.size() == null || actual != ref.size()) {
        throw new IOException(
            "resource size mismatch for "
                + ref.uri()
                + ": declared="
                + ref.size()
                + " actual="
                + actual);
      }
      byte[] bytes = new byte[(int) actual];
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        int read = channel.read(buffer);
        if (read < 0) {
          throw new IOException("resource truncated while reading: " + ref.uri());
        }
      }
      // 读取完成后复查最终 size：并发增长/追加必须拒绝（不可变内容契约）。
      if (channel.size() != actual) {
        throw new IOException("resource size changed while reading: " + ref.uri());
      }
      if (ref.sha256() == null || !ref.sha256().equals(sha256Hex(bytes))) {
        throw new IOException("resource sha256 mismatch for " + ref.uri());
      }
      return bytes;
    }
  }

  /** 返回本 store 使用的私有导出根（canonical 真实路径）。 */
  public Path directory() {
    return directory;
  }

  /**
   * 以 create-only 方式发布 digest 文件：hard link 原子失败于已存在目标（任意类型），杜绝并发写入互相覆盖；不支持 hard link 的文件系统退化为
   * create-only copy（默认不替换已存在目标）。发布后保留先到者文件；temp 由调用方在最终目标验证完成后清理。包私有以便同包测试直接验证 冲突收敛与获胜者报告契约。
   *
   * @return {@code true} 表示本次调用发布的文件胜出；{@code false} 表示目标已存在（并发先到者或外部占用），已有文件保留。
   */
  boolean publish(Path temp, Path target) throws IOException {
    try {
      Files.createLink(target, temp);
      return true;
    } catch (FileAlreadyExistsException ignored) {
      // 并发同内容写入：digest 命名下已存在即内容相同，保留已有文件。
      return false;
    } catch (UnsupportedOperationException error) {
      // 不支持 hard link 的文件系统：退化为 create-only copy（不覆盖先到者）。
      return copyCreateOnly(temp, target);
    }
  }

  /**
   * create-only copy 回退：默认不替换已存在目标（O_EXCL 语义），已存在（并发先到者/外部占用）时保留已有文件并返回 {@code
   * false}，不会覆盖任何内容。包私有以便同包测试直接验证回退机制的 create-only 语义。
   */
  static boolean copyCreateOnly(Path temp, Path target) throws IOException {
    try {
      Files.copy(temp, target);
      return true;
    } catch (FileAlreadyExistsException ignored) {
      return false;
    }
  }

  /**
   * 精确验证 digest 文件：以单个 NOFOLLOW_LINKS FileChannel 打开，size 必须精确匹配、内容摘要必须匹配；在取到精确 size
   * 之前不做任何分配，读取完成后复查最终 size 以检测并发增长。包私有以便同包测试确定性注入“发布后篡改/错误的并发获胜者”场景。
   */
  void verifyDigestFile(Path target, long expectedSize, String expectedSha) throws IOException {
    if (expectedSize > Integer.MAX_VALUE) {
      throw new IOException("digest file size exceeds the readable limit: " + expectedSize);
    }
    try (FileChannel channel =
        FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      long actual = channel.size();
      if (actual != expectedSize) {
        throw new IOException(
            "digest file size mismatch for "
                + target
                + ": expected="
                + expectedSize
                + " actual="
                + actual);
      }
      byte[] bytes = new byte[(int) actual];
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        int read = channel.read(buffer);
        if (read < 0) {
          throw new IOException("digest file truncated while verifying: " + target);
        }
      }
      if (channel.size() != actual) {
        throw new IOException("digest file size changed while verifying: " + target);
      }
      if (!expectedSha.equals(sha256Hex(bytes))) {
        throw new IOException("digest file content mismatch for " + target);
      }
    }
  }

  /**
   * 将 {@code ref.uri()} 解析为本组件导出的 digest 文件：URI 路径必须位于导出根之下且文件名必须为本组件生成的 64 位小写 hex
   * digest；拒绝符号链接以及任何越出导出根的解析结果。
   *
   * <p>{@link ResourceRef} 构造期已保证 canonical {@code file:///}（无 authority/query/fragment、无 dot/空
   * segment），此处只校验 scheme 与所有权。导出根在构造期已创建并固定为真实路径，{@code toUri()} 必然以 {@code '/'} 结尾。
   */
  private Path resolveOwned(DaemonResourceRef ref) throws IOException {
    URI uri = URI.create(ref.uri());
    if (!"file".equals(uri.getScheme())) {
      throw new IOException("resource is not a local file resource: " + ref.uri());
    }
    String rawPath = uri.getRawPath();
    String exportPath = directory.toUri().getRawPath();
    if (rawPath == null || !rawPath.startsWith(exportPath)) {
      throw new IOException("resource resolves outside export root: " + ref.uri());
    }
    String relative = rawPath.substring(exportPath.length());
    if (!DIGEST_NAME.matcher(relative).matches()) {
      throw new IOException("resource is not owned by this store: " + ref.uri());
    }
    Path candidate = directory.resolve(relative);
    if (Files.isSymbolicLink(candidate)) {
      throw new IOException("resource must not be a symbolic link: " + ref.uri());
    }
    Path canonicalDirectory = directory;
    Path canonical = candidate.toRealPath();
    if (!canonical.startsWith(canonicalDirectory)) {
      throw new IOException("resource resolves outside export root: " + ref.uri());
    }
    return canonical;
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }
}
