package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceRef;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Local file resource store 的 store / read / 所有权 / 边界契约测试。 */
class LocalFileResourceStoreTest {

  @TempDir Path environmentRoot;

  /** store + read 必须完整 round-trip 字节，ref 为携带 size/sha 的规范 file URI，文件以 digest 命名且可复用。 */
  @Test
  void storeAndReadRoundTripBytesAndDeduplicateByDigest() throws IOException {
    Path directory = environmentRoot.resolve("export");
    LocalFileResourceStore store = new LocalFileResourceStore(directory);
    byte[] data = new byte[] {1, 2, 3, 4, 5};

    DaemonResourceRef ref = store.store(data, "application/octet-stream");
    assertEquals("file", URI.create(ref.uri()).getScheme());
    assertEquals(data.length, ref.size());
    assertEquals(64, ref.sha256().length());
    assertArrayEquals(data, store.read(ref));
    assertArrayEquals(data, Files.readAllBytes(directory.resolve(ref.sha256())));
    assertTrue(Files.isRegularFile(directory.resolve(ref.sha256())));
    assertEquals(1, Files.list(directory).count());

    // 同内容重复 store 幂等：同一 digest 文件，不产生第二个文件。
    DaemonResourceRef again = store.store(data, "text/plain");
    assertEquals(ref.uri(), again.uri());
    assertEquals(1, Files.list(directory).count());

    DaemonResourceRef wrongSize =
        new DaemonResourceRef(ref.uri(), ref.mediaType(), null, ref.size() + 1, ref.sha256());
    assertThrows(IOException.class, () -> store.read(wrongSize));

    DaemonResourceRef wrongSha =
        new DaemonResourceRef(ref.uri(), ref.mediaType(), null, ref.size(), "0".repeat(63) + "a");
    assertThrows(IOException.class, () -> store.read(wrongSha));
  }

  /** 内容寻址：相同字节得到同一 URI，不同字节得到不同 URI。 */
  @Test
  void digestNamingDistinguishesContent() throws IOException {
    LocalFileResourceStore store = new LocalFileResourceStore(environmentRoot.resolve("export"));
    DaemonResourceRef first = store.store("a".getBytes(StandardCharsets.UTF_8), "text/plain");
    DaemonResourceRef second = store.store("b".getBytes(StandardCharsets.UTF_8), "text/plain");

    assertNotEquals(first.uri(), second.uri());
    assertEquals(2, Files.list(environmentRoot.resolve("export")).count());
  }

  /** 只读本组件生成的 digest 名文件：非 digest 名、越界路径与非法 URI 一律拒绝。 */
  @Test
  void readRejectsUnownedOrTraversalUris() throws IOException {
    Path directory = environmentRoot.resolve("export");
    LocalFileResourceStore store = new LocalFileResourceStore(directory);
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("not-a-digest"), "x");
    Files.writeString(directory.resolve("0".repeat(64)), "x");

    assertRejected(store, "file:///etc/passwd");
    assertRejected(store, "file://" + directory + "/not-a-digest");
    assertRejected(store, "file://" + directory.getParent() + "/outside/" + "0".repeat(64));
    assertRejected(store, "file://" + directory + "/" + "0".repeat(63) + "/nested");
    assertRejected(store, "https://example.com/a");
    // 含 dot segment 或非空 authority 的 URI 在 DaemonResourceRef 构造期即被协议校验拒绝，永远不会触达 store。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonResourceRef(
                "file://" + directory + "/../outside", "text/plain", null, 1L, "0".repeat(64)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonResourceRef(
                "file://host" + directory + "/" + "0".repeat(64),
                "text/plain",
                null,
                1L,
                "0".repeat(64)));
  }

  private static void assertRejected(LocalFileResourceStore store, String uri) {
    DaemonResourceRef ref = new DaemonResourceRef(uri, "text/plain", null, 1L, "0".repeat(64));
    assertThrows(IOException.class, () -> store.read(ref));
  }

  /** 符号链接（即使指向导出根内文件）也必须拒绝；未存储的 digest 抛 IOException。 */
  @Test
  void readRejectsSymlinksAndMissingResources() throws IOException {
    Path directory = environmentRoot.resolve("export");
    LocalFileResourceStore store = new LocalFileResourceStore(directory);
    byte[] data = "linked".getBytes(StandardCharsets.UTF_8);
    DaemonResourceRef ref = store.store(data, "text/plain");
    Files.createSymbolicLink(
        directory.resolve("0".repeat(63) + "f"), directory.resolve(ref.sha256()));

    DaemonResourceRef symlink =
        new DaemonResourceRef(
            directory.resolve("0".repeat(63) + "f").toUri().toString(),
            "text/plain",
            null,
            ref.size(),
            ref.sha256());
    assertThrows(IOException.class, () -> store.read(symlink));

    DaemonResourceRef missing =
        new DaemonResourceRef(
            directory.resolve("a".repeat(64)).toUri().toString(),
            "text/plain",
            null,
            0L,
            InMemoryResourceStore.sha256Of(""));
    assertThrows(IOException.class, () -> store.read(missing));
  }

  /** 导出根必须能渲染为规范 file URI；含空格或非 ASCII 的根在构造时即拒绝。 */
  @Test
  void rejectsExportRootThatCannotProduceCanonicalFileUris() throws IOException {
    Path spaced = environmentRoot.resolve("with space");
    Files.createDirectories(spaced);
    assertThrows(
        IllegalArgumentException.class, () -> new LocalFileResourceStore(spaced.resolve("export")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new LocalFileResourceStore(environmentRoot.resolve("中文")));
  }

  /** store 的媒体类型必须非空，读取必须精确匹配声明 size/sha。 */
  @Test
  void storeValidatesMediaTypeAndReadVerifiesDeclaredFields() throws IOException {
    LocalFileResourceStore store = new LocalFileResourceStore(environmentRoot.resolve("export"));
    assertThrows(IllegalArgumentException.class, () -> store.store(new byte[] {1}, ""));
    assertThrows(IllegalArgumentException.class, () -> store.store(new byte[] {1}, "  "));
    assertThrows(IllegalArgumentException.class, () -> store.store(new byte[] {1}, null));
  }

  /** digest 名被目录占据时，store 必须确定性失败而不是把目录当内容复用。 */
  @Test
  void storeFailsWhenDigestNameIsOccupiedByNonRegularFile() throws IOException {
    Path directory = environmentRoot.resolve("export");
    LocalFileResourceStore store = new LocalFileResourceStore(directory);
    byte[] data = "collision".getBytes(StandardCharsets.UTF_8);
    String digest = InMemoryResourceStore.sha256Of(new String(data, StandardCharsets.UTF_8));
    Files.createDirectories(directory.resolve(digest));

    assertThrows(IOException.class, () -> store.store(data, "text/plain"));
    assertEquals(1, Files.list(directory).count());
    assertTrue(Files.isDirectory(directory.resolve(digest)));
  }

  /** 并发同内容 store 必须收敛到同一 digest 文件：所有调用方得到相同 ref，且不残留临时文件。 */
  @Test
  void concurrentStoresOfSameContentConvergeToSingleFile() throws Exception {
    Path directory = environmentRoot.resolve("export");
    LocalFileResourceStore store = new LocalFileResourceStore(directory);
    byte[] data = "concurrent".getBytes(StandardCharsets.UTF_8);
    int threads = 32;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      List<Future<DaemonResourceRef>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return store.store(data, "text/plain");
                }));
      }
      start.countDown();
      Set<String> uris = new HashSet<>();
      for (Future<DaemonResourceRef> future : futures) {
        uris.add(future.get(10, TimeUnit.SECONDS).uri());
      }
      assertEquals(1, uris.size());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(1, Files.list(directory).count());
    assertTrue(
        Files.isRegularFile(directory.resolve(InMemoryResourceStore.sha256Of("concurrent"))));
    try (var files = Files.list(directory)) {
      assertTrue(files.noneMatch(name -> name.getFileName().toString().startsWith(".resource-")));
    }
  }

  /** publish 报告获胜者：冲突时保留先到者并返回 false，自己发布返回 true；temp 由调用方在最终验证后清理。 */
  @Test
  void publishReportsWinnerAndKeepsExistingOnConflict() throws IOException {
    Path directory = environmentRoot.resolve("export");
    LocalFileResourceStore store = new LocalFileResourceStore(directory);
    byte[] published = "first writer wins".getBytes(StandardCharsets.UTF_8);
    byte[] candidate = "second writer loses".getBytes(StandardCharsets.UTF_8);
    String digest = InMemoryResourceStore.sha256Of(new String(published, StandardCharsets.UTF_8));
    Files.createDirectories(directory);
    Path target = directory.resolve(digest);
    Files.write(target, published);

    // 已存在目标：create-only 保留先到者内容、报告冲突（false）；temp 保留到调用方清理。
    Path temp = directory.resolve("temp-candidate");
    Files.write(temp, candidate);
    assertFalse(store.publish(temp, target));
    assertArrayEquals(published, Files.readAllBytes(target));
    assertTrue(Files.exists(temp));
    Files.deleteIfExists(temp);

    // 新目标：发布成功、报告自己胜出（true）。
    Path freshTarget = directory.resolve("f".repeat(64));
    Path freshTemp = directory.resolve("temp-fresh");
    Files.write(freshTemp, candidate);
    assertTrue(store.publish(freshTemp, freshTarget));
    assertArrayEquals(candidate, Files.readAllBytes(freshTarget));
    assertTrue(Files.exists(freshTemp));
    Files.deleteIfExists(freshTemp);
    assertEquals(2, Files.list(directory).count());
  }

  /** 构造期根路径被普通文件阻挡时确定性失败（IOException 包装为 IllegalArgumentException）。 */
  @Test
  void constructorRejectsRootBlockedByAFile() throws IOException {
    Path blocker = environmentRoot.resolve("blocker");
    Files.writeString(blocker, "x", StandardCharsets.UTF_8);
    assertThrows(
        IllegalArgumentException.class,
        () -> new LocalFileResourceStore(blocker.resolve("export")));
  }

  /** temp 写入后立即被替换：发布前身份验证必须拒绝，防止把无关文件发布为 digest。 */
  @Test
  void storeRejectsReplacedTempFile() throws IOException {
    Path directory = environmentRoot.resolve("export");
    LocalFileResourceStore store = new LocalFileResourceStore(directory);
    Files.createDirectories(directory);
    byte[] data = "temp identity".getBytes(StandardCharsets.UTF_8);
    Path temp = LocalFileResourceStore.writeOwnedTemp(directory, data);
    Object tempKey = LocalFileResourceStore.fileKeyOf(temp);

    // 旧文件 rename 移走（保持原 inode 存活），在同一路径写入替换文件 → 必然不同 inode。
    Path moved = directory.resolve("temp-replaced");
    Files.move(temp, moved);
    Files.writeString(temp, "attacker replacement");
    IOException error = assertThrows(IOException.class, () -> store.verifyOwnedTemp(temp, tempKey));
    assertTrue(error.getMessage().contains("replaced"));
    Files.deleteIfExists(temp);
    Files.deleteIfExists(moved);

    // temp 被替换为目录：非普通文件分支拒绝。
    Path dirTemp = LocalFileResourceStore.writeOwnedTemp(directory, data);
    Object dirTempKey = LocalFileResourceStore.fileKeyOf(dirTemp);
    Files.delete(dirTemp);
    Files.createDirectory(dirTemp);
    IOException nonRegular =
        assertThrows(IOException.class, () -> store.verifyOwnedTemp(dirTemp, dirTempKey));
    assertTrue(nonRegular.getMessage().contains("non-regular file"));
    Files.deleteIfExists(dirTemp);
  }

  /** 自己发布的目标在最终验证前被原地毒化：仅当目标仍是同一 inode 时才清理，无关并发目标绝不删除。 */
  @Test
  void poisonedOwnPublishTargetIsCleanedOnlyWhenSameInode() throws IOException {
    Path directory = environmentRoot.resolve("export");
    LocalFileResourceStore store = new LocalFileResourceStore(directory);
    Files.createDirectories(directory);
    byte[] data = "correct content".getBytes(StandardCharsets.UTF_8);
    String digest = InMemoryResourceStore.sha256Of(new String(data, StandardCharsets.UTF_8));
    Path target = directory.resolve(digest);

    // 自己发布胜出；随后同 inode 原地毒化（size 不符）→ 验证失败，清理仅在仍为同一 inode 时执行。
    Path temp = LocalFileResourceStore.writeOwnedTemp(directory, data);
    assertTrue(store.publish(temp, target));
    Files.write(target, new byte[] {1, 2, 3});
    assertThrows(IOException.class, () -> store.verifyDigestFile(target, data.length, digest));
    LocalFileResourceStore.deleteTargetIfSameInode(temp, target);
    assertFalse(Files.exists(target));
    Files.deleteIfExists(temp);

    // 无关并发目标（不同 inode）：绝不删除。
    Files.writeString(target, "unrelated concurrent winner");
    Path otherTemp = LocalFileResourceStore.writeOwnedTemp(directory, data);
    LocalFileResourceStore.deleteTargetIfSameInode(otherTemp, target);
    assertTrue(Files.exists(target));
    Files.deleteIfExists(otherTemp);
  }

  /** 构造后导出根被替换（rename 移走原根后在同路径建新目录）：store/read 必须按根身份（fileKey）确定性拒绝。 */
  @Test
  void storeAndReadRejectReplacedRoot() throws IOException {
    Path directory = environmentRoot.resolve("export");
    LocalFileResourceStore store = new LocalFileResourceStore(directory);
    byte[] data = "root pin".getBytes(StandardCharsets.UTF_8);
    DaemonResourceRef ref = store.store(data, "text/plain");

    // rename 保持原根 inode 存活，新目录必然不同 inode。
    Path replaced = environmentRoot.resolve("export-replaced");
    Files.move(directory, replaced);
    Files.createDirectories(directory);

    IOException storeError = assertThrows(IOException.class, () -> store.store(data, "text/plain"));
    assertTrue(storeError.getMessage().contains("root was replaced"));
    assertThrows(IOException.class, () -> store.read(ref));
    deleteRecursively(replaced);
  }

  /** 精确读取检测追加增长：声明 size 不变而文件被追加后，读取必须拒绝。 */
  @Test
  void readRejectsAppendedExtraBytes() throws IOException {
    Path directory = environmentRoot.resolve("export");
    LocalFileResourceStore store = new LocalFileResourceStore(directory);
    byte[] data = "immutable".getBytes(StandardCharsets.UTF_8);
    DaemonResourceRef ref = store.store(data, "text/plain");

    Files.write(directory.resolve(ref.sha256()), new byte[] {0x7F}, StandardOpenOption.APPEND);
    IOException error = assertThrows(IOException.class, () -> store.read(ref));
    assertTrue(error.getMessage().contains("size mismatch"));
  }

  /** 回退 copy 必须 create-only：已存在目标保留且返回 false，新目标成功返回 true（O_EXCL 语义）。 */
  @Test
  void copyCreateOnlyKeepsExistingTarget() throws IOException {
    Path directory = environmentRoot.resolve("export");
    LocalFileResourceStore store = new LocalFileResourceStore(directory);
    Files.createDirectories(directory);
    Path existing = directory.resolve("existing");
    Files.writeString(existing, "original", StandardCharsets.UTF_8);
    Path temp = directory.resolve("temp");
    Files.writeString(temp, "candidate", StandardCharsets.UTF_8);

    // 目标已存在：内容保持原样，返回 false。
    assertFalse(LocalFileResourceStore.copyCreateOnly(temp, existing));
    assertEquals("original", Files.readString(existing, StandardCharsets.UTF_8));

    // 目标不存在：按内容复制，返回 true。
    Path fresh = directory.resolve("fresh");
    assertTrue(LocalFileResourceStore.copyCreateOnly(temp, fresh));
    assertEquals("candidate", Files.readString(fresh, StandardCharsets.UTF_8));
  }

  /** 发布后验证必须精确：内容错误的并发获胜者/发布后篡改（size 或内容不符）确定性拒绝，且绝不覆盖损坏内容。 */
  @Test
  void verifyDigestFileRejectsWrongWinnerAndPostPublishTamper() throws IOException {
    Path directory = environmentRoot.resolve("export");
    LocalFileResourceStore store = new LocalFileResourceStore(directory);
    Files.createDirectories(directory);
    byte[] data = "correct content".getBytes(StandardCharsets.UTF_8);
    String digest = InMemoryResourceStore.sha256Of(new String(data, StandardCharsets.UTF_8));
    Path target = directory.resolve(digest);

    // 自己发布胜出；随后篡改（等价于“digest 名被错误内容抢先占据”）。
    Path temp = directory.resolve("temp-winner");
    Files.write(temp, data);
    assertTrue(store.publish(temp, target));

    // size 不符：拒绝且不覆盖损坏内容。
    Files.write(target, new byte[] {1, 2, 3});
    assertThrows(IOException.class, () -> store.verifyDigestFile(target, data.length, digest));
    // 同 size 内容不符：拒绝。
    Files.write(target, new byte[data.length]);
    assertThrows(IOException.class, () -> store.verifyDigestFile(target, data.length, digest));
    assertArrayEquals(new byte[data.length], Files.readAllBytes(target));

    // store 复用同一 digest 走同一验证路径：拒绝而非信任。
    assertThrows(IOException.class, () -> store.store(data, "text/plain"));
    // 恢复正确内容后收敛成功。
    Files.write(target, data);
    assertEquals(target.toUri().toString(), store.store(data, "text/plain").uri());
  }

  /** 验证的期望 size 超过可读上限（int 数组边界）时，在任何分配之前拒绝。 */
  @Test
  void verifyDigestFileRejectsExpectedSizeBeyondReadableLimit() throws IOException {
    Path directory = environmentRoot.resolve("export");
    LocalFileResourceStore store = new LocalFileResourceStore(directory);
    Files.createDirectories(directory);
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                store.verifyDigestFile(
                    directory.resolve("a".repeat(64)),
                    (long) Integer.MAX_VALUE + 1,
                    "0".repeat(64)));
    assertTrue(error.getMessage().contains("exceeds the readable limit"));
  }

  /** 构造器创建导出根并固定其真实路径：符号链接形式的根被解析为最终目标。 */
  @Test
  void constructorCreatesAndPinsRealExportRoot() throws IOException {
    Path real = environmentRoot.resolve("real-export");
    Files.createDirectories(real);
    Path link = environmentRoot.resolve("export-link");
    Files.createSymbolicLink(link, real);
    LocalFileResourceStore store = new LocalFileResourceStore(link);

    assertEquals(real.toRealPath(), store.directory());
    assertTrue(Files.isDirectory(store.directory()));
  }

  /** 字节上限必须为正；store 的字节与 read 的声明/实际大小都必须在分配前受上限约束。 */
  @Test
  void storeAndReadEnforceMaxResourceBytes() throws IOException {
    Path directory = environmentRoot.resolve("export");
    assertThrows(IllegalArgumentException.class, () -> new LocalFileResourceStore(directory, 0));
    assertThrows(IllegalArgumentException.class, () -> new LocalFileResourceStore(directory, -1));

    LocalFileResourceStore store = new LocalFileResourceStore(directory, 4);
    assertThrows(
        IllegalArgumentException.class,
        () -> store.store(new byte[] {1, 2, 3, 4, 5}, "text/plain"));
    DaemonResourceRef ref = store.store(new byte[] {1, 2, 3, 4}, "text/plain");
    assertArrayEquals(new byte[] {1, 2, 3, 4}, store.read(ref));

    // 声明 size 超限：任何文件访问/分配前拒绝。
    DaemonResourceRef declaredTooBig =
        new DaemonResourceRef(ref.uri(), ref.mediaType(), null, 5L, ref.sha256());
    assertThrows(IOException.class, () -> store.read(declaredTooBig));

    // 实际文件超限（声明 ≤ 上限但落盘内容更大）：打开后先取 size 再拒绝，不分配。
    Files.write(directory.resolve(ref.sha256()), new byte[] {1, 2, 3, 4, 5});
    DaemonResourceRef declaresSmall =
        new DaemonResourceRef(ref.uri(), ref.mediaType(), null, 1L, ref.sha256());
    IOException actualTooBig = assertThrows(IOException.class, () -> store.read(declaresSmall));
    assertTrue(actualTooBig.getMessage().contains("exceeds the readable limit"));
  }

  /** 复用既有 digest 文件前必须精确校验：同 digest 名被篡改（size 或内容不符）时拒绝而不是信任。 */
  @Test
  void storeRejectsTamperedExistingDigestFileOnReuse() throws IOException {
    Path directory = environmentRoot.resolve("export");
    LocalFileResourceStore store = new LocalFileResourceStore(directory);
    byte[] data = "original".getBytes(StandardCharsets.UTF_8);
    DaemonResourceRef ref = store.store(data, "text/plain");
    Path digestFile = directory.resolve(ref.sha256());

    // 篡改为不同长度：size 校验拒绝。
    Files.write(digestFile, new byte[] {1, 2, 3});
    assertThrows(IOException.class, () -> store.store(data, "text/plain"));
    // 恢复长度但篡改内容：内容校验拒绝。
    Files.write(digestFile, new byte[data.length]);
    assertThrows(IOException.class, () -> store.store(data, "text/plain"));
    // 恢复原内容后复用成功。
    Files.write(digestFile, data);
    assertEquals(ref.uri(), store.store(data, "text/plain").uri());
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // 尽力清理
                }
              });
    }
  }
}
