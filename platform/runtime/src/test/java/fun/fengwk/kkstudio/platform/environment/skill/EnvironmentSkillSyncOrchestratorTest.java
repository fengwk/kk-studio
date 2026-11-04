package fun.fengwk.kkstudio.platform.environment.skill;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.platform.daemon.DaemonSkillPackage;
import fun.fengwk.kkstudio.platform.daemon.DaemonSkillStatePackage;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentEvent;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentSkillState;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** {@link EnvironmentSkillSyncOrchestrator} 的期望集合下发、单飞合并与状态投影测试。 */
class EnvironmentSkillSyncOrchestratorTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final EnvironmentId OTHER =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
  private static final UUID LEASE_TOKEN = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID NODE = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final String COMMIT = "a".repeat(64);
  private static final String ARTIFACT_A = "a".repeat(64);
  private static final String ARTIFACT_B = "b".repeat(64);
  private static final Instant NOW = Instant.parse("2026-09-21T06:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private EnvironmentRegistry registry;
  private SkillPackageRepository packageRepository;
  private StorageBlobManager blobManager;
  private ExecutorService executor;
  private RecordingTransport transport;
  private EnvironmentSkillSyncOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    registry = mock(EnvironmentRegistry.class);
    packageRepository = mock(SkillPackageRepository.class);
    blobManager = mock(StorageBlobManager.class);
    executor = Executors.newFixedThreadPool(2);
    transport = new RecordingTransport();
    orchestrator =
        new EnvironmentSkillSyncOrchestrator(
            registry, packageRepository, blobManager, transport, executor, CLOCK);
    when(registry.find(ENV)).thenReturn(Optional.of(connection(ENV, List.of())));
    when(registry.find(OTHER)).thenReturn(Optional.of(connection(OTHER, List.of())));
    when(registry.listReadyOwnedByNode()).thenReturn(List.of(connection(ENV, List.of())));
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  /** 意图：READY 下发完整期望集合，包含归档摘要、大小与预签名下载地址，不含任何 Git 或凭据事实。 */
  @Test
  void sendsTheCompleteDesiredSet() throws Exception {
    when(packageRepository.listPackages())
        .thenReturn(List.of(packageRow("pkg-a", ARTIFACT_A), packageRow("pkg-b", ARTIFACT_B)));
    stubArtifact(ARTIFACT_A, 11, "https://s3.example.com/a");
    stubArtifact(ARTIFACT_B, 22, "https://s3.example.com/b");

    orchestrator.onEnvironmentReady(ENV);

    List<DaemonSkillPackage> sent = transport.awaitSends(1).getFirst();
    assertEquals(2, sent.size());
    assertEquals("pkg-a", sent.get(0).packageName());
    assertEquals(ARTIFACT_A, sent.get(0).sha256());
    assertEquals(11, sent.get(0).sizeBytes());
    assertEquals("https://s3.example.com/a", sent.get(0).downloadUrl());
    assertEquals("pkg-b", sent.get(1).packageName());
  }

  /** 意图：运行中的新触发合并为结束后恰好再跑一次，并使用最新期望集合。 */
  @Test
  void triggerDuringARoundMergesIntoExactlyOneMoreRound() throws Exception {
    when(packageRepository.listPackages()).thenReturn(List.of(packageRow("pkg-a", ARTIFACT_A)));
    stubArtifact(ARTIFACT_A, 11, "https://s3.example.com/a");
    transport.blockFirstSend();

    orchestrator.onEnvironmentReady(ENV);
    assertTrue(transport.awaitFirstSendStarted());
    // 第一次下发阻塞期间，catalog 增加到两个包并再次触发。
    when(packageRepository.listPackages())
        .thenReturn(List.of(packageRow("pkg-a", ARTIFACT_A), packageRow("pkg-b", ARTIFACT_B)));
    stubArtifact(ARTIFACT_B, 22, "https://s3.example.com/b");
    orchestrator.onEnvironmentReady(ENV);
    orchestrator.onPackageChanged("pkg-b");
    transport.releaseFirstSend();

    List<List<DaemonSkillPackage>> sends = transport.awaitSends(2);
    assertEquals(2, sends.size(), "a single merged round must follow the blocked round");
    assertEquals(2, sends.get(1).size());
    assertEquals("pkg-b", sends.get(1).get(1).packageName());
    // 合并后的第三、四次触发不会再产生额外轮次。
    Thread.sleep(50);
    assertEquals(2, transport.sent().size());
  }

  /** 意图：不同 Environment 互不阻塞。 */
  @Test
  void differentEnvironmentsAreNotSerialized() throws Exception {
    when(packageRepository.listPackages()).thenReturn(List.of(packageRow("pkg-a", ARTIFACT_A)));
    stubArtifact(ARTIFACT_A, 11, "https://s3.example.com/a");
    transport.blockFirstSend();

    orchestrator.onEnvironmentReady(ENV);
    assertTrue(transport.awaitFirstSendStarted());
    orchestrator.onEnvironmentReady(OTHER);

    List<List<DaemonSkillPackage>> sends = transport.awaitSends(2);
    assertEquals(2, sends.size());
    transport.releaseFirstSend();
  }

  /** 意图：Package 变更只向本节点持有的 READY Environment 重新下发。 */
  @Test
  void packageChangeResyncsReadyOwnedEnvironments() throws Exception {
    when(packageRepository.listPackages()).thenReturn(List.of(packageRow("pkg-a", ARTIFACT_A)));
    stubArtifact(ARTIFACT_A, 11, "https://s3.example.com/a");
    when(registry.listReadyOwnedByNode())
        .thenReturn(List.of(connection(ENV, List.of()), connection(OTHER, List.of())));

    orchestrator.onPackageChanged("pkg-a");

    assertEquals(2, transport.awaitSends(2).size());
  }

  /** 意图：连接未 READY 时不下发；下一次 READY 触发会重新收敛。 */
  @Test
  void unreadyConnectionIsNotSent() {
    when(registry.find(ENV))
        .thenReturn(Optional.of(connection(ENV, LiveEnvironmentStatus.CONNECTING, List.of())));

    orchestrator.onEnvironmentReady(ENV);

    assertTrue(transport.sent().isEmpty());
  }

  /** 意图：状态上报按 owner + lease token 整体替换投影，并记录一次状态更新事件。 */
  @Test
  void stateReportReplacesTheProjection() {
    when(registry.find(ENV))
        .thenReturn(
            Optional.of(
                connection(
                    ENV,
                    List.of(
                        EnvironmentSkillState.installed("pkg-a", "c".repeat(64), "/old/pkg-a")))));

    orchestrator.onSkillState(
        ENV,
        List.of(
            new DaemonSkillStatePackage("pkg-a", ARTIFACT_A, "/home/dev/skills/pkg-a", null),
            new DaemonSkillStatePackage("pkg-b", null, null, "INSTALL_FAILED")));

    verify(registry)
        .replaceSkillState(
            eq(ENV),
            eq(LEASE_TOKEN),
            eq(
                List.of(
                    EnvironmentSkillState.installed("pkg-a", ARTIFACT_A, "/home/dev/skills/pkg-a"),
                    new EnvironmentSkillState("pkg-b", null, null, "INSTALL_FAILED"))),
            any(EnvironmentEvent.class));
  }

  /** 意图：与当前投影相同的上报不写数据库，避免无意义的环境失效通知。 */
  @Test
  void identicalStateReportDoesNotWrite() {
    EnvironmentSkillState installed =
        EnvironmentSkillState.installed("pkg-a", ARTIFACT_A, "/home/dev/skills/pkg-a");
    when(registry.find(ENV)).thenReturn(Optional.of(connection(ENV, List.of(installed))));

    orchestrator.onSkillState(
        ENV,
        List.of(new DaemonSkillStatePackage("pkg-a", ARTIFACT_A, "/home/dev/skills/pkg-a", null)));

    verify(registry, never()).replaceSkillState(any(), any(), any(), any());
  }

  /** 意图：摘要与路径不成对的整轮上报被拒绝，绝不写入半份投影。 */
  @Test
  void malformedStateReportIsRejectedAsAWhole() {
    orchestrator.onSkillState(
        ENV, List.of(new DaemonSkillStatePackage("pkg-a", ARTIFACT_A, null, null)));

    verify(registry, never()).replaceSkillState(any(), any(), any(), any());
  }

  /** 意图：下发失败只记录，不向触发方抛异常；下一次触发重新收敛。 */
  @Test
  void sendFailureIsContained() throws Exception {
    transport.failure = new IllegalStateException("queue full");
    when(packageRepository.listPackages()).thenReturn(List.of(packageRow("pkg-a", ARTIFACT_A)));
    stubArtifact(ARTIFACT_A, 11, "https://s3.example.com/a");

    assertDoesNotThrow(() -> orchestrator.onEnvironmentReady(ENV));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (transport.attempts() == 0 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertEquals(1, transport.attempts());
  }

  /** 意图：缺失归档的 Package 被跳过，不影响其它包的期望集合。 */
  @Test
  void missingArtifactIsSkipped() throws Exception {
    when(packageRepository.listPackages())
        .thenReturn(List.of(packageRow("pkg-a", ARTIFACT_A), packageRow("pkg-b", ARTIFACT_B)));
    when(blobManager.getBlob(any())).thenReturn(null);

    orchestrator.onEnvironmentReady(ENV);

    assertEquals(1, transport.awaitSends(1).size());
    assertTrue(transport.sent().getFirst().isEmpty());
  }

  private void stubArtifact(String sha256, long size, String url) {
    UUID blobId = blobIdFor(sha256);
    StorageBlob blob = new StorageBlob();
    blob.setId(blobId);
    blob.setSha256(sha256);
    blob.setSizeBytes(size);
    blob.setMediaType("application/zip");
    blob.setRefCount(1);
    blob.setState(StorageBlobState.ACTIVE);
    when(blobManager.getBlob(blobId)).thenReturn(blob);
    StoragePresignedUrlDTO presigned = StoragePresignedUrlDTO.builder().url(url).build();
    when(blobManager.presignOriginalUrl(blobId)).thenReturn(presigned);
  }

  private static UUID blobIdFor(String artifactSha256) {
    return UUID.nameUUIDFromBytes(artifactSha256.getBytes(StandardCharsets.UTF_8));
  }

  private static SkillPackage packageRow(String packageName, String artifactSha256) {
    return new SkillPackage(
        packageName,
        null,
        "v1",
        blobIdFor(artifactSha256),
        List.of(new SkillManifestEntry("dev", "d")),
        NOW,
        0L,
        NOW,
        NOW,
        null);
  }

  private static EnvironmentConnection connection(
      EnvironmentId environmentId, List<EnvironmentSkillState> skillState) {
    return connection(environmentId, LiveEnvironmentStatus.READY, skillState);
  }

  private static EnvironmentConnection connection(
      EnvironmentId environmentId,
      LiveEnvironmentStatus status,
      List<EnvironmentSkillState> skillState) {
    return new EnvironmentConnection(
        environmentId,
        NODE,
        LEASE_TOKEN,
        status,
        status == LiveEnvironmentStatus.READY ? mock(DaemonCapabilities.class) : null,
        skillState,
        List.of(),
        NOW,
        NOW.plusSeconds(60));
  }

  /** 记录下发并可选择阻塞第一次，用于验证单飞与合并语义。 */
  private static final class RecordingTransport implements EnvironmentSkillTransport {
    private final List<List<DaemonSkillPackage>> sends = new CopyOnWriteArrayList<>();
    private final CountDownLatch firstStarted = new CountDownLatch(1);
    private final CountDownLatch firstRelease = new CountDownLatch(1);
    private volatile boolean blockFirst;
    private volatile RuntimeException failure;
    private final AtomicInteger attempts = new AtomicInteger();

    void blockFirstSend() {
      blockFirst = true;
    }

    boolean awaitFirstSendStarted() throws InterruptedException {
      return firstStarted.await(5, TimeUnit.SECONDS);
    }

    void releaseFirstSend() {
      firstRelease.countDown();
    }

    List<List<DaemonSkillPackage>> awaitSends(int count) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (sends.size() < count && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      return List.copyOf(sends);
    }

    List<List<DaemonSkillPackage>> sent() {
      return List.copyOf(sends);
    }

    int attempts() {
      return attempts.get();
    }

    @Override
    public void sendSkillSync(EnvironmentId environmentId, List<DaemonSkillPackage> packages) {
      attempts.incrementAndGet();
      if (blockFirst && sends.isEmpty()) {
        firstStarted.countDown();
        try {
          firstRelease.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
        }
      }
      if (failure != null) {
        throw failure;
      }
      sends.add(packages);
    }
  }
}
