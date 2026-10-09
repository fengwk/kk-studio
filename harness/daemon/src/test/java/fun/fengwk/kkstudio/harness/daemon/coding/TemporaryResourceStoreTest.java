package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 针对 {@link TemporaryResourceStore} 的行为断言：不可变创建登记、TTL/扫描清扫、活动租约保护、符号链接与路径逃逸拒绝，以及重启后按登记清扫。
 *
 * <p>清扫策略是受控临时资源的正确性边界：它决定了“哪些临时 workspace 可以在何时被删除”，因此这里用显式时钟构造确定性时间推进，覆盖热更保留期、 活动租约与非受控条目。
 */
class TemporaryResourceStoreTest {

  @TempDir Path tmpRoot;

  /** 每个 workspace 都有不可变 created-at 登记，且两次创建得到不同目录。 */
  @Test
  void createRegistersImmutableCreatedAt() throws IOException {
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_000));
    TemporaryResourceStore store = TemporaryResourceStore.open(tmpRoot, clock);

    TemporaryResourceStore.Workspace first = store.create();
    Path meta = first.directory().resolve("created-at");
    assertTrue(Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS), "workspace 必须有创建登记");
    assertEquals("1000", Files.readString(meta).trim());

    // 时间推进后新建的 workspace 记录新时刻，旧登记不被改写。
    clock.advance(Duration.ofSeconds(500));
    TemporaryResourceStore.Workspace second = store.create();
    assertNotEquals(first.directory(), second.directory());
    assertEquals("1500", Files.readString(second.directory().resolve("created-at")).trim());
    assertEquals("1000", Files.readString(meta).trim(), "已登记的创建时刻不可变");
  }

  /** 超过保留期且未租用时被清扫；仍在保留期内不删。 */
  @Test
  void sweepRemovesOnlyExpiredUnleasedWorkspaces() throws IOException {
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(10_000));
    TemporaryResourceStore store = TemporaryResourceStore.open(tmpRoot, clock);

    TemporaryResourceStore.Workspace expired = store.create();
    expired.close();
    clock.advance(Duration.ofSeconds(100));
    TemporaryResourceStore.Workspace fresh = store.create();
    fresh.close();

    // 保留期 50 秒：第一个在 100 秒前创建已过期，第二个刚创建仍新鲜。
    assertEquals(1, store.sweep(50));
    assertFalse(Files.exists(expired.directory()));
    assertTrue(Files.exists(fresh.directory()));
  }

  /** 缩短保留期后，之前仍新鲜的工作区在下次扫描立即过期（热更对未回收旧资源生效）。 */
  @Test
  void shorteningTtlCollectsPreviouslyFreshWorkspaceOnNextSweep() throws IOException {
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));
    TemporaryResourceStore store = TemporaryResourceStore.open(tmpRoot, clock);

    TemporaryResourceStore.Workspace workspace = store.create();
    workspace.close();
    clock.advance(Duration.ofSeconds(100));

    assertEquals(0, store.sweep(200), "更长的保留期下未过期");
    assertEquals(1, store.sweep(50), "缩短保留期后应立即回收未回收的旧资源");
    assertFalse(Files.exists(workspace.directory()));
  }

  /** 活动租约保护：只要 workspace 仍被租用，即使超过保留期也绝不删除。 */
  @Test
  void sweepNeverDeletesLeasedWorkspace() throws IOException {
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));
    TemporaryResourceStore store = TemporaryResourceStore.open(tmpRoot, clock);

    TemporaryResourceStore.Workspace leased = store.create();
    clock.advance(Duration.ofSeconds(10_000));

    assertEquals(0, store.sweep(1), "被租用的 workspace 不得删除");
    assertTrue(Files.exists(leased.directory()));

    leased.close();
    assertEquals(1, store.sweep(1), "释放租约后过期 workspace 才可回收");
    assertFalse(Files.exists(leased.directory()));
  }

  /** 重启后内存租约消失，已登记且过期的 workspace 按创建登记被清扫。 */
  @Test
  void restartSweepsByCreationRegistration() throws IOException {
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));
    TemporaryResourceStore first = TemporaryResourceStore.open(tmpRoot, clock);
    TemporaryResourceStore.Workspace workspace = first.create();
    // 模拟崩溃：进程结束、内存租约全部丢失，但目录与登记留在磁盘。
    Path directory = workspace.directory();

    clock.advance(Duration.ofSeconds(1_000_000));
    TemporaryResourceStore reopened = TemporaryResourceStore.open(tmpRoot, clock);

    assertEquals(1, reopened.sweep(100), "重启后按创建登记清扫旧 workspace");
    assertFalse(Files.exists(directory));
  }

  /** 符号链接（reparse）不得被当作受控 workspace 遍历或删除，其指向的目标保持完整。 */
  @Test
  void sweepRejectsSymlinkedWorkspace() throws IOException {
    assumeTrue(supportsSymlinks(), "需要支持符号链接的文件系统");
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));
    TemporaryResourceStore store = TemporaryResourceStore.open(tmpRoot, clock);

    Path outside = tmpRoot.resolve("outside-target");
    Files.createDirectories(outside);
    Files.writeString(outside.resolve("keep.txt"), "keep");
    Path link = store.root().resolve("22222222-2222-2222-2222-222222222222");
    Files.createSymbolicLink(link, outside);

    assertEquals(0, store.sweep(1), "符号链接不得被视为受控 workspace");
    assertTrue(Files.exists(outside.resolve("keep.txt")), "链接目标不得被删除");
    assertTrue(Files.isSymbolicLink(link));
  }

  /** 名字不是 UUID 或缺少创建登记的目录不是受控 workspace，清扫必须跳过。 */
  @Test
  void sweepSkipsUnregisteredDirectories() throws IOException {
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));
    TemporaryResourceStore store = TemporaryResourceStore.open(tmpRoot, clock);

    Path notUuid = store.root().resolve("user-project");
    Path uuidWithoutRegistration = store.root().resolve("33333333-3333-3333-3333-333333333333");
    Files.createDirectories(notUuid);
    Files.createDirectories(uuidWithoutRegistration);
    Files.writeString(notUuid.resolve("data.txt"), "user data");
    clock.advance(Duration.ofSeconds(1_000_000));

    assertEquals(0, store.sweep(1), "未登记目录不得被清扫");
    assertTrue(Files.exists(notUuid.resolve("data.txt")));
    assertTrue(Files.exists(uuidWithoutRegistration));
  }

  /** 非正保留期必须 fail closed，避免“立即删除所有临时资源”这种静默失效的配置。 */
  @Test
  void sweepRejectsNonPositiveTtl() {
    TemporaryResourceStore store = TemporaryResourceStore.open(tmpRoot);
    assertThrows(IllegalArgumentException.class, () -> store.sweep(0));
    assertThrows(IllegalArgumentException.class, () -> store.sweep(-1));
  }

  /** 读取受控登记 workspace 时获取的 in-use 租约必须阻止对其的清扫，即使它已超过保留期。 */
  @Test
  void acquireLeasePreventsSweepOfRegisteredWorkspace() throws IOException {
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));
    TemporaryResourceStore store = TemporaryResourceStore.open(tmpRoot, clock);
    TemporaryResourceStore.Workspace workspace = store.create();
    workspace.close();
    clock.advance(Duration.ofSeconds(10_000));

    try (TextOutputStore.Lease lease = store.acquire(workspace.directory().resolve("output.log"))) {
      assertNotSame(TextOutputStore.Lease.NONE, lease, "受控路径必须授予真实租约");
      assertEquals(0, store.sweep(1), "在途读取持有的租约必须阻止清扫");
      assertTrue(Files.exists(workspace.directory()));
    }
    assertEquals(1, store.sweep(1), "租约释放后过期 workspace 才可回收");
    assertFalse(Files.exists(workspace.directory()));
  }

  /** 非受控路径（受控根外、非 UUID 子目录、无创建登记目录）不获取任何 in-use 租约。 */
  @Test
  void acquireIgnoresUncontrolledPaths() throws IOException {
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));
    TemporaryResourceStore store = TemporaryResourceStore.open(tmpRoot, clock);
    store.create().close();

    Path outside = tmpRoot.resolve("outside.txt");
    Files.writeString(outside, "x");
    Path notUuid = store.root().resolve("user-project");
    Files.createDirectories(notUuid);
    Path uuidWithoutRegistration = store.root().resolve("33333333-3333-3333-3333-333333333333");
    Files.createDirectories(uuidWithoutRegistration);

    assertSame(TextOutputStore.Lease.NONE, store.acquire(outside));
    assertSame(TextOutputStore.Lease.NONE, store.acquire(notUuid.resolve("file.txt")));
    assertSame(
        TextOutputStore.Lease.NONE, store.acquire(uuidWithoutRegistration.resolve("file.txt")));
  }

  /** 受控根自身不是任何 workspace 内的路径，不授予租约。 */
  @Test
  void acquireOfWorkspaceRootItselfIsUncontrolled() {
    TemporaryResourceStore store = TemporaryResourceStore.open(tmpRoot);
    assertSame(TextOutputStore.Lease.NONE, store.acquire(store.root()));
  }

  /** 清扫无法读取受控根时必须显式失败，绝不静默返回“已清扫 0 个”掩盖真实故障。 */
  @Test
  void sweepReportsUnreadableRootInsteadOfSilentlySucceeding() throws IOException {
    TemporaryResourceStore store = TemporaryResourceStore.open(tmpRoot);
    Files.delete(store.root());

    assertThrows(UncheckedIOException.class, () -> store.sweep(1));
  }

  /** 创建登记损坏（非法数字）时不得被当作受控 workspace 清扫；同轮合法的过期 workspace 仍被回收。 */
  @Test
  void sweepSkipsWorkspaceWithCorruptRegistration() throws IOException {
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));
    TemporaryResourceStore store = TemporaryResourceStore.open(tmpRoot, clock);
    TemporaryResourceStore.Workspace valid = store.create();
    valid.close();

    Path corrupt = store.root().resolve("44444444-4444-4444-4444-444444444444");
    Files.createDirectories(corrupt);
    Files.writeString(corrupt.resolve("created-at"), "not-a-number");

    clock.advance(Duration.ofSeconds(1_000_000));
    TemporaryResourceStore reopened = TemporaryResourceStore.open(tmpRoot, clock);

    assertEquals(1, reopened.sweep(1), "只有登记合法的过期 workspace 被回收");
    assertTrue(Files.exists(corrupt), "登记损坏的目录不得被猜测为受控资源");
    assertFalse(Files.exists(valid.directory()));
  }

  /** 数据根没有父目录（文件系统根）不是受控临时根，必须在产生副作用前拒绝。 */
  @Test
  void openRejectsRootWithoutParent() {
    Path filesystemRoot = tmpRoot.getRoot();
    assumeTrue(filesystemRoot.getParent() == null, "需要可用的文件系统根");
    assertThrows(IllegalArgumentException.class, () -> TemporaryResourceStore.open(filesystemRoot));
  }

  /** 可信根之上的 OS 前缀 alias 不是逃逸：只检查可信根（数据根）之下分量，不跟随、不拒绝前缀 alias。 */
  @Test
  void openAllowsAliasPrefixAboveTrustedRoot() throws IOException {
    assumeTrue(supportsSymlinks(), "需要支持符号链接的文件系统");
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));
    Path real = Files.createDirectories(tmpRoot.resolve("real-dataset"));
    Files.createDirectories(real.resolve("data"));
    Path alias = tmpRoot.resolve("alias");
    Files.createSymbolicLink(alias, real);

    TemporaryResourceStore store =
        TemporaryResourceStore.open(alias.resolve("data").resolve("tmp"), clock);

    assertTrue(Files.isDirectory(store.root(), LinkOption.NOFOLLOW_LINKS));
    assertTrue(store.root().startsWith(alias.resolve("data")), store.root().toString());
  }

  /** 可信根之下出现符号链接分量（tmp 或 workspaces）必须失败关闭，绝不跟随链接。 */
  @Test
  void openRejectsSymlinkedControlledComponents() throws IOException {
    assumeTrue(supportsSymlinks(), "需要支持符号链接的文件系统");
    MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));

    Path realTmp = Files.createDirectories(tmpRoot.resolve("real-tmp"));
    Path tmpLink = tmpRoot.resolve("tmp-link");
    Files.createSymbolicLink(tmpLink, realTmp);
    assertThrows(
        UncheckedIOException.class,
        () -> TemporaryResourceStore.open(tmpLink, clock),
        "tmp 分量是符号链接时必须拒绝");

    Path tmpDir = Files.createDirectories(tmpRoot.resolve("tmp-dir"));
    Path outside = Files.createDirectories(tmpRoot.resolve("outside-workspaces"));
    Files.createSymbolicLink(tmpDir.resolve("workspaces"), outside);
    assertThrows(
        UncheckedIOException.class,
        () -> TemporaryResourceStore.open(tmpDir, clock),
        "workspaces 分量是符号链接时必须拒绝");
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

  /** 可推进的测试时钟，用于构造确定性的保留期边界。 */
  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
