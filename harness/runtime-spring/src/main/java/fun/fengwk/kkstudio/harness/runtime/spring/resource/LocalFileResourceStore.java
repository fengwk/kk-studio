package fun.fengwk.kkstudio.harness.runtime.spring.resource;

import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;

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
import java.util.concurrent.ThreadLocalRandom;

/**
 * 根目录固定的本地文件 {@link ResourceStore}。
 *
 * <p>对象以 SHA-256 十六进制（64 位小写）为扁平文件名存放在固定根目录下。写路径是「NOFOLLOW/CREATE_NEW 通道写临时文件（写后 force 落盘）+ 硬链接发布」的
 * create-only 发布：硬链接创建原子且绝不替换已存在目标（Linux 的 ATOMIC_MOVE 语义会覆盖目标，不能用于
 * create-only）。无论自有发布、并发冲突（FileAlreadyExistsException）还是已存在复用，返回引用前都必须对最终目标做一次精确 size + sha 校验（同一条
 * NOFOLLOW FileChannel，EOF 提前结束或多出字节均视为失败），校验失败视为损坏并抛错，绝不覆盖；并发写同一内容时恰好
 * 一个获胜者创建对象，其余全部走同一最终校验复用。读取只接受 parent 恰为 pinned root 且文件名等于 sha256 的规范 file 引用，以 NOFOLLOW_LINKS
 * 打开并校验 size 与摘要，精确读取声明的字节数（既不短读也不多读）。构造时要求根目录解析为规范绝对真实目录，且其自身 能产生 ResourceRef 兼容（ASCII、无百分号）的 file
 * URI，否则 fail fast。
 *
 * <p>非法输入（null、超限 content、非 file 引用、根目录之外/嵌套路径等）抛 {@link IllegalArgumentException}；存储/IO
 * 失败与损坏（size/摘要不匹配、符号链接、非普通文件、文件系统不支持硬链接）抛 {@link IllegalStateException}。
 */
public final class LocalFileResourceStore implements ResourceStore {

  private static final String TEMP_PREFIX = ".kk-resource-";
  private static final String TEMP_SUFFIX = ".tmp";

  private final Path root;
  private final int maxBytes;

  public LocalFileResourceStore(Path root, int maxBytes) {
    if (root == null) {
      throw new IllegalArgumentException("root must not be null");
    }
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    Path absolute = root.toAbsolutePath().normalize();
    if (!Files.exists(absolute)) {
      throw new IllegalArgumentException("root must be an existing real directory: " + root);
    }
    Path canonical;
    try {
      canonical = absolute.toRealPath();
    } catch (IOException error) {
      throw new IllegalStateException("cannot resolve root to a real path: " + root, error);
    }
    if (!Files.isDirectory(canonical)) {
      throw new IllegalArgumentException("root must be a real directory: " + root);
    }
    requireResourceRefCompatibleFileUri(canonical);
    this.root = canonical;
    this.maxBytes = maxBytes;
  }

  @Override
  public ResourceRef put(String mediaType, String name, byte[] content) {
    requireInput(mediaType, "mediaType");
    requireInput(content, "content");
    if (content.length > maxBytes) {
      throw new IllegalArgumentException("content must not exceed " + maxBytes + " bytes");
    }
    // 防御性拷贝：调用方之后修改原数组不得影响已存内容与摘要。
    byte[] payload = content.clone();
    String sha = sha256Hex(payload);
    Path target = root.resolve(sha);
    // 引用在写盘前构造：mediaType/name/uri/size/sha 全部输入校验先行，失败不产生任何存储副作用；与 reference 完全一致。
    ResourceRef ref = reference(mediaType, name, payload.length, sha);
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      // 已存在路径：不与写入竞争，但返回前仍需同一最终校验（对象损坏/被篡改不得复用）。
      verifyFinalTarget(target, payload.length, sha);
      return ref;
    }
    Path temp = null;
    try {
      temp = writeTempFile(root, payload);
      try {
        // create-only 发布：硬链接创建原子出现，且当目标已被并发创建时以 FileAlreadyExistsException 失败，绝不覆盖。
        Files.createLink(target, temp);
      } catch (FileAlreadyExistsException error) {
        // 并发写同一内容时另一获胜者已发布：走同一最终校验复用。
      } catch (UnsupportedOperationException error) {
        throw new IllegalStateException(
            "cannot publish resource " + sha + ": file system does not support hard links", error);
      }
    } catch (IOException error) {
      throw new IllegalStateException("cannot publish resource " + sha, error);
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
          // 临时文件清理尽力而为。
        }
      }
    }
    // 自有发布与并发冲突的公共收尾：返回引用前必须对最终目标做精确 size + sha 校验（同一 NOFOLLOW 通道）。
    verifyFinalTarget(target, payload.length, sha);
    return ref;
  }

  @Override
  public ResourceRef reference(String mediaType, String name, long size, String sha256) {
    requireInput(mediaType, "mediaType");
    requireInput(sha256, "sha256");
    if (size < 0) {
      throw new IllegalArgumentException("size must not be negative");
    }
    if (size > maxBytes) {
      throw new IllegalArgumentException("size must not exceed " + maxBytes + " bytes: " + sha256);
    }
    // 与 put 完全相同的规范引用构造路径（uri 由 sha 推导、ResourceRef 校验 mediaType/name/size/sha），但绝不触碰存储。
    return new ResourceRef(canonicalObjectUri(root.resolve(sha256)), mediaType, name, size, sha256);
  }

  @Override
  public byte[] read(ResourceRef resource) {
    requireInput(resource, "resource");
    URI uri = URI.create(resource.uri());
    if (!"file".equals(uri.getScheme())) {
      throw new IllegalArgumentException(
          "only canonical file resources are supported: " + resource.uri());
    }
    long declaredSize = resource.size();
    if (declaredSize > maxBytes) {
      throw new IllegalArgumentException(
          "resource size must not exceed " + maxBytes + " bytes: " + resource.uri());
    }
    Path path = Path.of(uri);
    Path parent = path.getParent();
    if (parent == null || !parent.equals(root)) {
      throw new IllegalArgumentException(
          "resource must live directly under the pinned root: " + resource.uri());
    }
    if (!path.getFileName().toString().equals(resource.sha256())) {
      throw new IllegalArgumentException(
          "resource file name must equal its sha256: " + resource.uri());
    }
    try {
      BasicFileAttributes attributes =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink()) {
        throw new IllegalStateException("resource entry must not be a symbolic link: " + path);
      }
      if (!attributes.isRegularFile()) {
        throw new IllegalStateException("resource entry must be a regular file: " + path);
      }
      if (attributes.size() != declaredSize) {
        throw new IllegalStateException(
            "resource entry size mismatch: declared "
                + declaredSize
                + " but stored "
                + attributes.size()
                + ": "
                + path);
      }
      byte[] content = readFully(path, Math.toIntExact(declaredSize));
      if (!resource.sha256().equals(sha256Hex(content))) {
        throw new IllegalStateException("resource entry digest mismatch: " + path);
      }
      return content;
    } catch (IOException error) {
      throw new IllegalStateException("cannot read resource " + path, error);
    }
  }

  /**
   * 最终发布校验：以单一 NOFOLLOW FileChannel 精确读取目标（EOF 提前结束或多出字节均视为失败）并核对 SHA-256。
   *
   * <p>{@link #put} 的所有返回路径（自有发布、并发冲突复用、已存在复用）都必须先经过该校验；符号链接 / 非普通文件在 NOFOLLOW 打开时 即失败。包私有：供并发冲突 /
   * 事后篡改场景的确定性测试直接调用。
   */
  static void verifyFinalTarget(Path target, int expectedSize, String expectedSha) {
    try (FileChannel channel =
        FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      byte[] stored = readExactly(channel, target, expectedSize);
      if (!expectedSha.equals(sha256Hex(stored))) {
        throw new IllegalStateException("resource entry digest mismatch: " + target);
      }
    } catch (IOException error) {
      throw new IllegalStateException("cannot verify resource entry " + target, error);
    }
  }

  /**
   * 以 NOFOLLOW/CREATE_NEW 通道写临时文件并 force 落盘：原子创建（已存在同名条目立即失败，绝不跟随符号链接），返回的临时文件与之后 硬链接发布的目标是同一个
   * inode。
   */
  private static Path writeTempFile(Path root, byte[] payload) throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(payload);
    for (int attempt = 0; attempt < 3; attempt++) {
      Path temp =
          root.resolve(
              TEMP_PREFIX + Long.toHexString(ThreadLocalRandom.current().nextLong()) + TEMP_SUFFIX);
      try (FileChannel channel =
          FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)) {
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
        return temp;
      } catch (FileAlreadyExistsException collision) {
        // 名称碰撞（极低概率）：换名重试。
      }
    }
    throw new IOException("cannot allocate a unique temp file in " + root);
  }

  /** 以 NOFOLLOW_LINKS 打开并精确读取 expectedSize 字节：文件提前结束视为 I/O 失败，多出字节同样视为 I/O 失败。 */
  private static byte[] readFully(Path path, int expectedSize) throws IOException {
    try (FileChannel channel =
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      return readExactly(channel, path, expectedSize);
    }
  }

  /** 从已打开的通道精确读取 expectedSize 字节：EOF 提前结束或多出字节均视为 I/O 失败。 */
  private static byte[] readExactly(FileChannel channel, Path path, int expectedSize)
      throws IOException {
    byte[] content = new byte[expectedSize];
    ByteBuffer buffer = ByteBuffer.wrap(content);
    while (buffer.hasRemaining()) {
      if (channel.read(buffer) < 0) {
        throw new IOException("unexpected end of file: " + path);
      }
    }
    // 精确读取：即使文件在 size 校验后被替换变长，也不得静默忽略多余字节。
    if (channel.read(ByteBuffer.wrap(new byte[1])) > 0) {
      throw new IOException("file is longer than declared size: " + path);
    }
    return content;
  }

  /**
   * 对象 URI：toUri() 对已存在的目录条目会附加尾部 '/'，这里归一为 ResourceRef 兼容的规范 file URI（目录条目随后由
   * verifyFinalTarget/read 以 IllegalStateException 拒绝）。
   */
  private static String canonicalObjectUri(Path target) {
    String uri = target.toUri().toASCIIString();
    if (uri.endsWith("/")) {
      return uri.substring(0, uri.length() - 1);
    }
    return uri;
  }

  /**
   * 根目录必须能产生 ResourceRef 兼容的 file URI：file:///、空 authority、ASCII 无百分号、无空/dot segment。真实目录的 toUri()
   * 以尾部 '/' 结尾，容忍这一个尾部分隔符。
   */
  private static void requireResourceRefCompatibleFileUri(Path root) {
    URI uri = root.toUri();
    String schemeSpecificPart = uri.getRawSchemeSpecificPart();
    String rawPath = uri.getRawPath();
    String objectPath = rawPath == null ? null : stripTrailingSlash(rawPath);
    boolean compatible =
        "file".equals(uri.getScheme())
            && uri.getQuery() == null
            && uri.getFragment() == null
            && schemeSpecificPart != null
            && schemeSpecificPart.startsWith("///")
            && rawPath != null
            && rawPath.indexOf('%') < 0
            && objectPath != null
            && objectPath.startsWith("/")
            && !objectPath.startsWith("//")
            && objectPath.length() > 1
            && !hasInvalidSegments(objectPath);
    if (!compatible) {
      throw new IllegalArgumentException(
          "root must produce a ResourceRef-compatible ASCII non-percent file URI: " + root);
    }
  }

  private static String stripTrailingSlash(String rawPath) {
    if (rawPath.endsWith("/")) {
      return rawPath.substring(0, rawPath.length() - 1);
    }
    return rawPath;
  }

  private static boolean hasInvalidSegments(String rawPath) {
    String[] segments = rawPath.split("/", -1);
    for (int index = 1; index < segments.length; index++) {
      String segment = segments[index];
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        return true;
      }
    }
    return false;
  }

  private static String sha256Hex(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available", error);
    }
  }

  private static void requireInput(Object value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
  }
}
