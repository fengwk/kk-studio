package fun.fengwk.kkstudio.platform.environment.skill;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogQueryService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentEvent;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentSkillState;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * EnvironmentSkillSyncOrchestrator 的收敛、围栏与失败隔离测试。
 *
 * <p>测试以「可变的连接行快照」模拟 {@code environment_connection} 行：{@code find} 返回当前行，
 * 写回只在围栏成立时更新该行，因此断言能区分「算出了结果」与「结果真的落库」。
 */
class EnvironmentSkillSyncOrchestratorTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final EnvironmentId OTHER =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");
  private static final UUID NODE = UUID.randomUUID();
  private static final UUID LEASE_TOKEN = UUID.randomUUID();
  private static final String COMMIT_A = "aaaaaaaabbbbbbbbccccccccddddddddaaaaaaaa";
  private static final String COMMIT_B = "bbbbbbbbccccccccddddddddaaaaaaaabbbbbbbb";
  private static final String PREVIOUS_COMMIT = "ccccccccddddddddaaaaaaaabbbbbbbbcccccccc";
  private static final Instant NOW = Instant.parse("2026-09-21T06:00:00Z");
  private static final Duration SYNC_TIMEOUT = Duration.ofMinutes(5);
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final DaemonCapabilities CAPABILITIES =
      new DaemonCapabilities(
          DaemonCapabilities.VERSION,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX, "UTC", "dev", "/home/dev", "Linux environment."));

  private final Map<String, SkillPackage> catalog = new LinkedHashMap<>();
  private final List<EnvironmentSkillState> rowSkillState = new ArrayList<>();
  private final Map<EnvironmentId, LiveEnvironmentStatus> rowStatus = new LinkedHashMap<>();
  private final Map<EnvironmentId, DaemonCapabilities> rowCapabilities = new LinkedHashMap<>();
  private final ScriptedTransport transport = new ScriptedTransport();

  private EnvironmentRegistry registry;
  private SkillCatalogQueryService catalogQuery;
  private EnvironmentSkillSyncOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    registry = mock(EnvironmentRegistry.class);
    catalogQuery = mock(SkillCatalogQueryService.class);
    rowStatus.put(ENV, LiveEnvironmentStatus.READY);
    rowStatus.put(OTHER, LiveEnvironmentStatus.READY);

    when(registry.find(any()))
        .thenAnswer(invocation -> Optional.of(row(invocation.getArgument(0))));
    when(registry.recordSkillEvent(any(), any(), any())).thenReturn(true);
    when(registry.replaceSkillState(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              List<EnvironmentSkillState> state = invocation.getArgument(2);
              rowSkillState.clear();
              rowSkillState.addAll(state);
              return true;
            });
    when(registry.listReadyOwnedByNode()).thenReturn(List.of());
    when(catalogQuery.listPackages()).thenAnswer(invocation -> List.copyOf(catalog.values()));
    when(catalogQuery.getPackage(any()))
        .thenAnswer(invocation -> catalog.get(invocation.getArgument(0)));

    // 直接执行的 executor：异步边界在测试里退化为同步调用，无需等待。
    orchestrator =
        new EnvironmentSkillSyncOrchestrator(
            registry, catalogQuery, transport, new DirectExecutor(), CLOCK);
  }

  /**
   * 测试意图：READY 触发对该 Environment 的全量同步，每个 Package 一次 skill.sync 调用，调用参数只含 Git 事实且不带 workdir，
   * 每次调用使用新的 callId；成功结果在同一围栏下写入安装投影与 SKILL_SYNC_SUCCEEDED 事件。
   */
  @Test
  void readyTriggersFullSyncOfEveryCatalogPackage() throws Exception {
    catalog.put("aaa", skillPackage("aaa", COMMIT_A));
    catalog.put("bbb", skillPackage("bbb", COMMIT_B));

    orchestrator.onEnvironmentReady(ENV);

    assertEquals(2, transport.requests.size());
    for (EnvironmentCapabilityExecutionRequest request : transport.requests) {
      assertEquals(EnvironmentCapabilityIds.SKILL_SYNC, request.descriptor().id());
      assertEquals(SYNC_TIMEOUT, request.timeout());
    }
    assertNotEquals(transport.requests.get(0).call().id(), transport.requests.get(1).call().id());
    assertDoesNotThrow(() -> UUID.fromString(transport.requests.get(0).call().id()));

    JsonNode firstArguments = JSON.readTree(transport.requests.get(0).call().argumentsJson());
    assertEquals("aaa", firstArguments.get("packageName").textValue());
    assertEquals(
        "https://git.example.com/aaa.git", firstArguments.get("repositoryUrl").textValue());
    assertEquals("main", firstArguments.get("branch").textValue());
    assertEquals(COMMIT_A, firstArguments.get("targetCommit").textValue());
    assertFalse(firstArguments.has("workdir"));

    assertEquals(
        List.of(
            EnvironmentSkillState.installed("aaa", COMMIT_A, "/home/dev/.kkstudio/skills/aaa"),
            EnvironmentSkillState.installed("bbb", COMMIT_B, "/home/dev/.kkstudio/skills/bbb")),
        rowSkillState);
    assertEquals(List.of("aaa", "bbb"), startedPackageNames());
    assertEquals(
        List.of(
            EnvironmentEvent.TYPE_SKILL_SYNC_SUCCEEDED, EnvironmentEvent.TYPE_SKILL_SYNC_SUCCEEDED),
        terminalEvents().stream().map(EnvironmentEvent::type).toList());
    assertTrue(terminalEvents().stream().allMatch(event -> !event.isAlert()));
  }

  /** 测试意图：单包失败只影响该包：失败的包保留上一次成功安装的 commit 与本地路径并记录有界去敏错误，其它包继续收敛成功。 */
  @Test
  void singlePackageFailureDoesNotBlockOtherPackagesAndPreservesInstalledFact() {
    catalog.put("aaa", skillPackage("aaa", COMMIT_A));
    catalog.put("bbb", skillPackage("bbb", COMMIT_B));
    rowSkillState.add(
        EnvironmentSkillState.installed(
            "aaa", PREVIOUS_COMMIT, "/home/dev/.kkstudio/skills/aaa-old"));
    transport.responder =
        request -> {
          String packageName = packageName(request);
          if ("aaa".equals(packageName)) {
            return EnvironmentCapabilityResult.codedError(
                request.call().id(), "COMMIT_NOT_FOUND", "commit is not reachable");
          }
          return success(request);
        };

    orchestrator.onEnvironmentReady(ENV);

    assertEquals(2, rowSkillState.size());
    EnvironmentSkillState failed = rowSkillState.get(0);
    assertEquals("aaa", failed.packageName());
    assertEquals(EnvironmentSkillState.STATUS_FAILED, failed.status());
    assertEquals(PREVIOUS_COMMIT, failed.installedCommit());
    assertEquals("/home/dev/.kkstudio/skills/aaa-old", failed.localPath());
    assertEquals("skill package sync failed: COMMIT_NOT_FOUND", failed.error());
    assertEquals(
        EnvironmentSkillState.installed("bbb", COMMIT_B, "/home/dev/.kkstudio/skills/bbb"),
        rowSkillState.get(1));

    List<EnvironmentEvent> terminals = terminalEvents();
    assertEquals(
        List.of(
            EnvironmentEvent.TYPE_SKILL_SYNC_FAILED, EnvironmentEvent.TYPE_SKILL_SYNC_SUCCEEDED),
        terminals.stream().map(EnvironmentEvent::type).toList());
    assertEquals(EnvironmentEvent.LEVEL_ERROR, terminals.get(0).level());
    assertTrue(terminals.get(0).isAlert());
    assertEquals("skill package sync failed: aaa", terminals.get(0).message());
  }

  /**
   * 测试意图：结果解析必须是精确安装事实。Package 名不匹配、commit 不等于本次请求的 currentCommit、commit 非 canonical 形状、localPath
   * 不是目标 OS 上的绝对路径或最后一段不是该 Package 根目录时，一律收敛为固定的去敏失败摘要，绝不写入安装投影。
   */
  @Test
  void malformedOrMismatchedResultsBecomeFixedSanitizedFailure() {
    catalog.put("aaa", skillPackage("aaa", COMMIT_A));
    List<String> malformedResults =
        List.of(
            // Package 名与请求不符
            "{\"packageName\":\"bbb\",\"installedCommit\":\""
                + COMMIT_A
                + "\",\"localPath\":\"/home/dev/.kkstudio/skills/aaa\"}",
            // commit 与本次请求的 currentCommit 不符
            "{\"packageName\":\"aaa\",\"installedCommit\":\""
                + COMMIT_B
                + "\",\"localPath\":\"/home/dev/.kkstudio/skills/aaa\"}",
            // commit 不是 canonical 形状（大写 / 长度不足）
            "{\"packageName\":\"aaa\",\"installedCommit\":\""
                + COMMIT_A.toUpperCase()
                + "\",\"localPath\":\"/home/dev/.kkstudio/skills/aaa\"}",
            "{\"packageName\":\"aaa\",\"installedCommit\":\"abc123\",\"localPath\":\"/home/dev/.kkstudio/skills/aaa\"}",
            // localPath 是相对路径
            "{\"packageName\":\"aaa\",\"installedCommit\":\""
                + COMMIT_A
                + "\",\"localPath\":\"skills/aaa\"}",
            // localPath 未展开占位符
            "{\"packageName\":\"aaa\",\"installedCommit\":\""
                + COMMIT_A
                + "\",\"localPath\":\"~/skills/aaa\"}",
            // localPath 最后一段不是该 Package 根目录
            "{\"packageName\":\"aaa\",\"installedCommit\":\""
                + COMMIT_A
                + "\",\"localPath\":\"/home/dev/.kkstudio/skills\"}",
            "{\"packageName\":\"aaa\",\"installedCommit\":\""
                + COMMIT_A
                + "\",\"localPath\":\"/home/dev/.kkstudio/skills/aaa/\"}",
            // 缺少字段
            "{\"packageName\":\"aaa\",\"installedCommit\":\"" + COMMIT_A + "\"}",
            // 多余字段
            "{\"packageName\":\"aaa\",\"installedCommit\":\""
                + COMMIT_A
                + "\",\"localPath\":\"/home/dev/.kkstudio/skills/aaa\",\"extra\":\"x\"}",
            // 非 object
            "[]",
            // 非文本
            "{\"packageName\":\"aaa\",\"installedCommit\":123,\"localPath\":\"/home/dev/.kkstudio/skills/aaa\"}");

    for (String malformed : malformedResults) {
      transport.responder =
          request -> EnvironmentCapabilityResult.json(request.call().id(), malformed);

      orchestrator.onEnvironmentReady(ENV);

      EnvironmentSkillState failed = rowSkillState.get(0);
      assertEquals(EnvironmentSkillState.STATUS_FAILED, failed.status(), malformed);
      assertEquals(null, failed.installedCommit(), malformed);
      assertEquals(null, failed.localPath(), malformed);
      assertEquals("skill package sync failed", failed.error(), malformed);
      assertEquals(
          EnvironmentEvent.TYPE_SKILL_SYNC_FAILED, terminalEvents().get(0).type(), malformed);
      assertTrue(terminalEvents().get(0).isAlert(), malformed);
    }
  }

  /** 测试意图：目标 Daemon 是 Windows 时接受 drive path 与 UNC 的本地根，但仍要求最后一段是该 Package 根目录。 */
  @Test
  void windowsLocalPathMustStillResolveToPackageRoot() {
    catalog.put("aaa", skillPackage("aaa", COMMIT_A));
    rowCapabilities.put(
        ENV,
        new DaemonCapabilities(
            DaemonCapabilities.VERSION,
            new DaemonEnvironmentInfo(
                // 只覆盖目标 OS 对 localPath 的冻结形状；其余宿主字段与路径判定无关。
                DaemonOperatingSystem.WINDOWS, "UTC", "dev", "/home/dev", "Windows environment.")));
    transport.responder =
        request ->
            EnvironmentCapabilityResult.json(
                request.call().id(),
                "{\"packageName\":\"aaa\",\"installedCommit\":\""
                    + COMMIT_A
                    + "\",\"localPath\":\"C:\\\\Users\\\\dev\\\\.kkstudio\\\\skills\\\\aaa\"}");

    orchestrator.onEnvironmentReady(ENV);

    assertEquals(
        List.of(
            EnvironmentSkillState.installed(
                "aaa", COMMIT_A, "C:\\Users\\dev\\.kkstudio\\skills\\aaa")),
        rowSkillState);

    transport.responder =
        request ->
            EnvironmentCapabilityResult.json(
                request.call().id(),
                "{\"packageName\":\"aaa\",\"installedCommit\":\""
                    + COMMIT_A
                    + "\",\"localPath\":\"skills\\\\aaa\"}");

    orchestrator.onEnvironmentReady(ENV);

    assertEquals(EnvironmentSkillState.STATUS_FAILED, rowSkillState.get(0).status());
    assertEquals("skill package sync failed", rowSkillState.get(0).error());
  }

  /** 测试意图：围栏在发送前就失效（事件写不进 READY 持有者的行）时，编排器必须放弃整次同步，绝不执行调用也绝不写回。 */
  @Test
  void fenceLostBeforeSendSkipsInvocationEntirely() {
    catalog.put("aaa", skillPackage("aaa", COMMIT_A));
    when(registry.recordSkillEvent(any(), any(), any())).thenReturn(false);

    orchestrator.onEnvironmentReady(ENV);

    assertEquals(List.of(), transport.requests);
    verify(registry, never()).replaceSkillState(any(), any(), any(), any());
  }

  /** 测试意图：READY 之外的连接状态（离线 / 重新 CONNECTING）不参与同步，绝不向非 READY 行发送调用。 */
  @Test
  void nonReadyConnectionIsNeverSentTo() {
    catalog.put("aaa", skillPackage("aaa", COMMIT_A));
    rowStatus.put(ENV, LiveEnvironmentStatus.CONNECTING);

    orchestrator.onPackageChanged("aaa");

    assertEquals(List.of(), transport.requests);
    verify(registry, never()).replaceSkillState(any(), any(), any(), any());
  }

  /** 测试意图：调用已发出但结果写回时围栏失效（租约被接管）必须静默放弃：不抛异常、不污染当前行。 */
  @Test
  void lateResultIsDiscardedWhenFenceIsLostDuringSend() {
    catalog.put("aaa", skillPackage("aaa", COMMIT_A));
    doReturn(false).when(registry).replaceSkillState(any(), any(), any(), any());

    assertDoesNotThrow(() -> orchestrator.onEnvironmentReady(ENV));

    assertEquals(1, transport.requests.size());
    assertEquals(List.of(), rowSkillState);
  }

  /** 测试意图：发送结果不确定（send-uncertain）绝不重放：只尝试一次，并把该 Package 记为失败。 */
  @Test
  void sendUncertainIsNeverReplayed() {
    catalog.put("aaa", skillPackage("aaa", COMMIT_A));
    transport.responder =
        request -> {
          throw new EnvironmentCapabilitySendUncertainException("transport lost the reply");
        };

    orchestrator.onEnvironmentReady(ENV);

    assertEquals(1, transport.requests.size());
    assertEquals(EnvironmentSkillState.STATUS_FAILED, rowSkillState.get(0).status());
    assertEquals("skill package sync could not be sent", rowSkillState.get(0).error());
    assertEquals(null, rowSkillState.get(0).installedCommit());
  }

  /** 测试意图：Package 变更通知只做单 Package 增量同步，且目标集合严格来自本节点持有的 READY 行。 */
  @Test
  void packageChangedSyncsOnlyLocallyOwnedReadyEnvironments() {
    catalog.put("aaa", skillPackage("aaa", COMMIT_A));
    catalog.put("bbb", skillPackage("bbb", COMMIT_B));
    rowStatus.put(OTHER, LiveEnvironmentStatus.CONNECTING);
    when(registry.listReadyOwnedByNode()).thenReturn(List.of(row(ENV), row(OTHER)));

    orchestrator.onPackageChanged("aaa");

    assertEquals(1, transport.requests.size());
    assertEquals("aaa", packageName(transport.requests.get(0)));
    assertEquals(
        List.of(EnvironmentSkillState.installed("aaa", COMMIT_A, "/home/dev/.kkstudio/skills/aaa")),
        rowSkillState);
  }

  /** 测试意图：catalog 中已不存在的 Package 不做任何写：残留投影只由下一次 READY 全量同步重建。 */
  @Test
  void packageChangedForUnknownPackageDoesNothing() {
    when(registry.listReadyOwnedByNode()).thenReturn(List.of(row(ENV)));

    orchestrator.onPackageChanged("gone");

    assertEquals(List.of(), transport.requests);
    verify(registry, never()).replaceSkillState(any(), any(), any(), any());
  }

  /** 测试意图：通知重连对账对本节点全部 READY Environment 各做一次全量同步，并对缺少 catalog 事实的调用保持 no-op。 */
  @Test
  void reconcileResyncsEveryOwnedReadyEnvironment() {
    catalog.put("aaa", skillPackage("aaa", COMMIT_A));
    when(registry.listReadyOwnedByNode()).thenReturn(List.of(row(ENV), row(OTHER)));

    orchestrator.reconcileReadyEnvironments();

    assertEquals(2, transport.requests.size());
    // 两个 READY Environment 各做一次全量同步。
    assertEquals(List.of("aaa", "aaa"), startedPackageNames());
    assertDoesNotThrow(() -> orchestrator.onEnvironmentReady(null));
    assertDoesNotThrow(() -> orchestrator.onPackageChanged(" "));
    assertEquals(2, transport.requests.size());
  }

  /** 测试意图：onEnvironmentReady 绝不在会话核心线程上执行发送或数据库 IO，发送只在 executor 上发生。 */
  @Test
  void readyEventReturnsBeforeAnySendHappens() throws Exception {
    catalog.put("aaa", skillPackage("aaa", COMMIT_A));
    CountDownLatch sendStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    transport.beforeComplete =
        () -> {
          sendStarted.countDown();
          await(release);
        };
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      EnvironmentSkillSyncOrchestrator async =
          new EnvironmentSkillSyncOrchestrator(registry, catalogQuery, transport, executor, CLOCK);

      assertTimeoutPreemptively(
          Duration.ofSeconds(5),
          () -> {
            async.onEnvironmentReady(ENV);
            async.onEnvironmentReady(ENV);
          });
      assertTrue(sendStarted.await(5, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      executor.shutdown();
    }

    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
  }

  /** 测试意图：同一 Environment 的同步在进程内串行，两个并发触发的同步绝不并发进入发送路径。 */
  @Test
  void concurrentTriggersForSameEnvironmentAreSerialized() throws Exception {
    catalog.put("aaa", skillPackage("aaa", COMMIT_A));
    CountDownLatch firstSendStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    transport.beforeComplete =
        () -> {
          if (firstSendStarted.getCount() > 0) {
            firstSendStarted.countDown();
            await(release);
          }
        };
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      EnvironmentSkillSyncOrchestrator async =
          new EnvironmentSkillSyncOrchestrator(registry, catalogQuery, transport, executor, CLOCK);
      async.onEnvironmentReady(ENV);
      assertTrue(firstSendStarted.await(5, TimeUnit.SECONDS));
      async.onEnvironmentReady(ENV);
      release.countDown();
    } finally {
      executor.shutdown();
    }

    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    assertEquals(2, transport.requests.size());
    assertEquals(1, transport.maxInFlight.get());
    assertEquals(1, rowSkillState.size());
    assertEquals(
        EnvironmentSkillState.installed("aaa", COMMIT_A, "/home/dev/.kkstudio/skills/aaa"),
        rowSkillState.get(0));
  }

  private List<String> startedPackageNames() {
    List<String> packages = new ArrayList<>();
    for (EnvironmentEvent event : recordedEvents()) {
      if (EnvironmentEvent.TYPE_SKILL_SYNC_STARTED.equals(event.type())) {
        packages.add(event.message().substring("skill package sync started: ".length()));
      }
    }
    return packages;
  }

  private List<EnvironmentEvent> terminalEvents() {
    return recordedEvents().stream()
        .filter(event -> !EnvironmentEvent.TYPE_SKILL_SYNC_STARTED.equals(event.type()))
        .toList();
  }

  private List<EnvironmentEvent> recordedEvents() {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<EnvironmentEvent> captor = ArgumentCaptor.forClass(EnvironmentEvent.class);
    verify(registry, atLeast(0)).recordSkillEvent(any(), any(), captor.capture());
    List<EnvironmentEvent> events = new ArrayList<>(captor.getAllValues());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<EnvironmentEvent> terminalCaptor =
        ArgumentCaptor.forClass(EnvironmentEvent.class);
    verify(registry, atLeast(0)).replaceSkillState(any(), any(), any(), terminalCaptor.capture());
    events.addAll(terminalCaptor.getAllValues());
    return events;
  }

  private static String packageName(EnvironmentCapabilityExecutionRequest request) {
    try {
      return JSON.readTree(request.call().argumentsJson()).get("packageName").textValue();
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static EnvironmentCapabilityResult success(
      EnvironmentCapabilityExecutionRequest request) {
    String packageName = packageName(request);
    String targetCommit;
    try {
      targetCommit = JSON.readTree(request.call().argumentsJson()).get("targetCommit").textValue();
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    return EnvironmentCapabilityResult.json(
        request.call().id(),
        "{\"packageName\":\""
            + packageName
            + "\",\"installedCommit\":\""
            + targetCommit
            + "\",\"localPath\":\"/home/dev/.kkstudio/skills/"
            + packageName
            + "\"}");
  }

  private EnvironmentConnection row(EnvironmentId environmentId) {
    return new EnvironmentConnection(
        environmentId,
        NODE,
        LEASE_TOKEN,
        rowStatus.getOrDefault(environmentId, LiveEnvironmentStatus.READY),
        rowCapabilities.getOrDefault(environmentId, CAPABILITIES),
        List.copyOf(rowSkillState),
        List.of(),
        NOW,
        NOW.plusSeconds(60));
  }

  private static SkillPackage skillPackage(String packageName, String commit) {
    SkillPackage skillPackage = new SkillPackage();
    skillPackage.setPackageName(packageName);
    skillPackage.setRepositoryUrl("https://git.example.com/" + packageName + ".git");
    skillPackage.setBranch("main");
    skillPackage.setCurrentCommit(commit);
    return skillPackage;
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }

  /** 在当前线程直接执行任务的 ExecutorService：把编排器的异步边界变成可断言的同步路径。 */
  private static final class DirectExecutor extends AbstractExecutorService {

    private volatile boolean shutdown;

    @Override
    public void execute(Runnable command) {
      command.run();
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return shutdown;
    }
  }

  /** 记录请求并同步回调终态结果的传输桩；responder 可抛出异常以模拟发送前/不确定失败。 */
  private static final class ScriptedTransport implements EnvironmentCapabilityTransport {

    private final List<EnvironmentCapabilityExecutionRequest> requests =
        Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private Function<EnvironmentCapabilityExecutionRequest, EnvironmentCapabilityResult> responder =
        EnvironmentSkillSyncOrchestratorTest::success;
    private Runnable beforeComplete = () -> {};

    @Override
    public EnvironmentCapabilityExecutionHandle invoke(
        EnvironmentId environmentId,
        EnvironmentCapabilityExecutionRequest request,
        EnvironmentCapabilityExecutionListener listener) {
      requests.add(request);
      maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
      try {
        beforeComplete.run();
        listener.onComplete(responder.apply(request));
      } finally {
        inFlight.decrementAndGet();
      }
      return new Handle();
    }
  }

  private static final class Handle implements EnvironmentCapabilityExecutionHandle {

    @Override
    public void cancel() {}

    @Override
    public boolean isCancelled() {
      return false;
    }
  }
}
