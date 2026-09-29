package fun.fengwk.kkstudio.harness.infra.resource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/** LocalFileResourceStore 的写入/读取/校验/异常契约。 */
class LocalFileResourceStoreTest {

  private static final String MEDIA_TYPE = "image/png";
  private static final String NAME = "diagram.png";
  private static final int DEFAULT_MAX_BYTES = 1024;

  @TempDir Path tempDir;

  private Path newRoot() throws IOException {
    Path root = tempDir.resolve("root");
    Files.createDirectory(root);
    return root;
  }

  private LocalFileResourceStore newStore(Path root, int maxBytes) {
    return new LocalFileResourceStore(root, maxBytes);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String sha256Hex(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static ResourceRef ref(String uri, String mediaType, String name, long size, String sha) {
    return new ResourceRef(uri, mediaType, name, size, sha);
  }

  /** 与 store 相同的规范对象 URI：目录条目的 toUri() 尾部 '/' 需归一。 */
  private static String canonicalFileUri(Path path) {
    String uri = path.toUri().toASCIIString();
    if (uri.endsWith("/")) {
      return uri.substring(0, uri.length() - 1);
    }
    return uri;
  }

  private static long objectCount(Path root) throws IOException {
    try (Stream<Path> files = Files.list(root)) {
      return files.count();
    }
  }

  private static void deleteTree(Path path) throws IOException {
    try (Stream<Path> stream = Files.walk(path)) {
      for (Path entry : stream.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(entry);
      }
    }
  }

  @Test
  void putReturnsCanonicalRefAndRoundTrips() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    byte[] content = bytes("hello resource");

    ResourceRef resource = store.put(MEDIA_TYPE, NAME, content);

    assertEquals(sha256Hex(content), resource.sha256());
    assertEquals(content.length, resource.size());
    assertEquals(MEDIA_TYPE, resource.mediaType());
    assertEquals(NAME, resource.name());
    String expectedUri = root.resolve(resource.sha256()).toUri().toASCIIString();
    assertEquals(expectedUri, resource.uri());
    assertTrue(resource.uri().startsWith("file:///"));
    assertArrayEquals(content, store.read(resource));
  }

  @Test
  void referenceProducesExactPutReferenceWithoutSideEffects() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    byte[] content = bytes("hello resource");

    ResourceRef viaPut = store.put(MEDIA_TYPE, NAME, content);
    // reference 与 put 对匹配字节返回完全相同的引用（put 复用 reference 构造），且绝不产生存储副作用。
    ResourceRef viaReference =
        store.reference(MEDIA_TYPE, NAME, content.length, sha256Hex(content));
    assertEquals(viaPut, viaReference);
    assertEquals(1L, objectCount(root), "reference must not create any object");

    // 未写入的引用（规划阶段）也能精确构造：null name 合法，且零存储副作用。
    ResourceRef planned = store.reference("text/plain", null, content.length, sha256Hex(content));
    assertEquals(1L, objectCount(root), "planning a reference must be side-effect free");
    assertEquals("text/plain", planned.mediaType());
    assertNull(planned.name());
    assertEquals(sha256Hex(content), planned.sha256());
    assertTrue(planned.uri().startsWith("file:///"));
  }

  @Test
  void putIsContentAddressedAndIdempotent() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    byte[] content = bytes("dedup me");

    ResourceRef first = store.put(MEDIA_TYPE, null, content);
    ResourceRef second = store.put(MEDIA_TYPE, null, content);

    assertEquals(first.uri(), second.uri());
    assertEquals(1, objectCount(root));
    assertArrayEquals(content, store.read(second));
  }

  @Test
  void putReusesPreExistingCorrectObject() throws IOException {
    Path root = newRoot();
    byte[] content = bytes("pre-existing");
    Path object = root.resolve(sha256Hex(content));
    Files.write(object, content);
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);

    ResourceRef resource = store.put(MEDIA_TYPE, NAME, content);

    assertEquals(object.toUri().toASCIIString(), resource.uri());
    assertEquals(1, objectCount(root));
    assertArrayEquals(content, store.read(resource));
  }

  @Test
  void putRejectsOversizedContentBeforeWriting() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, 4);

    assertThrows(IllegalArgumentException.class, () -> store.put(MEDIA_TYPE, NAME, bytes("12345")));
    assertEquals(0, objectCount(root));

    ResourceRef resource = store.put(MEDIA_TYPE, NAME, bytes("1234"));
    assertEquals(4, resource.size());
    assertArrayEquals(bytes("1234"), store.read(resource));
  }

  @Test
  void putAndReadAreDefensivelyCopied() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    byte[] content = bytes("defensive");

    ResourceRef resource = store.put(MEDIA_TYPE, NAME, content);
    content[0] = 'X';

    assertArrayEquals(bytes("defensive"), store.read(resource));
    byte[] firstRead = store.read(resource);
    byte[] secondRead = store.read(resource);
    firstRead[0] = 'Y';
    assertArrayEquals(bytes("defensive"), secondRead);
  }

  @Test
  void putRejectsNullAndInvalidInputsWithoutWriting() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);

    assertThrows(IllegalArgumentException.class, () -> store.put(null, NAME, bytes("x")));
    assertThrows(IllegalArgumentException.class, () -> store.put(MEDIA_TYPE, NAME, null));
    assertThrows(
        IllegalArgumentException.class, () -> store.put("not-a-media-type", NAME, bytes("x")));
    assertThrows(
        IllegalArgumentException.class, () -> store.put(MEDIA_TYPE, "bad\u0007name", bytes("x")));
    assertThrows(IllegalArgumentException.class, () -> store.read(null));

    assertEquals(0, objectCount(root));
  }

  @Test
  void putFailsWithIllegalStateOnStorageFailure() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    deleteTree(root);

    assertThrows(
        IllegalStateException.class, () -> store.put(MEDIA_TYPE, NAME, bytes("no root anymore")));
  }

  @Test
  void readRejectsNonFileSchemes() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    byte[] content = bytes("payload");
    String sha = sha256Hex(content);

    String dataUri =
        "data:" + MEDIA_TYPE + ";base64," + Base64.getEncoder().encodeToString(content);
    ResourceRef dataRef = ref(dataUri, MEDIA_TYPE, NAME, content.length, sha);
    assertThrows(IllegalArgumentException.class, () -> store.read(dataRef));

    ResourceRef httpRef = ref("http://example.com/x", MEDIA_TYPE, NAME, content.length, sha);
    assertThrows(IllegalArgumentException.class, () -> store.read(httpRef));

    ResourceRef s3Ref = ref("s3://my-bucket/key", MEDIA_TYPE, NAME, content.length, sha);
    assertThrows(IllegalArgumentException.class, () -> store.read(s3Ref));
  }

  @Test
  void readRejectsResourcesOutsidePinnedRoot() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    byte[] content = bytes("outside");
    Path outside = tempDir.resolve("outside.bin");
    Files.write(outside, content);

    ResourceRef outsideRef =
        ref(outside.toUri().toASCIIString(), MEDIA_TYPE, null, content.length, sha256Hex(content));
    assertThrows(IllegalArgumentException.class, () -> store.read(outsideRef));
  }

  @Test
  void readRejectsNestedOrMisnamedPaths() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    byte[] content = bytes("nested");

    Path nestedDir = Files.createDirectory(root.resolve("nested"));
    Path nested = nestedDir.resolve(sha256Hex(content));
    Files.write(nested, content);
    ResourceRef nestedRef =
        ref(nested.toUri().toASCIIString(), MEDIA_TYPE, null, content.length, sha256Hex(content));
    assertThrows(IllegalArgumentException.class, () -> store.read(nestedRef));

    Path misnamed = root.resolve("not-a-sha");
    Files.write(misnamed, content);
    ResourceRef misnamedRef =
        ref(misnamed.toUri().toASCIIString(), MEDIA_TYPE, null, content.length, sha256Hex(content));
    assertThrows(IllegalArgumentException.class, () -> store.read(misnamedRef));
  }

  @Test
  void readRejectsMissingObject() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    byte[] content = bytes("missing");
    Path object = root.resolve(sha256Hex(content));

    ResourceRef missingRef =
        ref(object.toUri().toASCIIString(), MEDIA_TYPE, null, content.length, sha256Hex(content));
    assertThrows(IllegalStateException.class, () -> store.read(missingRef));
  }

  @Test
  void readAndPutRejectSymbolicLinkObject() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    byte[] content = bytes("linked content");
    Path target = tempDir.resolve("link-target");
    Files.write(target, content);
    Path link = root.resolve(sha256Hex(content));
    Files.createSymbolicLink(link, target);

    ResourceRef linkRef =
        ref(link.toUri().toASCIIString(), MEDIA_TYPE, null, content.length, sha256Hex(content));
    assertThrows(IllegalStateException.class, () -> store.read(linkRef));
    assertThrows(IllegalStateException.class, () -> store.put(MEDIA_TYPE, NAME, content));
  }

  @Test
  void readAndPutRejectDirectoryObject() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    byte[] content = bytes("dir");
    Path dir = Files.createDirectory(root.resolve(sha256Hex(content)));

    ResourceRef dirRef =
        ref(canonicalFileUri(dir), MEDIA_TYPE, null, content.length, sha256Hex(content));
    assertThrows(IllegalStateException.class, () -> store.read(dirRef));
    assertThrows(IllegalStateException.class, () -> store.put(MEDIA_TYPE, NAME, content));
  }

  @Test
  void readRejectsDeclaredSizeAboveMax() throws IOException {
    Path root = newRoot();
    byte[] content = bytes("tiny");
    Path object = root.resolve(sha256Hex(content));
    Files.write(object, content);
    LocalFileResourceStore store = newStore(root, 4);

    ResourceRef oversizedRef =
        ref(
            object.toUri().toASCIIString(),
            MEDIA_TYPE,
            null,
            content.length + 1L,
            sha256Hex(content));
    assertThrows(IllegalArgumentException.class, () -> store.read(oversizedRef));
  }

  @Test
  void readRejectsDeclaredSizeMismatch() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    byte[] content = bytes("mismatch");
    Path object = root.resolve(sha256Hex(content));
    Files.write(object, content);

    ResourceRef wrongSizeRef =
        ref(
            object.toUri().toASCIIString(),
            MEDIA_TYPE,
            null,
            content.length + 1L,
            sha256Hex(content));
    assertThrows(IllegalStateException.class, () -> store.read(wrongSizeRef));
  }

  @Test
  void readRejectsDigestMismatch() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    byte[] stored = bytes("tampered");
    byte[] declared = bytes("expected");
    Path object = root.resolve(sha256Hex(declared));
    Files.write(object, stored);

    ResourceRef corruptRef =
        ref(object.toUri().toASCIIString(), MEDIA_TYPE, null, stored.length, sha256Hex(declared));
    assertThrows(IllegalStateException.class, () -> store.read(corruptRef));
  }

  @Test
  void readRejectsFileWithExtraBytes() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    byte[] declared = bytes("declared");
    Path object = root.resolve(sha256Hex(declared));
    Files.write(object, bytes("declared-extra"));

    ResourceRef extraRef =
        ref(object.toUri().toASCIIString(), MEDIA_TYPE, null, declared.length, sha256Hex(declared));
    assertThrows(IllegalStateException.class, () -> store.read(extraRef));
  }

  @Test
  void putRejectsCorruptExistingObjectWithoutOverwrite() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    byte[] expected = bytes("expected");
    byte[] tampered = bytes("tampered");

    Path sizeCorrupt = root.resolve(sha256Hex(expected));
    Files.write(sizeCorrupt, bytes("tampered!"));
    assertThrows(IllegalStateException.class, () -> store.put(MEDIA_TYPE, NAME, expected));
    assertArrayEquals(bytes("tampered!"), Files.readAllBytes(sizeCorrupt));

    Path digestCorrupt = root.resolve(sha256Hex(expected));
    Files.write(digestCorrupt, tampered);
    assertThrows(IllegalStateException.class, () -> store.put(MEDIA_TYPE, NAME, expected));
    assertArrayEquals(tampered, Files.readAllBytes(digestCorrupt));
  }

  /** 并发冲突的确定性仿真：另一个写者以同一 sha 名发布了错误内容，最终校验必须拒绝且绝不覆盖。 */
  @Test
  void verifyFinalTargetRejectsWrongConcurrentWinner() throws IOException {
    Path root = newRoot();
    byte[] expected = bytes("expected");
    byte[] wrongWinner = bytes("wrong winner payload");
    Path object = root.resolve(sha256Hex(expected));
    Files.write(object, wrongWinner);

    assertThrows(
        IllegalStateException.class,
        () ->
            LocalFileResourceStore.verifyFinalTarget(object, expected.length, sha256Hex(expected)));
    assertArrayEquals(wrongWinner, Files.readAllBytes(object));
    assertEquals(1, objectCount(root));
  }

  /** 自有发布后篡改的确定性仿真：同一最终校验必须再次拒绝被追加/替换的内容，绝不静默放过。 */
  @Test
  void verifyFinalTargetRejectsTamperAfterPublish() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    byte[] content = bytes("post-publish");
    ResourceRef resource = store.put(MEDIA_TYPE, NAME, content);

    Files.write(root.resolve(resource.sha256()), bytes("post-publish-tampered"));
    assertThrows(
        IllegalStateException.class,
        () ->
            LocalFileResourceStore.verifyFinalTarget(
                root.resolve(resource.sha256()), content.length, sha256Hex(content)));

    Files.delete(root.resolve(resource.sha256()));
    Files.write(root.resolve(resource.sha256()), content);
    assertThrows(
        IllegalStateException.class,
        () ->
            LocalFileResourceStore.verifyFinalTarget(
                root.resolve(resource.sha256()),
                content.length,
                sha256Hex(bytes("other content"))));
  }

  @Test
  void constructorRejectsInvalidRoots() throws IOException {
    Path root = newRoot();
    assertThrows(IllegalArgumentException.class, () -> newStore(null, DEFAULT_MAX_BYTES));
    assertThrows(IllegalArgumentException.class, () -> newStore(root, 0));
    assertThrows(IllegalArgumentException.class, () -> newStore(root, -1));

    Path missing = tempDir.resolve("missing-root");
    assertThrows(IllegalArgumentException.class, () -> newStore(missing, DEFAULT_MAX_BYTES));

    Path fileRoot = tempDir.resolve("file-root");
    Files.write(fileRoot, bytes("not a directory"));
    assertThrows(IllegalArgumentException.class, () -> newStore(fileRoot, DEFAULT_MAX_BYTES));

    Path spaced = Files.createDirectory(tempDir.resolve("root with space"));
    assertThrows(IllegalArgumentException.class, () -> newStore(spaced, DEFAULT_MAX_BYTES));

    Path nonAscii = Files.createDirectory(tempDir.resolve("根目录"));
    assertThrows(IllegalArgumentException.class, () -> newStore(nonAscii, DEFAULT_MAX_BYTES));

    assertThrows(IllegalArgumentException.class, () -> newStore(Path.of("/"), DEFAULT_MAX_BYTES));
  }

  @Test
  void constructorResolvesSymbolicRootToRealDirectory() throws IOException {
    Path realDir = Files.createDirectory(tempDir.resolve("real-dir"));
    Path link = tempDir.resolve("root-link");
    Files.createSymbolicLink(link, realDir);

    LocalFileResourceStore store = new LocalFileResourceStore(link, DEFAULT_MAX_BYTES);
    byte[] content = bytes("via symlink root");
    ResourceRef resource = store.put(MEDIA_TYPE, NAME, content);

    assertTrue(Files.isRegularFile(realDir.resolve(resource.sha256())));
    assertArrayEquals(content, store.read(resource));
  }

  @Test
  void readFailsWithIllegalStateWhenRootRemoved() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    ResourceRef resource = store.put(MEDIA_TYPE, NAME, bytes("gone"));
    deleteTree(root);

    assertThrows(IllegalStateException.class, () -> store.read(resource));
  }

  @Test
  void emptyContentRoundTrips() throws IOException {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);

    ResourceRef resource = store.put(MEDIA_TYPE, null, new byte[0]);

    assertEquals(0, resource.size());
    assertEquals(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", resource.sha256());
    assertArrayEquals(new byte[0], store.read(resource));
  }

  @Test
  void concurrentSameContentPutsPublishSingleObject() throws Exception {
    Path root = newRoot();
    LocalFileResourceStore store = newStore(root, DEFAULT_MAX_BYTES);
    byte[] content = bytes("concurrent dedup");
    int threads = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<ResourceRef>> futures = new ArrayList<>();
      for (int index = 0; index < threads; index++) {
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return store.put(MEDIA_TYPE, NAME, content);
                }));
      }
      start.countDown();
      String firstUri = null;
      for (Future<ResourceRef> future : futures) {
        ResourceRef resource = future.get();
        if (firstUri == null) {
          firstUri = resource.uri();
        }
        assertEquals(firstUri, resource.uri());
      }
    } finally {
      executor.shutdownNow();
    }
    assertEquals(1, objectCount(root));
    assertArrayEquals(
        content, store.read(newStore(root, DEFAULT_MAX_BYTES).put(MEDIA_TYPE, NAME, content)));
  }

  /** 测试意图：临时文件创建成功后的写入失败必须由 writeTempFile 自己清理——部分写入（如 ENOSPC）不得在 root 遗留 .tmp 残留。 */
  @Test
  void writeTempFileCleansUpAfterPartialWriteFailure() throws IOException {
    Path root = newRoot();
    IOException partialWriteFailure = new IOException("simulated partial write failure");

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                LocalFileResourceStore.writeTempFile(
                    root,
                    bytes("payload"),
                    (channel, payload) -> {
                      channel.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));
                      throw partialWriteFailure;
                    }));

    assertSame(partialWriteFailure, failure, "cleanup must not replace the original failure");
    assertEquals(0, failure.getSuppressed().length, "cleanup itself succeeded");
    assertEquals(0L, objectCount(root), "failed writeTempFile must not leave temp residue");
  }

  /** 测试意图：写入完成但 force（fsync）失败同属“创建后失败”，同样必须清理临时文件且保持原始故障。 */
  @Test
  void writeTempFileCleansUpAfterForceFailure() throws IOException {
    Path root = newRoot();
    IOException forceFailure = new IOException("simulated fsync failure");

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                LocalFileResourceStore.writeTempFile(
                    root,
                    bytes("payload"),
                    (channel, payload) -> {
                      channel.write(payload);
                      throw forceFailure;
                    }));

    assertSame(forceFailure, failure);
    assertEquals(0L, objectCount(root), "failed writeTempFile must not leave temp residue");
  }

  /**
   * 测试意图：写入器抛出非受检运行时异常（部分写入后失败，例如 JDK 层面的运行时 I/O 故障）同样必须由 writeTempFile 清理自己创建的临时文件、原样抛出失败，且绝不删除
   * root 中既有条目。
   */
  @Test
  void writeTempFileCleansUpAfterUncheckedPartialWriteFailure() throws IOException {
    Path root = newRoot();
    byte[] payload = bytes("payload");
    Path existing = root.resolve(sha256Hex(payload));
    Files.write(existing, bytes("already published"));
    IllegalStateException partialWriteFailure =
        new IllegalStateException("simulated unchecked partial write failure");

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                LocalFileResourceStore.writeTempFile(
                    root,
                    payload,
                    (channel, buffer) -> {
                      channel.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));
                      throw partialWriteFailure;
                    }));

    assertSame(partialWriteFailure, failure, "cleanup must not replace the original failure");
    assertEquals(0, failure.getSuppressed().length, "cleanup itself succeeded");
    assertEquals(
        1L, objectCount(root), "failed writeTempFile must leave exactly the pre-existing entry");
    assertArrayEquals(bytes("already published"), Files.readAllBytes(existing));
  }

  /** 测试意图：写入器抛出 Error（断言失败）时清理路径同样必须生效，不得在 root 遗留临时文件。 */
  @Test
  void writeTempFileCleansUpAfterErrorPartialWriteFailure() throws IOException {
    Path root = newRoot();
    byte[] payload = bytes("payload");
    Path existing = root.resolve(sha256Hex(payload));
    Files.write(existing, bytes("already published"));
    AssertionError partialWriteFailure = new AssertionError("simulated writer assertion failure");

    AssertionError failure =
        assertThrows(
            AssertionError.class,
            () ->
                LocalFileResourceStore.writeTempFile(
                    root,
                    payload,
                    (channel, buffer) -> {
                      channel.write(ByteBuffer.wrap(new byte[] {1, 2, 3}));
                      throw partialWriteFailure;
                    }));

    assertSame(partialWriteFailure, failure, "cleanup must not replace the original failure");
    assertEquals(0, failure.getSuppressed().length, "cleanup itself succeeded");
    assertEquals(
        1L, objectCount(root), "failed writeTempFile must leave exactly the pre-existing entry");
    assertArrayEquals(bytes("already published"), Files.readAllBytes(existing));
  }

  /** 测试意图：清理自身失败（条目已变成非空目录）只作为 suppressed 附加，绝不掩盖原始写入故障。 */
  @Test
  void deleteFailedTempAttachesCleanupFailureAsSuppressed() throws IOException {
    Path undeletable = tempDir.resolve("non-empty-directory");
    Files.createDirectory(undeletable);
    Files.writeString(undeletable.resolve("child"), "x");
    IOException primaryFailure = new IOException("primary write failure");

    LocalFileResourceStore.deleteFailedTemp(undeletable, primaryFailure);

    assertEquals(1, primaryFailure.getSuppressed().length);
    assertTrue(primaryFailure.getSuppressed()[0] instanceof IOException);
    assertTrue(Files.exists(undeletable), "undelatable entry is left for the operator to inspect");
  }
}
