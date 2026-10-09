package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 受控临时 workspace 的创建登记、in-use 租约与清扫：每个工具临时 workspace 是一个带不可变 {@code createdAt} 登记的子目录。
 *
 * <p>布局为 {@code <tmp>/workspaces/<uuid>/}，其中 {@code <uuid>/created-at} 以 CREATE_NEW 写入一次创建时刻（epoch
 * 秒），此后不再改写；清扫一律读取该登记，绝不用目录 ctime/mtime 猜测创建时间。
 *
 * <p>创建登记、in-use 获取（{@link #create()}/{@link #acquire(Path)}）与清扫（{@link
 * #sweep(long)}）的关键段全部由同一把锁串行化， 因此不存在「created-at 已写好但 lease 尚未登记」或「读取已确认受控但 lease
 * 尚未计数」的窗口：清扫绝不删除正在创建或正在读取的 workspace。
 *
 * <p>清扫只遍历本根下、名字是合法 UUID、且带合法 {@code created-at} 登记的直接子目录：符号链接（reparse）因 {@code NOFOLLOW_LINKS}
 * 判定为非目录而被跳过，名字不是 UUID 或缺少登记条目的目录一律不视为受控 workspace，因此不会被删除。skills/backup/用户项目以及旧 {@code
 * resources}/日志都不在本根下，天然不参与清扫。
 */
final class TemporaryResourceStore {

  private static final Logger LOG = System.getLogger(TemporaryResourceStore.class.getName());

  private static final String WORKSPACES = "workspaces";
  private static final String CREATED_AT_FILE = "created-at";

  private final Path root;
  private final Path trustedRoot;
  private final Clock clock;

  /** workspaceId -> 活动租约计数；存在即表示 workspace 正在被使用，清扫必须跳过。 */
  private final ConcurrentHashMap<UUID, AtomicInteger> leases = new ConcurrentHashMap<>();

  /** 串行化创建登记、in-use 获取与清扫的关键段，消除「登记与租约之间」的删除窗口。 */
  private final ReentrantLock lock = new ReentrantLock();

  private TemporaryResourceStore(Path root, Path trustedRoot, Clock clock) {
    this.root = root;
    this.trustedRoot = trustedRoot;
    this.clock = clock;
  }

  /** 打开（必要时创建）受控临时 workspace 根 {@code <tmpRoot>/workspaces}。 */
  static TemporaryResourceStore open(Path tmpRoot) {
    return open(tmpRoot, Clock.systemUTC());
  }

  /**
   * 使用显式时钟打开，便于确定性清扫测试。
   *
   * <p>可信根取 {@code tmpRoot} 的父目录（数据根），它必须已由调用方 canonical 化；本类只拒绝可信根之下（{@code tmpRoot} 起）的
   * 符号链接/reparse，从而容忍 OS 标准前缀 alias（macOS {@code /var}、{@code /tmp}）。
   */
  static TemporaryResourceStore open(Path tmpRoot, Clock clock) {
    Path tmp = Objects.requireNonNull(tmpRoot, "tmpRoot").toAbsolutePath().normalize();
    Path trustedRoot = tmp.getParent();
    if (trustedRoot == null) {
      throw new IllegalArgumentException("temporary root must have a parent: " + tmp);
    }
    try {
      Path root = OwnerOnlyFiles.ensureOwnerOnlyDirectory(trustedRoot, tmp.resolve(WORKSPACES));
      return new TemporaryResourceStore(root, trustedRoot, Objects.requireNonNull(clock, "clock"));
    } catch (IOException error) {
      throw new UncheckedIOException("cannot open temporary workspace root: " + tmp, error);
    }
  }

  /** 受控临时 workspace 根。 */
  Path root() {
    return root;
  }

  /** 已 canonical 的可信数据根：owner-only 组件 NOFOLLOW 检查只在该根之下执行。 */
  Path trustedRoot() {
    return trustedRoot;
  }

  /** 登记并返回一个新的 workspace，同时持有其 in-use lease，直到 {@link Workspace#close()}。 */
  Workspace create() throws IOException {
    UUID id = UUID.randomUUID();
    Path directory = root.resolve(id.toString());
    lock.lock();
    try {
      OwnerOnlyFiles.createOwnerOnlyDirectory(trustedRoot, directory);
      try {
        writeCreatedAt(directory);
      } catch (IOException error) {
        deleteOnRegistrationFailure(directory, error);
        throw error;
      }
      leases.put(id, new AtomicInteger(1));
      return new Workspace(id, directory);
    } finally {
      lock.unlock();
    }
  }

  /**
   * 若 {@code candidate} 位于某个已登记受控 workspace 内，则获取其 in-use lease 并返回；否则返回 no-op。
   *
   * <p>与 {@link #sweep(long)} 的关键段由同一把锁互斥：读取受控临时全文期间持有的 lease 保证清扫不会删除在途读取的 workspace。
   */
  TextOutputStore.Lease acquire(Path candidate) {
    Path normalized = candidate.toAbsolutePath().normalize();
    if (!normalized.startsWith(root)) {
      return TextOutputStore.Lease.NONE;
    }
    Path relative = root.relativize(normalized);
    Path workspace = root.resolve(relative.getName(0));
    UUID id;
    try {
      id = UUID.fromString(workspace.getFileName().toString());
    } catch (IllegalArgumentException notWorkspace) {
      return TextOutputStore.Lease.NONE;
    }
    lock.lock();
    try {
      if (!isControlledWorkspace(workspace)) {
        return TextOutputStore.Lease.NONE;
      }
      leases.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
    } finally {
      lock.unlock();
    }
    AtomicBoolean released = new AtomicBoolean();
    return () -> {
      if (released.compareAndSet(false, true)) {
        release(id);
      }
    };
  }

  /**
   * 清扫超过 ttl 且未租用的已登记 workspace，返回删除数量。
   *
   * <p>只处理受控 workspace；任何不满足登记形状的条目都跳过，保证不清扫 skills/backup/用户项目或旧资源/日志。
   */
  int sweep(long ttlSeconds) {
    if (ttlSeconds <= 0) {
      throw new IllegalArgumentException("ttlSeconds must be positive");
    }
    long cutoff = clock.instant().getEpochSecond() - ttlSeconds;
    int removed = 0;
    lock.lock();
    try {
      requireControlledRoot();
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
        for (Path entry : entries) {
          if (!isControlledWorkspace(entry)) {
            continue;
          }
          UUID id = UUID.fromString(entry.getFileName().toString());
          if (leases.containsKey(id)) {
            continue;
          }
          Long createdAt = readCreatedAt(entry);
          if (createdAt == null || createdAt > cutoff) {
            continue;
          }
          if (deleteRecursively(entry)) {
            removed++;
          }
        }
      }
    } catch (IOException error) {
      throw new UncheckedIOException("cannot scan temporary workspace root: " + root, error);
    } finally {
      lock.unlock();
    }
    return removed;
  }

  private void release(UUID id) {
    lock.lock();
    try {
      leases.computeIfPresent(
          id, (key, counter) -> counter.decrementAndGet() <= 0 ? null : counter);
    } finally {
      lock.unlock();
    }
  }

  /**
   * 受控根必须是真实目录：被删除、或被人替换成符号链接/reparse 时立即失败，绝不跟随链接扫描根外内容。
   *
   * <p>这是对本类自身根的直接事实校验，不是通用防护框架；只覆盖「root 被替换后仍被当作受控根使用」这一条边界。
   */
  private void requireControlledRoot() {
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("temporary workspace root is not a real directory: " + root);
    }
  }

  /** 登记失败后的清理：清理自身失败必须作为 suppressed 附加，绝不吞掉第二个故障。 */
  private static void deleteOnRegistrationFailure(Path directory, IOException failure) {
    try {
      deleteTree(directory);
    } catch (IOException cleanupError) {
      failure.addSuppressed(cleanupError);
    }
  }

  /** 判断条目是否为受控 workspace：直接子目录、名字是 UUID、且带合法 created-at 登记。 */
  private boolean isControlledWorkspace(Path entry) {
    if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    try {
      UUID.fromString(entry.getFileName().toString());
    } catch (IllegalArgumentException notWorkspace) {
      return false;
    }
    return readCreatedAt(entry) != null;
  }

  private void writeCreatedAt(Path directory) throws IOException {
    Path meta = directory.resolve(CREATED_AT_FILE);
    OwnerOnlyFiles.createOwnerOnlyFile(trustedRoot, meta);
    Files.writeString(
        meta,
        Long.toString(clock.instant().getEpochSecond()),
        StandardCharsets.US_ASCII,
        StandardOpenOption.WRITE);
  }

  private static Long readCreatedAt(Path directory) {
    Path meta = directory.resolve(CREATED_AT_FILE);
    try {
      if (!Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS)) {
        return null;
      }
      return Long.parseLong(Files.readString(meta, StandardCharsets.US_ASCII).trim());
    } catch (IOException | NumberFormatException error) {
      return null;
    }
  }

  /**
   * 唯一的递归删除实现：本类所有 workspace 清理都走这一处遍历，避免重复实现漂移。
   *
   * <p>删除失败以 {@link IOException} 抛出，由调用方按语义决定处理方式（warn、suppressed 或忽略），本方法自身绝不吞掉故障。
   */
  private static void deleteTree(Path directory) throws IOException {
    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException error)
              throws IOException {
            if (error != null) {
              throw error;
            }
            Files.deleteIfExists(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /** 清扫路径的删除：失败记录具体 workspace 路径的 warn，保留资源待下轮收敛。 */
  private static boolean deleteRecursively(Path directory) {
    try {
      deleteTree(directory);
      return true;
    } catch (IOException error) {
      LOG.log(
          Level.WARNING,
          "cannot delete expired temporary workspace " + directory + ": " + error.getMessage());
      return false;
    }
  }

  /**
   * 活动 workspace 句柄：持有 in-use lease，{@link #close()} 释放且保证恰好一次。
   *
   * <p>句柄关闭必须原子一次：`create` 的租约与读者 {@code acquire} 的租约共享同一计数，重复 close 若各递减一次会提前清空计数，让清扫误删仍在读取的
   * workspace。
   */
  final class Workspace implements AutoCloseable {

    private final UUID id;
    private final Path directory;
    private final AtomicBoolean released = new AtomicBoolean();

    private Workspace(UUID id, Path directory) {
      this.id = id;
      this.directory = directory;
    }

    UUID id() {
      return id;
    }

    Path directory() {
      return directory;
    }

    /** 释放 in-use lease；原子一次，重复调用无副作用。 */
    @Override
    public void close() {
      if (released.compareAndSet(false, true)) {
        release(id);
      }
    }
  }

  /** 删除一个已不再需要的中转 workspace（含创建登记）：发布失败/取消后 best-effort 立即收敛，失败记录路径 warn 并由后续清扫兜底。 */
  void deleteWorkspace(Path directory) {
    try {
      deleteTree(directory);
    } catch (IOException error) {
      LOG.log(
          Level.WARNING,
          "cannot delete temporary workspace " + directory + ": " + error.getMessage());
    }
  }

  /** 当前是否仍有活动租约；仅用于测试断言。 */
  boolean hasActiveLease(UUID id) {
    return leases.containsKey(id);
  }
}
