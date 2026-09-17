package fun.fengwk.kkstudio.harness.daemon;

import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.CANCELLED;
import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.COMPLETED;
import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.ERROR;
import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.HELLO;
import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.PROGRESS;
import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.READY;
import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.STARTED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.daemon.coding.CodingToolsConfig;
import fun.fengwk.kkstudio.harness.daemon.coding.ReadCapability;
import fun.fengwk.kkstudio.harness.daemon.coding.TestCodingConfig;
import fun.fengwk.kkstudio.harness.daemon.coding.WriteCapability;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationState;
import fun.fengwk.kkstudio.harness.daemon.journal.InMemoryDaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillRegistry;
import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillTestSupport;
import fun.fengwk.kkstudio.harness.daemon.skill.SkillLoadCapability;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonConnection;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransport;
import fun.fengwk.kkstudio.harness.daemon.transport.DaemonTransportListener;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResultCodes;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityResultCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonPresignedPut;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceTransferCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Daemon 生命周期及本地 Environment Capability SPI 的协议集成测试。 */
class DaemonRuntimeTest {

  private static final long ASYNC_TEST_TIMEOUT_SECONDS = 5;

  /** WELCOME 通告的资源字节预算；足够覆盖测试中的小资源。 */
  private static final long MAX_RESOURCE_BYTES = 1024L * 1024L;

  private static final EnvironmentId ENVIRONMENT_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final Path ENVIRONMENT_ROOT = Path.of(System.getProperty("user.dir"));

  /** 测试用注册凭证：与真实部署一样，只以 owner-only 文件形式存在，argv/日志中不出现明文。 */
  private static final String REGISTRATION_TOKEN = "test-registration-token";

  private final DaemonEnvelopeCodec codec = new DaemonEnvelopeCodec();
  private final DaemonCapabilityResultCodec resultCodec = new DaemonCapabilityResultCodec();
  private final DaemonResourceTransferCodec transferCodec = new DaemonResourceTransferCodec();
  private DaemonRuntime runtime;
  private FakeTransport handshakeTransport;

  /** 每个测试独享的宿主根目录：registry 数据目录建立在它下面，测试之间不共享持久状态。 */
  @TempDir Path testRoot;

  /** 为一次 registry 构造创建隔离的数据目录父目录。 */
  private Path dataDir() {
    return testRoot.resolve("daemon-data");
  }

  /**
   * 物化一个 owner-only 的注册凭证文件。
   *
   * <p>DaemonConfig 只接受凭证文件路径，因此每个需要凭证的用例都必须先准备这个文件；权限位与生产部署一致（POSIX 上 0600）。
   */
  private Path registrationTokenFile() {
    Path directory = testRoot.resolve("credentials");
    Path tokenFile = directory.resolve("registration.token");
    if (!Files.exists(tokenFile)) {
      try {
        Files.createDirectories(directory);
        Files.writeString(tokenFile, REGISTRATION_TOKEN);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
          Files.setPosixFilePermissions(
              tokenFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      }
    }
    return tokenFile;
  }

  @AfterEach
  void closeRuntime() {
    if (runtime != null) {
      runtime.close();
    }
  }

  /** 注册身份已被另一 live daemon 持有是终态冲突：daemon 进入 FAILED、停止重连并释放终止闩。 */
  @Test
  void registrationRejectedErrorIsTerminalFailureWithoutReconnect() throws Exception {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestCapability());

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);

    transport.receiveRaw(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"ERROR\",\"environmentId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"payload\":{\"code\":\"REGISTRATION_REJECTED\","
            + "\"message\":\"environment already bound to another active daemon\"}}");

    assertEquals(DaemonRuntimeState.FAILED, runtime.state());
    assertEquals(DaemonRuntimeState.FAILED, runtime.awaitTermination());
    assertTrue(runtime.failureReason().contains("environment registration is rejected"));

    // FAILED 后不得安排新的重连：连接计数保持现状，不会再有新的 HELLO。
    transport.awaitNoNewConnection(500);
  }

  /** ERROR RETRY_LATER 应主动关闭连接触发退避重连，不进入 FAILED 终态。 */
  @Test
  void retryLaterErrorClosesConnectionAndSchedulesReconnectWithoutTerminalFailure()
      throws Exception {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestCapability());

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);

    transport.receiveRaw(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"ERROR\",\"environmentId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"payload\":{\"code\":\"RETRY_LATER\","
            + "\"message\":\"server busy, retry later\"}}");

    assertNotEquals(DaemonRuntimeState.FAILED, runtime.state());
    transport.awaitConnections(1);
    completeHandshake();
    assertMessageTypes(transport.takeMessages(2), HELLO, READY);
  }

  /** 非冲突 ERROR（如普通协议提示）不终止 daemon。 */
  @Test
  void nonConflictErrorDoesNotTerminate() throws Exception {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestCapability());

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receiveRaw(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"ERROR\",\"environmentId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"payload\":{\"message\":\"informational\"}}");
    assertEquals(DaemonRuntimeState.READY, runtime.state());
  }

  /** 断线后必须重连并重新完成 HELLO/WELCOME/READY 的握手，使 daemon 在新连接上重新进入 READY 状态。 */
  @Test
  void reconnectsAfterDisconnectAndReannouncesReadiness() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestCapability(), "Custom & stable environment.");

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    List<DaemonEnvelope> handshake = transport.takeMessages(2);
    assertMessageTypes(handshake, HELLO, READY);
    JsonNode hello = codec.readPayload(handshake.get(0));
    assertEquals(REGISTRATION_TOKEN, hello.path("registrationToken").asText());
    assertEquals(DaemonProtocol.VERSION, hello.path("protocolVersion").asInt());
    assertEquals(
        EnvironmentCapabilityCatalog.version(), hello.path("capabilityCatalogVersion").asText());
    assertTrue(hello.path("toolCatalogVersion").isMissingNode());
    DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();
    DaemonEnvironmentInfo firstEnvironment =
        capabilitiesCodec.decode(handshake.get(1).payloadJson()).environment();
    assertEquals("Custom & stable environment.", firstEnvironment.note());
    assertTrue(codec.readPayload(handshake.get(1)).path("skillSources").isArray());

    transport.disconnect();
    transport.awaitConnections(1);
    completeHandshake();
    List<DaemonEnvelope> reconnected = transport.takeMessages(2);
    assertMessageTypes(reconnected, HELLO, READY);
    assertEquals(
        firstEnvironment, capabilitiesCodec.decode(reconnected.get(1).payloadJson()).environment());
    assertEquals(DaemonRuntimeState.READY, runtime.state());
  }

  /** 生产构造器不能为不完整的 registry 公布固定的 catalog。 */
  @Test
  void productionRuntimeRejectsRegistryThatDoesNotMatchFixedCatalog() {
    DaemonConfig config =
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            registrationTokenFile(),
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            null,
            ENVIRONMENT_ROOT,
            dataDir());

    assertThrows(
        IllegalStateException.class,
        () ->
            DaemonRuntime.create(
                config,
                DaemonSkillTestSupport.open(dataDir()),
                null,
                (registry, executor, scheduler) -> registry.register(new TestCapability())));
  }

  /** 生产工厂必须在同一装配点创建资源、注入完整固定 capability 目录，并把生命周期移交给可关闭 runtime。 */
  @Test
  void productionFactoryCreatesCompleteClosableRuntime() {
    DaemonConfig config =
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            registrationTokenFile(),
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            null,
            ENVIRONMENT_ROOT,
            dataDir());
    CodingToolsConfig toolsConfig = TestCodingConfig.withBridge(ENVIRONMENT_ROOT);

    runtime = DaemonRuntime.create(config, toolsConfig, DaemonSkillTestSupport.open(dataDir()));

    assertEquals(DaemonRuntimeState.STOPPED, runtime.state());
    runtime.close();
    assertEquals(DaemonRuntimeState.STOPPED, runtime.state());
  }

  /** 生产装配在 capability 注册失败时必须释放已经创建的 scheduler/executor。 */
  @Test
  void productionConstructionFailureReleasesCreatedLifecycleResources() throws Exception {
    DaemonConfig config =
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            registrationTokenFile(),
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            null,
            ENVIRONMENT_ROOT,
            dataDir());
    AtomicReference<ExecutorService> executorRef = new AtomicReference<>();
    AtomicReference<ScheduledExecutorService> schedulerRef = new AtomicReference<>();

    assertThrows(
        IllegalStateException.class,
        () ->
            DaemonRuntime.create(
                config,
                DaemonSkillTestSupport.open(dataDir()),
                null,
                (registry, executor, scheduler) -> {
                  executorRef.set(executor);
                  schedulerRef.set(scheduler);
                  throw new IllegalStateException("registration failed");
                }));

    assertTrue(executorRef.get().isShutdown());
    assertTrue(schedulerRef.get().isShutdown());
    assertTrue(executorRef.get().awaitTermination(1, TimeUnit.SECONDS));
    assertTrue(schedulerRef.get().awaitTermination(1, TimeUnit.SECONDS));
  }

  /**
   * Daemon 发出的 READY payload 必须能被 Cloud 共享的 capabilities codec 解码回完整能力对象（skills），避免 Cloud/Daemon
   * 协议漂移。
   */
  @Test
  void readyCapabilitiesPayloadIsFullyDecodableBySharedCodec() throws Exception {
    Path skillRoot = Files.createTempDirectory("daemon-skills-codec");
    Path skillDir = skillRoot.resolve("demo");
    Files.createDirectories(skillDir);
    Files.writeString(
        skillDir.resolve("SKILL.md"), "---\nname: demo\ndescription: Demo skill\n---\n# Demo\n");
    try {
      DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();
      FakeTransport transport = new FakeTransport();
      DaemonSkillRegistry skillRegistry =
          DaemonSkillTestSupport.publish(dataDir(), skillRoot, 1).registry();
      runtime =
          runtime(
              transport,
              new TestCapability(),
              skillRegistry,
              Duration.ofMinutes(1),
              Duration.ofSeconds(10));

      runtime.start();
      transport.awaitConnections(1);
      completeHandshake();
      List<DaemonEnvelope> handshake = transport.takeMessages(2);
      assertMessageTypes(handshake, HELLO, READY);

      DaemonCapabilities capabilities = capabilitiesCodec.decode(handshake.get(1).payloadJson());

      assertEquals(DaemonCapabilities.VERSION, capabilities.version());
      // READY 必须携带已发布来源集合的生成版本，Platform 以它为该报告做持久围栏。
      assertEquals(1L, capabilities.sourceSetVersion());
      assertEquals(ZoneId.systemDefault().getId(), capabilities.environment().timeZone());
      assertEquals(
          DaemonOperatingSystemDetector.detectCurrent(),
          capabilities.environment().operatingSystem());
      assertEquals(
          new DaemonConfig(
                  URI.create("ws://localhost/gateway"),
                  registrationTokenFile(),
                  Duration.ofMinutes(1),
                  Duration.ZERO,
                  Duration.ofSeconds(1),
                  Duration.ofSeconds(10),
                  null,
                  ENVIRONMENT_ROOT,
                  dataDir())
              .effectiveNote(capabilities.environment().operatingSystem()),
          capabilities.environment().note());
      List<DaemonSkillDescriptor> flat = capabilities.flattenSkills();
      assertEquals(1, flat.size());
      assertEquals("demo", flat.get(0).name());
      assertEquals("Demo skill", flat.get(0).description());
      assertEquals(skillDir.toRealPath().toString(), flat.get(0).baseDirectory());
    } finally {
      deleteRecursively(skillRoot);
    }
  }

  /** READY 后必须在配置周期内发送 HEARTBEAT。 */
  @Test
  void sendsHeartbeatWhileReady() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestCapability(), Duration.ofMillis(20));

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    List<DaemonEnvelope> handshake = transport.takeMessages(3);
    assertMessageTypes(handshake, HELLO, READY, DaemonMessageType.HEARTBEAT);

    assertMessageTypes(List.of(transport.takeNextMessage()), DaemonMessageType.HEARTBEAT);
  }

  /** 首次连接失败后必须按重连生命周期再次尝试并完成握手。 */
  @Test
  void reconnectsAfterConnectionFailure() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    transport.failNextConnection();
    runtime = runtime(transport, new TestCapability());

    runtime.start();
    transport.awaitConnections(2);
    completeHandshake();

    assertMessageTypes(transport.takeMessages(2), HELLO, READY);
  }

  /** 任一出站 send failure 都使连接失效；重连后 journal 仍阻止 invocation 重启。 */
  @Test
  void reconnectsAfterSendFailureWithoutRestartingInvocation() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.failNextSend();
    transport.receive(invoke("send-failure"));
    transport.awaitConnections(1);
    completeHandshake();
    assertMessageTypes(transport.takeMessages(2), HELLO, READY);
    assertEquals(1, tool.executions.get());

    transport.receive(invoke("send-failure"));
    assertMessageTypes(transport.takeMessages(1), STARTED);
    assertEquals(1, tool.executions.get());
  }

  /** 未注册 capability 必须以 FAILED 终态返回，而不是让协议处理线程失败。 */
  @Test
  void returnsFailedTerminalForUnknownCapability() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    InMemoryDaemonInvocationJournal journal = new InMemoryDaemonInvocationJournal();
    runtime = runtime(transport, new TestCapability(), journal);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invoke("unknown-invocation", "missing"));

    List<DaemonEnvelope> messages = transport.takeMessages(1);
    assertMessageTypes(messages, DaemonMessageType.FAILED);
    assertTrue(messages.get(0).payloadJson().contains("unknown Environment capability"));
    assertEquals(
        DaemonInvocationState.FAILED, journal.find("unknown-invocation").orElseThrow().state());
  }

  /**
   * 测试意图：v1 INVOKE 外壳不携带目录，coding workdir 只存在于具体 arguments 中——携带已删除的 {@code workspacePath}
   * 字段（文本或数字）必须在协议边界作为 unknown field 拒绝，且不触达 capability SPI。
   */
  @Test
  void rejectsUnknownWorkspacePathFieldInV1InvokePayloadBeforeSideEffects()
      throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);

    transport.receiveRaw(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"INVOKE\","
            + "\"environmentId\":\"11111111-1111-1111-1111-111111111111\",\"payload\":{\"capabilityId\":\"test\","
            + "\"capabilityVersion\":\"1.0.0\",\"arguments\":{},\"timeoutMillis\":100}}");
    assertMessageTypes(transport.takeMessages(1), ERROR);

    transport.receiveRaw(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"INVOKE\","
            + "\"environmentId\":\"11111111-1111-1111-1111-111111111111\",\"payload\":{\"capabilityId\":\"test\","
            + "\"capabilityVersion\":\"1.0.0\",\"workspacePath\":1,\"arguments\":{},\"timeoutMillis\":100}}");
    assertMessageTypes(transport.takeMessages(1), ERROR);

    transport.receiveRaw(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"INVOKE\","
            + "\"environmentId\":\"11111111-1111-1111-1111-111111111111\",\"payload\":{\"capabilityId\":\"test\","
            + "\"capabilityVersion\":\"1.0.0\",\"workspacePath\":\".\",\"arguments\":{},\"timeoutMillis\":100}}");
    assertMessageTypes(transport.takeMessages(1), ERROR);
    assertEquals(0, tool.executions.get());

    // 去掉 workspacePath 的严格 v1 payload 正常执行，证明拒绝只针对该未知字段。
    transport.receive(invoke("valid-after-rejected-workspace"));
    transport.takeMessages(1);
    assertEquals(1, tool.executions.get());
  }

  /**
   * 测试意图：coding capability 的 workdir 只来自该次调用 arguments，缺失时在请求构造期被 schema 确定性拒绝（因此没有 STARTED），且绝不回退到
   * Environment Root。
   *
   * <p>用真实 {@link ReadCapability}：Environment Root 下放置同名文件；若实现发生回退，capability 就能读到该文件并在 COMPLETED
   * 内容中出现其文本，从而被本测试捕获。
   */
  @Test
  void omittedWorkdirIsRejectedWithoutEnvironmentRootFallback() throws Exception {
    Path root = Files.createTempDirectory("daemon-workdir-root");
    try {
      Files.writeString(root.resolve("local.txt"), "from-environment-root");
      FakeTransport transport = new FakeTransport();
      DaemonCapabilityRegistry registry = new DaemonCapabilityRegistry();
      registry.register(
          new ReadCapability(
              TestCodingConfig.withBridge(root), Executors.newVirtualThreadPerTaskExecutor()));
      runtime = runtime(transport, registry, root);

      runtime.start();
      transport.awaitConnections(1);
      completeHandshake();
      transport.takeMessages(2);

      // 省略 workdir：schema 必填校验在构造执行请求时失败 → 直接 FAILED，绝不发出 STARTED。
      transport.receive(invoke("missing-workdir", "fs.read", "1", 100, "{\"path\":\"local.txt\"}"));
      List<DaemonEnvelope> missing = transport.takeMessages(1);
      assertMessageTypes(missing, DaemonMessageType.FAILED);
      String failure = missing.get(0).payloadJson();
      assertTrue(failure.contains("workdir"), failure);
      assertFalse(failure.contains("from-environment-root"), failure);

      // 显式绝对 workdir 正常执行并读到该目录下的文件；可见内容证明目录来自 arguments 而非 Environment Root。
      transport.receive(
          invoke(
              "explicit-workdir",
              "fs.read",
              "1",
              100,
              "{\"path\":\"local.txt\",\"workdir\":\"" + jsonEscape(root.toString()) + "\"}"));
      assertMessageTypes(transport.takeMessages(2), STARTED, DaemonMessageType.COMPLETED);
    } finally {
      deleteRecursively(root);
    }
  }

  /** 引用错误的 Environment 逻辑名称或非法 INVOKE payload 必须得到明确 ERROR 响应。 */
  @Test
  void rejectsWrongScopeAndMalformedInvocationPayload() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestCapability());

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION,
            DaemonMessageType.INVOKE,
            EnvironmentId.parse("22222222-2222-2222-2222-222222222222"),
            "wrong-scope",
            "{\"capabilityId\":\"test\",\"arguments\":{}}"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);

    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION,
            DaemonMessageType.INVOKE,
            ENVIRONMENT_ID,
            "bad-payload",
            "{\"capabilityId\":\"test\",\"arguments\":[]}"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);

    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION,
            DaemonMessageType.INVOKE,
            ENVIRONMENT_ID,
            "invalid-capability-id",
            "{\"capabilityId\":\"TEST\",\"capabilityVersion\":\"1.0.0\","
                + "\"arguments\":{},\"timeoutMillis\":1000}"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
  }

  /** 不支持的协议版本必须在 codec 边界拒绝，不会触达 scope 或 invocation 生命周期。 */
  @Test
  void rejectsUnsupportedProtocolVersions() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receiveRaw(
        "{\"protocolVersion\":3,\"messageType\":\"INVOKE\","
            + "\"environmentId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"payload\":{\"capabilityId\":\"test\",\"capabilityVersion\":\"1.0.0\","
            + "\"arguments\":{},\"timeoutMillis\":1000}}");
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    assertEquals(0, tool.executions.get());
    transport.receiveRaw(
        "{\"protocolVersion\":4,\"messageType\":\"INVOKE\","
            + "\"environmentId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"payload\":{\"capabilityId\":\"test\",\"capabilityVersion\":\"1.0.0\","
            + "\"arguments\":{},\"timeoutMillis\":1000}}");
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    assertEquals(0, tool.executions.get());
    transport.receive(invoke("valid-after-unsupported-version"));
    assertMessageTypes(transport.takeMessages(1), STARTED);
  }

  /** 缺失 invocationId 在 codec 边界失败，不得触达 journal 或 capability SPI。 */
  @Test
  void rejectsInvokeWithoutInvocationIdBeforeSideEffects() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receiveRaw(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"INVOKE\","
            + "\"environmentId\":\"11111111-1111-1111-1111-111111111111\",\"payload\":{\"capabilityId\":\"test\","
            + "\"capabilityVersion\":\"1.0.0\",\"arguments\":{},"
            + "\"timeoutMillis\":1000}}");

    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    transport.receiveRaw(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"CANCEL\","
            + "\"environmentId\":\"11111111-1111-1111-1111-111111111111\",\"payload\":{}}");
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    assertEquals(0, tool.executions.get());
    transport.receive(invoke("valid-after-missing-id"));
    assertMessageTypes(transport.takeMessages(1), STARTED);
  }

  /** 测试意图：wire 协议没有序号，同一 invocationId 的重复 INVOKE（同实例重连后的重放）必须复用 journal 并重放 STARTED，绝不重复执行。 */
  @Test
  void replaysInvokeByInvocationIdWithoutRestartingExecution() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    DaemonEnvelope invoke = invoke("replayed-invocation");
    transport.receive(invoke);
    assertMessageTypes(transport.takeMessages(1), STARTED);
    transport.receive(invoke);
    List<DaemonEnvelope> replayed = transport.takeMessages(1);
    assertMessageTypes(replayed, STARTED);
    assertEquals("{\"replayed\":true}", replayed.get(0).payloadJson());
    assertEquals(1, tool.executions.get());

    transport.receive(cancel("replayed-invocation"));
    assertMessageTypes(transport.takeMessages(1), CANCELLED);
  }

  /** 测试意图：只有 ERROR 是服务端控制消息，到达时不创建 invocation 也不产生响应；daemon 不得接收自身的 READY 等消息类型。 */
  @Test
  void acceptsServerControlMessagesWithoutCreatingInvocations() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(platformMessage(DaemonMessageType.ERROR));
    assertFalse(transport.hasMessages());

    transport.receive(platformMessage(DaemonMessageType.READY));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);

    transport.receive(invoke("after-control"));
    assertMessageTypes(transport.takeMessages(1), STARTED);
    assertEquals(1, tool.executions.get());
  }

  /** skill.load 作为标准 capability 执行：按精确 (sourceId,name,revision) 返回正文与 baseDirectory。 */
  @Test
  void loadsSkillViaCapabilityInvocation() throws Exception {
    Path skillRoot = Files.createTempDirectory("daemon-skill-root");
    Path skillDir = skillRoot.resolve("my-skill");
    Files.createDirectories(skillDir);
    Files.writeString(
        skillDir.resolve("SKILL.md"),
        "---\nname: my-skill\ndescription: A test skill\n---\n# My Skill Body\nInstruction content.");
    try {
      DaemonSkillRegistry skillRegistry =
          DaemonSkillTestSupport.publish(dataDir(), skillRoot, 1).registry();
      DaemonSkillDescriptor descriptor = skillRegistry.descriptors().getFirst();
      FakeTransport transport = new FakeTransport();
      DaemonCapabilityRegistry registry = new DaemonCapabilityRegistry();
      registry.register(
          new SkillLoadCapability(skillRegistry, Executors.newVirtualThreadPerTaskExecutor()));
      runtime = runtime(transport, registry, skillRegistry);

      runtime.start();
      transport.awaitConnections(1);
      completeHandshake();
      transport.takeMessages(2);

      transport.receive(
          invoke(
              "skill-1",
              EnvironmentCapabilityIds.SKILL_LOAD,
              EnvironmentCapabilityCatalog.VERSION,
              "{\"sourceId\":\""
                  + descriptor.sourceId()
                  + "\",\"name\":\"my-skill\",\"revision\":\""
                  + descriptor.contentRevision()
                  + "\"}"));
      List<DaemonEnvelope> messages = transport.takeMessages(2);
      assertMessageTypes(messages, STARTED, COMPLETED);
      JsonNode result = codec.readPayload(messages.get(1)).path("result");
      assertFalse(result.path("error").asBoolean());
      JsonNode payload = result.path("contents").get(0).path("json");
      assertEquals("# My Skill Body\nInstruction content.", payload.path("body").asText());
      assertEquals(skillDir.toRealPath().toString(), payload.path("baseDirectory").asText());

      // 未知 revision 必须返回 RESOURCE_CHANGED，绝不回退到当前版本。
      transport.receive(
          invoke(
              "skill-2",
              EnvironmentCapabilityIds.SKILL_LOAD,
              EnvironmentCapabilityCatalog.VERSION,
              "{\"sourceId\":\""
                  + descriptor.sourceId()
                  + "\",\"name\":\"my-skill\",\"revision\":\""
                  + "0".repeat(64)
                  + "\"}"));
      List<DaemonEnvelope> missing = transport.takeMessages(2);
      assertMessageTypes(missing, STARTED, COMPLETED);
      EnvironmentCapabilityResult missingResult =
          resultCodec.decodeResult(missing.get(1).payloadJson());
      assertTrue(missingResult.error());
      // 稳定码走结构化 details，可读文本保持固定：调用方据码判定，不解析自由文本。
      assertTrue(
          missingResult.detailsJson().contains(EnvironmentCapabilityResultCodes.RESOURCE_CHANGED));
      assertTrue(
          ((TextResultContent) missingResult.contents().get(0))
              .text()
              .contains("no longer available"));
    } finally {
      deleteRecursively(skillRoot);
    }
  }

  /** Cloud 声明的 capability 版本必须匹配本地 descriptor，避免以错误参数契约启动 capability。 */
  @Test
  void rejectsMismatchedCapabilityVersion() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    InMemoryDaemonInvocationJournal journal = new InMemoryDaemonInvocationJournal();
    runtime = runtime(transport, tool, journal);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invoke("version-mismatch", "test", "2.0.0", 1000));

    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.FAILED);
    assertEquals(0, tool.executions.get());
    assertEquals(
        DaemonInvocationState.FAILED, journal.find("version-mismatch").orElseThrow().state());
  }

  /** 重复 INVOKE 仅重放 STARTED 或终态，不得再次调用本地 capability。 */
  @Test
  void deduplicatesRunningInvocationAndReplaysTerminalAfterReconnect() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    DaemonEnvelope invoke = invoke("invocation-1");
    transport.receive(invoke);
    assertMessageTypes(transport.takeMessages(1), STARTED);
    assertEquals(1, tool.executions.get());

    transport.receive(invoke);
    assertMessageTypes(transport.takeMessages(1), STARTED);
    assertEquals(1, tool.executions.get());

    tool.complete(
        new EnvironmentCapabilityResult(
            "invocation-1", List.of(new TextResultContent("done")), false, "{}"));
    List<DaemonEnvelope> terminal = transport.takeMessages(1);
    assertMessageTypes(terminal, COMPLETED);
    assertTrue(terminal.get(0).payloadJson().contains("done"));
    tool.complete(new EnvironmentCapabilityResult("invocation-1", List.of(), false, "{}"));
    assertFalse(transport.hasMessages());

    transport.disconnect();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invoke("invocation-1"));
    assertMessageTypes(transport.takeMessages(1), COMPLETED);
    assertEquals(1, tool.executions.get());
  }

  /** Runtime 在 deadline 主动 cancel capability，并阻止 timeout 后的完成回调覆盖 FAILED。 */
  @Test
  void enforcesTimeoutAndGuardsLateCompletion() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invoke("timeout", "test", "1.0.0", 30));
    transport.takeMessages(1);

    List<DaemonEnvelope> terminal = transport.takeMessages(1);
    assertMessageTypes(terminal, DaemonMessageType.FAILED);
    assertTrue(terminal.get(0).payloadJson().contains("timed out"));
    assertEquals(1, tool.handle.cancelCalls.get());
    tool.complete(new EnvironmentCapabilityResult("timeout", List.of(), false, "{}"));
    assertFalse(transport.hasMessages());
  }

  /** v4 最大毫秒 timeout 仍必须先发送 STARTED；测试显式 CANCEL 收敛，不能等待不可达的 deadline。 */
  @Test
  void acceptsMaximumWireTimeoutWithoutOverflowingScheduler() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    InMemoryDaemonInvocationJournal journal = new InMemoryDaemonInvocationJournal();
    runtime = runtime(transport, tool, journal);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invoke("maximum-timeout", "test", "1.0.0", Long.MAX_VALUE));

    assertMessageTypes(transport.takeMessages(1), STARTED);
    assertFalse(transport.awaitMessage(Duration.ofMillis(100)));
    assertEquals(
        DaemonInvocationState.RUNNING, journal.find("maximum-timeout").orElseThrow().state());

    transport.receive(cancel("maximum-timeout"));
    assertMessageTypes(transport.takeMessages(1), CANCELLED);
    assertEquals(
        DaemonInvocationState.CANCELLED, journal.find("maximum-timeout").orElseThrow().state());
    assertEquals(1, tool.handle.cancelCalls.get());
  }

  /** Daemon timeout 必须取消排队中的写能力，并在工具尚未开始时保持 workspace 不变。 */
  @Test
  void timeoutCancelsQueuedWriteBeforeMutation() throws Exception {
    Path root = Files.createTempDirectory("daemon-write-timeout");
    ExecutorService taskExecutor = Executors.newSingleThreadExecutor();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    CountDownLatch blockerStarted = new CountDownLatch(1);
    CountDownLatch releaseBlocker = new CountDownLatch(1);
    taskExecutor.execute(
        () -> {
          blockerStarted.countDown();
          try {
            releaseBlocker.await();
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
        });
    try {
      assertTrue(blockerStarted.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
      FakeTransport transport = new FakeTransport();
      DaemonCapabilityRegistry registry = new DaemonCapabilityRegistry();
      registry.register(new WriteCapability(TestCodingConfig.withBridge(root), taskExecutor));
      runtime =
          new DaemonRuntime(
              new DaemonConfig(
                  URI.create("ws://localhost/gateway"),
                  registrationTokenFile(),
                  Duration.ofMinutes(1),
                  Duration.ZERO,
                  Duration.ofSeconds(1),
                  Duration.ofSeconds(10),
                  null,
                  root,
                  dataDir()),
              transport,
              registry,
              DaemonSkillTestSupport.open(dataDir()),
              new InMemoryDaemonInvocationJournal(),
              scheduler,
              taskExecutor);

      handshakeTransport = transport;
      runtime.start();
      transport.awaitConnections(1);
      completeHandshake();
      transport.takeMessages(2);
      transport.receive(
          invoke(
              "write-timeout",
              "fs.write",
              "1",
              100,
              "{\"workdir\":\""
                  + jsonEscape(root.toString())
                  + "\",\"path\":\"timeout.txt\",\"content\":\"must not be written\"}"));

      assertMessageTypes(transport.takeMessages(1), STARTED);
      List<DaemonEnvelope> terminal = transport.takeMessages(1);
      assertMessageTypes(terminal, DaemonMessageType.FAILED);
      assertTrue(terminal.getFirst().payloadJson().contains("timed out"));
      assertFalse(Files.exists(root.resolve("timeout.txt")));
    } finally {
      releaseBlocker.countDown();
      if (runtime != null) {
        runtime.close();
        runtime = null;
      } else {
        scheduler.shutdownNow();
        taskExecutor.shutdownNow();
      }
      deleteRecursively(root);
    }
  }

  /** 缺省 timeoutMillis 表示不覆盖，必须优先使用 Tool descriptor timeout。 */
  @Test
  void omittedTimeoutUsesDescriptorTimeout() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool, Duration.ofMinutes(1), Duration.ofSeconds(30));

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invokeWithoutTimeout("omitted-descriptor-timeout", "test", "1.0.0"));
    transport.takeMessages(1);

    assertEquals(Duration.ofSeconds(10), tool.request.effectiveTimeout());
  }

  /** timeoutMillis 缺省且 descriptor 为 0 时，必须回退 daemon 默认 timeout。 */
  @Test
  void omittedTimeoutFallsBackToDaemonDefault() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    DefaultTimeoutCapability tool = new DefaultTimeoutCapability();
    runtime = runtime(transport, tool, Duration.ofMinutes(1), Duration.ofSeconds(12));

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invokeWithoutTimeout("omitted-default-timeout", "fallback", "1.0.0"));
    transport.takeMessages(1);

    assertEquals(Duration.ofSeconds(12), tool.request.effectiveTimeout());
  }

  /** 0 timeout 使用 descriptor timeout，完成或取消时 deadline 必须被撤销。 */
  @Test
  void resolvesZeroTimeoutAndCancelsDeadlineAfterCompletionOrCancellation()
      throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invoke("zero-timeout", "test", "1.0.0", 0));
    transport.takeMessages(1);
    assertEquals(Duration.ofSeconds(10), tool.request.effectiveTimeout());
    tool.complete(new EnvironmentCapabilityResult("zero-timeout", List.of(), false, "{}"));
    assertMessageTypes(transport.takeMessages(1), COMPLETED);

    transport.receive(invoke("cancel-before-timeout", "test", "1.0.0", 30));
    transport.takeMessages(1);
    transport.receive(cancel("cancel-before-timeout"));
    assertMessageTypes(transport.takeMessages(1), CANCELLED);
    assertFalse(transport.awaitMessage(Duration.ofMillis(80)));
  }

  /** descriptor 也为 0 时必须回退 daemon 默认 timeout，仍不能产生无限执行。 */
  @Test
  void fallsBackToDaemonTimeoutWhenRequestAndDescriptorAreZero() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    DefaultTimeoutCapability tool = new DefaultTimeoutCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invoke("default-timeout", "fallback", "1.0.0", 0));
    transport.takeMessages(1);

    assertEquals(Duration.ofSeconds(10), tool.request.effectiveTimeout());
    tool.complete();
    assertMessageTypes(transport.takeMessages(1), COMPLETED);
  }

  /** complete 抢先终态后 deadline 必须失效且不能 cancel handle。 */
  @Test
  void completionWinsAgainstPendingTimeout() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invoke("complete-before-timeout", "test", "1.0.0", 50));
    transport.takeMessages(1);
    tool.complete(
        new EnvironmentCapabilityResult("complete-before-timeout", List.of(), false, "{}"));

    assertMessageTypes(transport.takeMessages(1), COMPLETED);
    assertFalse(transport.awaitMessage(Duration.ofMillis(100)));
    assertEquals(0, tool.handle.cancelCalls.get());
  }

  /** 流式 PROGRESS 只承载 text/json；resource 内容在 PROGRESS 路径被拒绝并收敛为 FAILED。 */
  @Test
  void partialSerializesTextAndJsonButRejectsResourceContents() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invoke("structured-content"));
    transport.takeMessages(1);
    tool.partial(
        new EnvironmentCapabilityResult(
            "structured-content",
            List.of(new JsonResultContent("[1,2]"), new TextResultContent("hi")),
            false,
            "{}"));

    List<DaemonEnvelope> messages = transport.takeMessages(1);
    assertMessageTypes(messages, PROGRESS);
    String payload = messages.get(0).payloadJson();
    assertTrue(payload.contains("\"type\":\"json\""));
    assertTrue(payload.contains("\"json\":[1,2]"));
    assertTrue(payload.contains("\"type\":\"text\""));
    assertTrue(payload.contains("\"text\":\"hi\""));

    // PROGRESS 携带 resource → 编码在任何上传之前拒绝，收敛为 FAILED，绝不发出控制帧。
    transport.receive(invoke("structured-content-2"));
    transport.takeMessages(1);
    tool.partial(
        new EnvironmentCapabilityResult(
            "structured-content-2",
            List.of(new ResourceResultContent(uploadedRef(UUID.randomUUID(), "application/json"))),
            false,
            "{}"));
    List<DaemonEnvelope> partialFailure = transport.takeMessages(1);
    assertMessageTypes(partialFailure, DaemonMessageType.FAILED);
    assertTrue(partialFailure.get(0).payloadJson().contains("cannot partial"));
    assertFalse(transport.hasMessages(), "PROGRESS 携带 resource 时绝不能发出任何上传控制帧");
  }

  /**
   * Capability 重构不可退化的 fitness gate：daemon wire 必须按序发送两个 PROGRESS，再发送唯一 COMPLETED，并保留
   * invocation/call id。
   */
  @Test
  void streamsMultiplePartialsBeforeCompletionOnWire() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);
    String invocationId = "streaming-invocation";

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invoke(invocationId));
    tool.partial(
        new EnvironmentCapabilityResult(
            invocationId, List.of(new TextResultContent("partial1")), false, "{}"));
    tool.partial(
        new EnvironmentCapabilityResult(
            invocationId, List.of(new TextResultContent("partial2")), false, "{}"));
    tool.complete(
        new EnvironmentCapabilityResult(
            invocationId, List.of(new TextResultContent("complete")), false, "{}"));

    List<DaemonEnvelope> messages = transport.takeMessages(4);
    assertMessageTypes(messages, STARTED, PROGRESS, PROGRESS, COMPLETED);
    for (DaemonEnvelope message : messages) {
      assertEquals(invocationId, message.invocationId());
    }
    assertTrue(codec.readPayload(messages.get(0)).isEmpty());

    List<EnvironmentCapabilityResult> results =
        messages.subList(1, 4).stream()
            .map(message -> resultCodec.decodeResult(message.payloadJson()))
            .toList();
    assertEquals(
        List.of(invocationId, invocationId, invocationId),
        results.stream().map(EnvironmentCapabilityResult::callId).toList());
    assertEquals(
        List.of("partial1", "partial2", "complete"),
        results.stream()
            .map(result -> ((TextResultContent) result.contents().get(0)).text())
            .toList());
  }

  /**
   * 二进制/图片结果的完整数据面：REQUEST → 票据 → 直传 PUT → COMMIT → READY → 仅元数据 COMPLETED。
   *
   * <p>同时证明 wire 上没有任何字节表示（既非 Base64 也非二进制帧），且终端 resource 只承载全局 uploadId 与权威元数据。
   */
  @Test
  void binaryResultUploadsDirectlyAndEncodesMetadataOnly() throws Exception {
    try (UploadEndpoint storage = UploadEndpoint.start()) {
      FakeTransport transport = new FakeTransport();
      TestCapability tool = new TestCapability();
      byte[] data = new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
      runtime = runtime(transport, tool);

      runtime.start();
      transport.awaitConnections(1);
      completeHandshake();
      transport.takeMessages(2);
      // 最大 wire timeout 等价于实际上无 deadline；不能溢出 OkHttp 的 callTimeout 参数。
      transport.receive(invoke("binary-upload", "test", "1.0.0", Long.MAX_VALUE));
      assertMessageTypes(transport.takeMessages(1), STARTED);
      Thread completion =
          completeAsync(
              tool,
              new EnvironmentCapabilityResult(
                  "binary-upload",
                  List.of(new BinaryResultContent("application/octet-stream", data)),
                  false,
                  "{}"));

      // daemon 必须先申请票据：控制帧只承载 transfer 元数据，绝无二进制数据面。
      DaemonEnvelope requestFrame = transport.takeNextMessage();
      assertMessageTypes(List.of(requestFrame), DaemonMessageType.RESOURCE_UPLOAD_REQUEST);
      assertEquals("binary-upload", requestFrame.invocationId());
      DaemonResourceTransferCodec.UploadRequest request =
          transferCodec.decodeRequest(requestFrame.payloadJson());
      assertEquals("application/octet-stream", request.mediaType());
      assertEquals(data.length, request.size());
      assertEquals(storage.sha256Hex(data), request.sha256());

      // 服务端签发 PENDING 票据：daemon 按精确方法/headers 直传，然后提交。
      UUID uploadId = UUID.randomUUID();
      storage.expectUpload(request.sha256());
      transport.receive(
          ticketEnvelope(
              "binary-upload",
              transferCodec.encodeTicket(
                  DaemonResourceTransferCodec.UploadTicket.pending(
                      request.transferId(),
                      uploadId,
                      new DaemonPresignedPut(
                          "PUT", storage.putUrl(), Map.of("If-None-Match", "*"))))));

      DaemonEnvelope commitFrame = transport.takeNextMessage();
      assertMessageTypes(List.of(commitFrame), DaemonMessageType.RESOURCE_UPLOAD_COMMIT);
      assertEquals("binary-upload", commitFrame.invocationId());
      DaemonResourceTransferCodec.UploadCommit commit =
          transferCodec.decodeCommit(commitFrame.payloadJson());
      assertEquals(request.transferId(), commit.transferId());
      assertEquals(uploadId, commit.uploadId());
      assertEquals("PUT", storage.lastMethod());
      assertEquals(data.length, storage.lastBody().length);
      assertArrayEquals(data, storage.lastBody());

      // 提交回执 READY 后 daemon 才能发出唯一 COMPLETED，且只承载元数据。
      transport.receive(
          ticketEnvelope(
              "binary-upload",
              transferCodec.encodeTicket(
                  DaemonResourceTransferCodec.UploadTicket.ready(request.transferId(), uploadId))));

      DaemonEnvelope terminal = transport.takeNextMessage();
      assertMessageTypes(List.of(terminal), COMPLETED);
      JsonNode content = codec.readPayload(terminal).get("result").get("contents").get(0);
      assertEquals("resource", content.get("type").asText());
      assertEquals(uploadId.toString(), content.get("uploadId").asText());
      assertEquals("application/octet-stream", content.get("mediaType").asText());
      assertEquals(data.length, content.get("size").asLong());
      assertEquals(storage.sha256Hex(data), content.get("sha256").asText());
      // 终态绝不承载本地地址或任何字节表示。
      assertFalse(content.has("uri"));
      assertFalse(terminal.payloadJson().contains("file://"));
      assertEquals(1, storage.uploadCount());

      // 接收端把 uploadId 还原为进程内瞬态 blob-upload 引用，而不是内存字节。
      EnvironmentCapabilityResult decoded = resultCodec.decodeResult(terminal.payloadJson());
      ResourceResultContent uploaded = (ResourceResultContent) decoded.contents().get(0);
      assertEquals(uploadId, uploaded.resource().blobUploadId());
      assertEquals(ResourceRef.blobUploadUri(uploadId), uploaded.resource().uri());

      awaitCompletion(completion);
    }
  }

  /** 票据等待期间断线后，同实例重连必须重放同一 transferId，且能力只执行一次、字节只上传一次。 */
  @Test
  void inFlightUploadResumesWithTheSameTransferAfterReconnect() throws Exception {
    try (UploadEndpoint storage = UploadEndpoint.start()) {
      FakeTransport transport = new FakeTransport();
      TestCapability tool = new TestCapability();
      byte[] data = new byte[] {4, 5, 6};
      runtime = runtime(transport, tool);

      runtime.start();
      transport.awaitConnections(1);
      completeHandshake();
      transport.takeMessages(2);
      transport.receive(invoke("upload-reconnect", "test", "1.0.0", 10_000));
      assertMessageTypes(transport.takeMessages(1), STARTED);
      Thread completion =
          completeAsync(
              tool,
              new EnvironmentCapabilityResult(
                  "upload-reconnect",
                  List.of(new BinaryResultContent("application/octet-stream", data)),
                  false,
                  "{}"));

      DaemonEnvelope firstRequestFrame = transport.takeNextMessage();
      DaemonResourceTransferCodec.UploadRequest firstRequest =
          transferCodec.decodeRequest(firstRequestFrame.payloadJson());
      transport.disconnect();

      transport.awaitConnections(1);
      completeHandshake();
      assertMessageTypes(transport.takeMessages(2), HELLO, READY);
      // Server 在 READY 后以同一 invocationId 重放 INVOKE；Daemon journal 只回放 STARTED，不重复执行能力。
      transport.receive(invoke("upload-reconnect", "test", "1.0.0", 10_000));
      List<DaemonEnvelope> resumed = transport.takeMessages(2);
      DaemonEnvelope retriedRequestFrame =
          resumed.stream()
              .filter(frame -> frame.messageType() == DaemonMessageType.RESOURCE_UPLOAD_REQUEST)
              .findFirst()
              .orElseThrow();
      assertTrue(
          resumed.stream().anyMatch(frame -> frame.messageType() == DaemonMessageType.STARTED));
      DaemonResourceTransferCodec.UploadRequest retriedRequest =
          transferCodec.decodeRequest(retriedRequestFrame.payloadJson());
      assertEquals(firstRequest, retriedRequest);
      assertEquals(1, tool.executions.get());

      UUID uploadId = UUID.randomUUID();
      storage.expectUpload(retriedRequest.sha256());
      transport.receive(
          ticketEnvelope(
              "upload-reconnect",
              transferCodec.encodeTicket(
                  DaemonResourceTransferCodec.UploadTicket.pending(
                      retriedRequest.transferId(),
                      uploadId,
                      new DaemonPresignedPut(
                          "PUT", storage.putUrl(), Map.of("If-None-Match", "*"))))));
      DaemonEnvelope commitFrame = transport.takeNextMessage();
      assertEquals(DaemonMessageType.RESOURCE_UPLOAD_COMMIT, commitFrame.messageType());
      transport.receive(
          ticketEnvelope(
              "upload-reconnect",
              transferCodec.encodeTicket(
                  DaemonResourceTransferCodec.UploadTicket.ready(
                      retriedRequest.transferId(), uploadId))));
      assertMessageTypes(transport.takeMessages(1), COMPLETED);
      assertEquals(1, storage.uploadCount());
      awaitCompletion(completion);
    }
  }

  /** 同实例重连后终态重放：journal 直接重放已就绪的终态，绝不再次上传或重新执行能力。 */
  @Test
  void terminalReplayAfterReconnectDoesNotUploadAgain() throws Exception {
    try (UploadEndpoint storage = UploadEndpoint.start()) {
      FakeTransport transport = new FakeTransport();
      TestCapability tool = new TestCapability();
      byte[] data = new byte[] {7, 8, 9};
      runtime = runtime(transport, tool);

      runtime.start();
      transport.awaitConnections(1);
      completeHandshake();
      transport.takeMessages(2);
      transport.receive(invoke("replayed-upload"));
      transport.takeMessages(1);
      Thread completion =
          completeAsync(
              tool,
              new EnvironmentCapabilityResult(
                  "replayed-upload",
                  List.of(new BinaryResultContent("application/octet-stream", data)),
                  false,
                  "{}"));

      DaemonEnvelope requestFrame = transport.takeNextMessage();
      DaemonResourceTransferCodec.UploadRequest request =
          transferCodec.decodeRequest(requestFrame.payloadJson());
      UUID uploadId = UUID.randomUUID();
      storage.expectUpload(request.sha256());
      transport.receive(
          ticketEnvelope(
              "replayed-upload",
              transferCodec.encodeTicket(
                  DaemonResourceTransferCodec.UploadTicket.pending(
                      request.transferId(),
                      uploadId,
                      new DaemonPresignedPut(
                          "PUT", storage.putUrl(), Map.of("If-None-Match", "*"))))));
      transport.takeNextMessage();
      transport.receive(
          ticketEnvelope(
              "replayed-upload",
              transferCodec.encodeTicket(
                  DaemonResourceTransferCodec.UploadTicket.ready(request.transferId(), uploadId))));
      DaemonEnvelope firstTerminal = transport.takeNextMessage();
      assertMessageTypes(List.of(firstTerminal), COMPLETED);
      assertEquals(1, storage.uploadCount());

      // 物理断线后同实例重连：同一 invocationId 重放终态，不再上传也不再执行。
      transport.disconnect();
      transport.awaitConnections(1);
      String replayedTerminal = null;
      transport.awaitNextMessageType(HELLO);
      transport.takeMessages(1);
      transport.receive(platformMessage(DaemonMessageType.WELCOME));
      transport.takeMessages(1);
      transport.receive(invoke("replayed-upload"));
      for (DaemonEnvelope envelope : transport.takeMessages(1)) {
        assertMessageTypes(List.of(envelope), COMPLETED);
        replayedTerminal = envelope.payloadJson();
      }

      assertEquals(firstTerminal.payloadJson(), replayedTerminal);
      assertEquals(1, storage.uploadCount(), "终态重放绝不重复上传字节");
      assertEquals(1, tool.executions.get(), "终态重放绝不重复执行能力");
      awaitCompletion(completion);
    }
  }

  /**
   * 服务端持续拒绝签发票据时 daemon 必须在 invocation deadline 有界收敛为 FAILED，且不泄漏预签名地址、不内联任何字节。
   *
   * <p>同时证明这是调用级故障：同一连接上的下一个调用仍能正常完成。终态与资源上传控制帧的出站顺序是协议级不变量：完成回调收敛后，本调用绝不能再吐出任何 {@code
   * RESOURCE_UPLOAD_REQUEST}/{@code COMMIT}，否则服务端会把迟到控制帧判定为协议违规并关闭本可复用的连接。
   */
  @Test
  void convergesToFailedTerminalWhenTicketRejectionsOutlastDeadline() throws Exception {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invoke("rejected-upload", "test", "1.0.0", 2_000));
    transport.takeMessages(1);
    Thread completion =
        completeAsync(
            tool,
            new EnvironmentCapabilityResult(
                "rejected-upload",
                List.of(new BinaryResultContent("image/png", new byte[] {1})),
                false,
                "{}"));

    // 服务端持续拒绝时，同一 transfer 在 invocation deadline 内重试，超时后确定性收敛。
    int attempts = 0;
    DaemonEnvelope terminal = null;
    while (terminal == null) {
      DaemonEnvelope frame = transport.takeNextMessage();
      if (frame.messageType() == DaemonMessageType.RESOURCE_UPLOAD_REQUEST) {
        attempts++;
        DaemonResourceTransferCodec.UploadRequest request =
            transferCodec.decodeRequest(frame.payloadJson());
        transport.receive(
            ticketEnvelope(
                "rejected-upload",
                transferCodec.encodeTicket(
                    DaemonResourceTransferCodec.UploadTicket.failed(
                        request.transferId(), "storage rejected the transfer"))));
        continue;
      }
      terminal = frame;
    }

    assertEquals(DaemonMessageType.FAILED, terminal.messageType());
    assertFalse(terminal.payloadJson().contains("http://"));
    assertFalse(terminal.payloadJson().contains("storage rejected the transfer"));
    assertTrue(attempts > 1, "deadline 到达前应重试同一 transfer");
    awaitCompletion(completion);
    // 完成回调收敛后，终态之前（或期间）发出的控制帧必然已经排在终态之前；队列非空即证明存在越过终态的迟到控制帧。
    assertFalse(
        transport.hasMessages(), "FAILED 终态后不得再发出 RESOURCE_UPLOAD_REQUEST/RESOURCE_UPLOAD_COMMIT");

    // 上传失败只终结该调用：同一连接上的后续调用仍然可用。
    transport.receive(invoke("after-rejection"));
    assertMessageTypes(transport.takeMessages(1), STARTED);
    tool.complete(new EnvironmentCapabilityResult("after-rejection", List.of(), false, "{}"));
    assertMessageTypes(transport.takeMessages(1), COMPLETED);
    assertFalse(transport.hasMessages(), "调用完成后不得残留任何报文");
  }

  /** 对象存储无响应时，PUT 总调用必须服从 invocation deadline，不能因 read/write timeout 为零而永久占住执行线程。 */
  @Test
  void directUploadPutCannotOutliveInvocationDeadline() throws Exception {
    try (UploadEndpoint storage = UploadEndpoint.start()) {
      FakeTransport transport = new FakeTransport();
      TestCapability tool = new TestCapability();
      byte[] data = new byte[] {1, 2, 3};
      storage.delayResponse(5_000);
      runtime = runtime(transport, tool);

      runtime.start();
      transport.awaitConnections(1);
      completeHandshake();
      transport.takeMessages(2);
      transport.receive(invoke("put-deadline", "test", "1.0.0", 500));
      transport.takeMessages(1);
      long startedNanos = System.nanoTime();
      Thread completion =
          completeAsync(
              tool,
              new EnvironmentCapabilityResult(
                  "put-deadline",
                  List.of(new BinaryResultContent("application/octet-stream", data)),
                  false,
                  "{}"));

      DaemonEnvelope requestFrame = transport.takeNextMessage();
      DaemonResourceTransferCodec.UploadRequest request =
          transferCodec.decodeRequest(requestFrame.payloadJson());
      storage.expectUpload(request.sha256());
      transport.receive(
          ticketEnvelope(
              "put-deadline",
              transferCodec.encodeTicket(
                  DaemonResourceTransferCodec.UploadTicket.pending(
                      request.transferId(),
                      UUID.randomUUID(),
                      new DaemonPresignedPut(
                          "PUT", storage.putUrl(), Map.of("If-None-Match", "*"))))));

      DaemonEnvelope terminal = transport.takeNextMessage();
      assertEquals(DaemonMessageType.FAILED, terminal.messageType());
      assertFalse(terminal.payloadJson().contains(storage.putUrl()));
      awaitCompletion(completion);
      assertTrue(
          System.nanoTime() - startedNanos < TimeUnit.SECONDS.toNanos(3),
          "direct PUT must converge within the invocation deadline budget");
    }
  }

  /** Tool error 和错误关联 ID 的完成回调都必须收敛为 FAILED。 */
  @Test
  void convertsToolErrorsAndMismatchedResultsToFailedTerminal() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invoke("tool-error"));
    transport.takeMessages(1);
    tool.error(new IllegalStateException("tool failed"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.FAILED);

    transport.receive(invoke("wrong-result"));
    transport.takeMessages(1);
    tool.complete(new EnvironmentCapabilityResult("another-id", List.of(), false, "{}"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.FAILED);
  }

  /** null callback error 也必须生成唯一 FAILED 终态；迟到 complete 不能再次写 journal 或发送终态。 */
  @Test
  void nullCapabilityErrorConvergesToSingleFailedTerminal() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    InMemoryDaemonInvocationJournal journal = new InMemoryDaemonInvocationJournal();
    runtime = runtime(transport, tool, journal);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invoke("null-error"));
    assertMessageTypes(transport.takeMessages(1), STARTED);

    tool.error(null);
    List<DaemonEnvelope> terminal = transport.takeMessages(1);
    assertMessageTypes(terminal, DaemonMessageType.FAILED);
    assertEquals("{\"message\":\"capability execution failed\"}", terminal.get(0).payloadJson());
    assertEquals(DaemonInvocationState.FAILED, journal.find("null-error").orElseThrow().state());

    tool.complete(new EnvironmentCapabilityResult("null-error", List.of(), false, "{}"));
    assertFalse(transport.hasMessages());
    assertEquals(DaemonInvocationState.FAILED, journal.find("null-error").orElseThrow().state());
  }

  /** 恶意 Throwable 的 message 不能逃逸终态处理；同时覆盖结果编码失败并验证 Runtime 仍可接收后续 invocation。 */
  @Test
  void guardsAdversarialFailureMessagesAndKeepsRuntimeUsable() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    InMemoryDaemonInvocationJournal journal = new InMemoryDaemonInvocationJournal();
    runtime = runtime(transport, tool, journal);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invoke("adversarial-error"));
    assertMessageTypes(transport.takeMessages(1), STARTED);

    tool.error(new ExplodingMessageException());
    List<DaemonEnvelope> errorTerminal = transport.takeMessages(1);
    assertMessageTypes(errorTerminal, DaemonMessageType.FAILED);
    assertEquals(
        "{\"message\":\"capability execution failed\"}", errorTerminal.get(0).payloadJson());
    assertEquals(
        DaemonInvocationState.FAILED, journal.find("adversarial-error").orElseThrow().state());

    tool.complete(new EnvironmentCapabilityResult("adversarial-error", List.of(), false, "{}"));
    assertFalse(transport.hasMessages());

    transport.receive(invoke("adversarial-result"));
    assertMessageTypes(transport.takeMessages(1), STARTED);
    // 无法解析的 resource 引用（非 blob-upload scheme）在终态编码前被拒绝，绝不落到 wire 或存储。
    tool.complete(
        new EnvironmentCapabilityResult(
            "adversarial-result",
            List.of(
                new ResourceResultContent(
                    new ResourceRef(
                        "file:///export/adversarial",
                        "application/octet-stream",
                        null,
                        1L,
                        "0".repeat(64)))),
            false,
            "{}"));
    List<DaemonEnvelope> resultTerminal = transport.takeMessages(1);
    assertMessageTypes(resultTerminal, DaemonMessageType.FAILED);
    assertTrue(resultTerminal.get(0).payloadJson().contains("cannot complete"));
    assertFalse(resultTerminal.get(0).payloadJson().contains("adversarial"));
    assertEquals(
        DaemonInvocationState.FAILED, journal.find("adversarial-result").orElseThrow().state());

    transport.receive(invoke("runtime-still-usable"));
    assertMessageTypes(transport.takeMessages(1), STARTED);
    tool.complete(new EnvironmentCapabilityResult("runtime-still-usable", List.of(), false, "{}"));
    assertMessageTypes(transport.takeMessages(1), COMPLETED);
    assertEquals(
        DaemonInvocationState.COMPLETED,
        journal.find("runtime-still-usable").orElseThrow().state());
  }

  /** PROGRESS 必须流式转发，CANCEL 后迟到 complete callback 不能覆盖 CANCELLED 终态。 */
  @Test
  void forwardsPartialAndGuardsCancelledInvocationAgainstLateCallbacks()
      throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invoke("invocation-2"));
    transport.takeMessages(1);

    tool.partial(
        new EnvironmentCapabilityResult(
            "invocation-2", List.of(new TextResultContent("chunk")), false, "{}"));
    List<DaemonEnvelope> partial = transport.takeMessages(1);
    assertMessageTypes(partial, PROGRESS);
    assertTrue(partial.get(0).payloadJson().contains("chunk"));

    transport.receive(cancel("invocation-2"));
    assertMessageTypes(transport.takeMessages(1), CANCELLED);
    assertEquals(1, tool.handle.cancelCalls.get());

    tool.complete(
        new EnvironmentCapabilityResult(
            "invocation-2", List.of(new TextResultContent("late")), false, "{}"));
    assertFalse(transport.hasMessages());
  }

  /** READY 能力对象只携带 environment 与按来源分组的 skill 摘要，且不含正文。 */
  @Test
  void announcesSkillsInCapabilities() throws Exception {
    Path skillRoot = Files.createTempDirectory("daemon-skills");
    Path skillDir = skillRoot.resolve("demo");
    Files.createDirectories(skillDir);
    String body = "---\nname: demo\ndescription: Demo skill\n---\n# Demo\n";
    Files.writeString(skillDir.resolve("SKILL.md"), body);
    try {
      FakeTransport transport = new FakeTransport();
      DaemonSkillRegistry skills =
          DaemonSkillTestSupport.publish(dataDir(), skillRoot, 1).registry();
      runtime =
          runtime(
              transport,
              new TestCapability(),
              skills,
              Duration.ofMinutes(1),
              Duration.ofSeconds(10));

      runtime.start();
      transport.awaitConnections(1);
      completeHandshake();
      List<DaemonEnvelope> handshake = transport.takeMessages(2);
      assertMessageTypes(handshake, HELLO, READY);

      JsonNode payload = codec.readPayload(handshake.get(1));
      assertFalse(payload.has("tools"));
      assertEquals(DaemonCapabilities.VERSION, payload.path("version").asInt());
      // 顶层集合版本必须出现在 environment 与 skillSources 之间，缺省的旧形状不允许。
      assertEquals(1L, payload.path("sourceSetVersion").asLong());
      assertTrue(payload.path("environment").path("workingDirectory").isMissingNode());
      assertTrue(payload.path("environment").path("note").isTextual());
      assertTrue(payload.path("environment").path("rootPath").isTextual());
      assertEquals(1, payload.path("skillSources").size());
      JsonNode source = payload.path("skillSources").get(0);
      assertEquals(DaemonSkillTestSupport.SOURCE_ID.toString(), source.path("sourceId").asText());
      assertEquals(1, source.path("sourceVersion").asLong());
      assertFalse(source.path("sourceRevision").asText().isBlank());
      assertEquals(1, source.path("skills").size());
      JsonNode skill = source.path("skills").get(0);
      assertEquals("demo", skill.path("name").asText());
      assertEquals("Demo skill", skill.path("description").asText());
      assertEquals(skillDir.toRealPath().toString(), skill.path("baseDirectory").asText());
      assertEquals(64, skill.path("contentRevision").asText().length());
      // READY 只上报身份与描述，正文与旧字段绝不出现。
      assertTrue(skill.path("body").isMissingNode());
      assertTrue(skill.path("path").isMissingNode());
      assertTrue(skill.path("content").isMissingNode());
      assertFalse(payload.has("skills"));
      assertFalse(payload.has("mcpServers"));
    } finally {
      deleteRecursively(skillRoot);
    }
  }

  /** transport 同步抛错和异步返回空连接都必须收敛为下一次重连。 */
  @Test
  void reconnectsWhenTransportThrowsOrCompletesWithNullConnection() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    transport.throwNextConnection();
    transport.returnNullNextConnection();
    runtime = runtime(transport, new TestCapability());

    runtime.start();

    transport.awaitConnections(3);
    completeHandshake();
    assertMessageTypes(transport.takeMessages(2), HELLO, READY);
    assertEquals(DaemonRuntimeState.READY, runtime.state());
  }

  /** close 先于异步 connect 完成时，迟到连接必须立即关闭而不能重新激活 runtime。 */
  @Test
  void closesConnectionThatCompletesAfterRuntimeShutdown() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    transport.delayNextConnection();
    runtime = runtime(transport, new TestCapability());

    runtime.start();
    transport.awaitConnections(1);
    runtime.close();
    transport.completeDelayedConnection();

    assertTrue(transport.connection.awaitClosed(Duration.ofSeconds(ASYNC_TEST_TIMEOUT_SECONDS)));
    assertFalse(transport.connection.isOpen());
    assertFalse(transport.hasMessages());
  }

  /** 上一代连接迟到的消息不得进入当前连接的 invocation 生命周期。 */
  @Test
  void ignoresLateMessageFromSupersededConnection() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.disconnect();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);

    transport.receiveFromConnection(0, invoke("stale-invocation"));
    assertFalse(transport.hasMessages());
    assertEquals(0, tool.executions.get());

    transport.receive(invoke("current-invocation"));
    assertMessageTypes(transport.takeMessages(1), STARTED);
    assertEquals(1, tool.executions.get());
  }

  /** 上一代连接的 CANCEL 不得影响 replacement connection 上的 invocation 生命周期。 */
  @Test
  void doesNotRouteStaleCancelToReplacementConnection() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestCapability());

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.disconnect();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);

    transport.receiveFromConnection(0, cancel("stale-cancel"));
    assertFalse(transport.hasMessages());

    transport.receive(cancel("current-cancel"));
    assertFalse(transport.hasMessages());
  }

  /** stale INVOKE 响应及其协议 ERROR 均不得被发送到 replacement connection。 */
  @Test
  void doesNotRouteStaleInvokeOrMalformedInputResponsesToReplacementConnection()
      throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestCapability(), DaemonSkillTestSupport.open(dataDir()));

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.disconnect();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);

    transport.receiveFromConnection(0, invoke("stale-invoke"));
    transport.receiveRawFromConnection(
        0,
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"INVOKE\","
            + "\"environmentId\":\"11111111-1111-1111-1111-111111111111\",\"payload\":{}}");
    assertFalse(transport.hasMessages());

    transport.receive(invoke("current-invoke"));
    assertMessageTypes(transport.takeMessages(1), STARTED);
  }

  /** 被提前关闭的 scheduler 不能让 runtime 停在 started=true 但永远不会连接的半启动状态。 */
  @Test
  void remainsStoppedWhenSchedulerRejectsStartup() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    scheduler.shutdownNow();
    runtime = runtime(transport, new TestCapability(), scheduler);

    runtime.start();

    assertEquals(DaemonRuntimeState.STOPPED, runtime.state());
    assertFalse(transport.awaitConnection(Duration.ofMillis(100)));
    runtime.close();
    assertTrue(transport.closed.get());
  }

  /** heartbeat 已创建但 reconnect 被拒绝时，start 必须撤销 heartbeat 并恢复 STOPPED。 */
  @Test
  void remainsStoppedWhenSchedulerRejectsInitialReconnect() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    ScheduledExecutorService scheduler = new ReconnectRejectingScheduler();
    runtime = runtime(transport, new TestCapability(), scheduler);

    runtime.start();

    assertEquals(DaemonRuntimeState.STOPPED, runtime.state());
    assertFalse(transport.awaitConnection(Duration.ofMillis(100)));
    runtime.close();
    assertTrue(transport.closed.get());
  }

  /** 未预期 platform 消息、未知取消和非法 invoke payload 都必须只产生协议级响应。 */
  @Test
  void isolatesUnexpectedAndMalformedProtocolMessagesFromInvocationExecution()
      throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);

    transport.receive(
        new DaemonEnvelope(DaemonProtocol.VERSION, DaemonMessageType.HELLO, null, null, "{}"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);

    transport.receive(cancel("unknown-cancel"));
    assertFalse(transport.hasMessages());

    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION,
            DaemonMessageType.INVOKE,
            ENVIRONMENT_ID,
            "invalid-invoke-payload",
            "{}"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    assertEquals(0, tool.executions.get());
  }

  /** close 必须取消运行中的 Tool、记录可重放 CANCELLED，并成为不可重启的终态。 */
  @Test
  void closeCancelsRunningInvocationAndPreventsRestart() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    InMemoryDaemonInvocationJournal journal = new InMemoryDaemonInvocationJournal();
    runtime = runtime(transport, tool, journal);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    transport.receive(invoke("shutdown-invocation"));
    assertMessageTypes(transport.takeMessages(1), STARTED);

    runtime.close();

    assertTrue(transport.closed.get());
    assertEquals(1, tool.handle.cancelCalls.get());
    assertEquals(
        DaemonInvocationState.CANCELLED, journal.find("shutdown-invocation").orElseThrow().state());
    tool.complete(
        new EnvironmentCapabilityResult(
            "shutdown-invocation", List.of(new TextResultContent("late")), false, "{}"));
    assertFalse(transport.hasMessages());

    runtime.start();
    assertFalse(transport.awaitConnection(Duration.ofMillis(100)));
  }

  /** 未启动的 runtime 仍必须释放其 transport 和 scheduler，且 close 后不能重新启动。 */
  @Test
  void closesUnstartedRuntimeWithoutAllowingLaterStart() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestCapability());

    runtime.close();

    assertEquals(DaemonRuntimeState.STOPPED, runtime.state());
    assertTrue(transport.closed.get());
    runtime.start();
    assertFalse(transport.awaitConnection(Duration.ofMillis(100)));
  }

  /** close 的两个执行资源必须恰好归 runtime 所有：重复关闭后均 shutdownNow 且在有界时间内终止。 */
  @Test
  void closeTerminatesSchedulerAndSharedTaskExecutorIdempotently() throws Exception {
    FakeTransport transport = new FakeTransport();
    DaemonCapabilityRegistry registry = new DaemonCapabilityRegistry();
    registry.register(new TestCapability());
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    ExecutorService taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
    runtime =
        new DaemonRuntime(
            new DaemonConfig(
                URI.create("ws://localhost/gateway"),
                registrationTokenFile(),
                Duration.ofMinutes(1),
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                null,
                ENVIRONMENT_ROOT,
                dataDir()),
            transport,
            registry,
            DaemonSkillTestSupport.open(dataDir()),
            new InMemoryDaemonInvocationJournal(),
            scheduler,
            taskExecutor);

    runtime.close();
    runtime.close();

    assertTrue(scheduler.isShutdown());
    assertTrue(taskExecutor.isShutdown());
    assertTrue(scheduler.awaitTermination(1, TimeUnit.SECONDS));
    assertTrue(taskExecutor.awaitTermination(1, TimeUnit.SECONDS));
    assertEquals(DaemonRuntimeState.STOPPED, runtime.awaitTermination());
  }

  /** 阻塞 executor 被占满时，独立 scheduler 仍必须发送 heartbeat，证明阻塞 Tool/IO 不会饿死连接保活。 */
  @Test
  void blockingTaskExecutorDoesNotDelayHeartbeatScheduler() throws Exception {
    FakeTransport transport = new FakeTransport();
    handshakeTransport = transport;
    DaemonCapabilityRegistry registry = new DaemonCapabilityRegistry();
    registry.register(new TestCapability());
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    ExecutorService taskExecutor = Executors.newSingleThreadExecutor();
    CountDownLatch release = new CountDownLatch(1);
    taskExecutor.execute(
        () -> {
          try {
            release.await();
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
        });
    runtime =
        new DaemonRuntime(
            new DaemonConfig(
                URI.create("ws://localhost/gateway"),
                registrationTokenFile(),
                Duration.ofMillis(20),
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                null,
                ENVIRONMENT_ROOT,
                dataDir()),
            transport,
            registry,
            DaemonSkillTestSupport.open(dataDir()),
            new InMemoryDaemonInvocationJournal(),
            scheduler,
            taskExecutor);

    try {
      runtime.start();
      transport.awaitConnections(1);
      completeHandshake();
      transport.takeMessages(2);

      transport.awaitNextMessageType(DaemonMessageType.HEARTBEAT);
    } finally {
      release.countDown();
    }
  }

  /** transport close 抛错不得悬挂 shutdown：终止闩释放且 close 幂等。 */
  @Test
  void shutdownConvergesWhenTransportCloseThrows() throws Exception {
    FakeTransport transport = new FakeTransport();
    transport.closeThrows.set(true);
    runtime =
        runtime(
            transport,
            new TestCapability(),
            DaemonSkillTestSupport.open(dataDir()),
            Duration.ofMinutes(1),
            Duration.ofSeconds(10));

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);

    runtime.close();
    runtime.close();

    // transport close 抛错被吸收：termination 闩仍释放，shutdown 不悬挂。
    assertEquals(DaemonRuntimeState.STOPPED, runtime.awaitTermination());
    assertTrue(transport.closed.get());
  }

  /** shutdown 与 Tool 启动交错时，迟到的 execution handle 也必须收到取消且 journal 不得遗留 RUNNING。 */
  @Test
  void closesInvocationThatCompletesToolStartupAfterShutdown() throws Exception {
    FakeTransport transport = new FakeTransport();
    BlockingCapability tool = new BlockingCapability();
    InMemoryDaemonInvocationJournal journal = new InMemoryDaemonInvocationJournal();
    runtime = runtime(transport, tool, journal);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake();
    transport.takeMessages(2);
    Thread invocationThread =
        new Thread(
            () -> transport.receive(invoke("shutdown-race", "blocking")), "invoke-shutdown-race");
    invocationThread.start();
    assertTrue(tool.executionStarted.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    assertMessageTypes(transport.takeMessages(1), STARTED);

    runtime.close();
    tool.allowReturn.countDown();
    invocationThread.join(TimeUnit.SECONDS.toMillis(ASYNC_TEST_TIMEOUT_SECONDS));

    assertFalse(invocationThread.isAlive());
    assertEquals(1, tool.handle.cancelCalls.get());
    assertEquals(
        DaemonInvocationState.CANCELLED, journal.find("shutdown-race").orElseThrow().state());
    assertFalse(transport.hasMessages());
  }

  private DaemonRuntime runtime(FakeTransport transport, EnvironmentCapability capability) {
    return runtime(transport, capability, Duration.ofMinutes(1));
  }

  private DaemonRuntime runtime(
      FakeTransport transport, EnvironmentCapability capability, Duration heartbeatInterval) {
    return runtime(transport, capability, heartbeatInterval, Duration.ofSeconds(10));
  }

  private DaemonRuntime runtime(
      FakeTransport transport, EnvironmentCapability capability, String note) {
    handshakeTransport = transport;
    DaemonCapabilityRegistry registry = new DaemonCapabilityRegistry();
    registry.register(capability);
    return new DaemonRuntime(
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            registrationTokenFile(),
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            note,
            ENVIRONMENT_ROOT,
            dataDir()),
        transport,
        registry,
        DaemonSkillTestSupport.open(dataDir()),
        new InMemoryDaemonInvocationJournal(),
        Executors.newSingleThreadScheduledExecutor(),
        Executors.newVirtualThreadPerTaskExecutor());
  }

  private DaemonRuntime runtime(
      FakeTransport transport, EnvironmentCapability capability, Path environmentRoot) {
    handshakeTransport = transport;
    DaemonCapabilityRegistry registry = new DaemonCapabilityRegistry();
    registry.register(capability);
    return new DaemonRuntime(
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            registrationTokenFile(),
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            null,
            environmentRoot,
            dataDir()),
        transport,
        registry,
        DaemonSkillTestSupport.open(dataDir()),
        new InMemoryDaemonInvocationJournal(),
        Executors.newSingleThreadScheduledExecutor(),
        Executors.newVirtualThreadPerTaskExecutor());
  }

  private DaemonRuntime runtime(
      FakeTransport transport, DaemonCapabilityRegistry registry, Path environmentRoot) {
    handshakeTransport = transport;
    return new DaemonRuntime(
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            registrationTokenFile(),
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            null,
            environmentRoot,
            dataDir()),
        transport,
        registry,
        DaemonSkillTestSupport.open(dataDir()),
        new InMemoryDaemonInvocationJournal(),
        Executors.newSingleThreadScheduledExecutor(),
        Executors.newVirtualThreadPerTaskExecutor());
  }

  private DaemonRuntime runtime(
      FakeTransport transport,
      DaemonCapabilityRegistry registry,
      DaemonSkillRegistry skillRegistry) {
    handshakeTransport = transport;
    return new DaemonRuntime(
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            registrationTokenFile(),
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            null,
            ENVIRONMENT_ROOT,
            dataDir()),
        transport,
        registry,
        skillRegistry,
        new InMemoryDaemonInvocationJournal(),
        Executors.newSingleThreadScheduledExecutor(),
        Executors.newVirtualThreadPerTaskExecutor());
  }

  private DaemonRuntime runtime(
      FakeTransport transport,
      EnvironmentCapability capability,
      InMemoryDaemonInvocationJournal journal) {
    handshakeTransport = transport;
    DaemonCapabilityRegistry registry = new DaemonCapabilityRegistry();
    registry.register(capability);
    return new DaemonRuntime(
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            registrationTokenFile(),
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            null,
            ENVIRONMENT_ROOT,
            dataDir()),
        transport,
        registry,
        DaemonSkillTestSupport.open(dataDir()),
        journal,
        Executors.newSingleThreadScheduledExecutor(),
        Executors.newVirtualThreadPerTaskExecutor());
  }

  private DaemonRuntime runtime(
      FakeTransport transport,
      EnvironmentCapability capability,
      ScheduledExecutorService scheduler) {
    handshakeTransport = transport;
    DaemonCapabilityRegistry registry = new DaemonCapabilityRegistry();
    registry.register(capability);
    return new DaemonRuntime(
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            registrationTokenFile(),
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            null,
            ENVIRONMENT_ROOT,
            dataDir()),
        transport,
        registry,
        DaemonSkillTestSupport.open(dataDir()),
        new InMemoryDaemonInvocationJournal(),
        scheduler,
        Executors.newVirtualThreadPerTaskExecutor());
  }

  private DaemonRuntime runtime(
      FakeTransport transport,
      EnvironmentCapability capability,
      Duration heartbeatInterval,
      Duration defaultToolTimeout) {
    return runtime(
        transport,
        capability,
        DaemonSkillTestSupport.open(dataDir()),
        heartbeatInterval,
        defaultToolTimeout);
  }

  private DaemonRuntime runtime(
      FakeTransport transport,
      EnvironmentCapability capability,
      DaemonSkillRegistry skillRegistry) {
    return runtime(
        transport, capability, skillRegistry, Duration.ofMinutes(1), Duration.ofSeconds(10));
  }

  private DaemonRuntime runtime(
      FakeTransport transport,
      EnvironmentCapability capability,
      DaemonSkillRegistry skillRegistry,
      Duration heartbeatInterval,
      Duration defaultToolTimeout) {
    handshakeTransport = transport;
    DaemonCapabilityRegistry registry = new DaemonCapabilityRegistry();
    registry.register(capability);
    return new DaemonRuntime(
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            registrationTokenFile(),
            heartbeatInterval,
            Duration.ZERO,
            Duration.ofSeconds(1),
            defaultToolTimeout,
            null,
            ENVIRONMENT_ROOT,
            dataDir()),
        transport,
        registry,
        skillRegistry,
        new InMemoryDaemonInvocationJournal(),
        Executors.newSingleThreadScheduledExecutor(),
        Executors.newVirtualThreadPerTaskExecutor());
  }

  private void deleteRecursively(Path root) throws Exception {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (Exception ignored) {
                  // 尽力清理 temp skill fixture
                }
              });
    }
  }

  /** 模拟 Gateway 完成 HELLO/WELCOME 握手：发送 WELCOME 让 daemon 推进到 READY。 */
  private void completeHandshake() throws InterruptedException {
    handshakeTransport.awaitNextMessageType(DaemonMessageType.HELLO);
    handshakeTransport.receive(platformMessage(DaemonMessageType.WELCOME));
  }

  private DaemonEnvelope invoke(String invocationId) {
    return invoke(invocationId, "test");
  }

  private DaemonEnvelope invoke(String invocationId, String capabilityId) {
    return invoke(invocationId, capabilityId, "1.0.0", 1000);
  }

  private DaemonEnvelope invoke(
      String invocationId, String capabilityId, String capabilityVersion, long timeoutMillis) {
    return invoke(invocationId, capabilityId, capabilityVersion, timeoutMillis, "{}");
  }

  /** 严格 v1 INVOKE：外壳只有 capabilityId/capabilityVersion/arguments/timeoutMillis，目录只来自 arguments。 */
  private DaemonEnvelope invoke(
      String invocationId,
      String capabilityId,
      String capabilityVersion,
      long timeoutMillis,
      String argumentsJson) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION,
        DaemonMessageType.INVOKE,
        ENVIRONMENT_ID,
        invocationId,
        "{\"capabilityId\":\""
            + capabilityId
            + "\",\"capabilityVersion\":\""
            + capabilityVersion
            + "\",\"arguments\":"
            + argumentsJson
            + ",\"timeoutMillis\":"
            + timeoutMillis
            + "}");
  }

  private static String jsonEscape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("\u0007", "\\u0007");
  }

  private DaemonEnvelope invokeWithoutTimeout(
      String invocationId, String capabilityId, String capabilityVersion) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION,
        DaemonMessageType.INVOKE,
        ENVIRONMENT_ID,
        invocationId,
        "{\"capabilityId\":\""
            + capabilityId
            + "\",\"capabilityVersion\":\""
            + capabilityVersion
            + "\",\"arguments\":{},\"timeoutMillis\":0}");
  }

  private DaemonEnvelope invoke(
      String invocationId,
      EnvironmentCapabilityId capabilityId,
      String capabilityVersion,
      String argumentsJson) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION,
        DaemonMessageType.INVOKE,
        ENVIRONMENT_ID,
        invocationId,
        "{\"capabilityId\":\""
            + capabilityId.value()
            + "\",\"capabilityVersion\":\""
            + capabilityVersion
            + "\",\"arguments\":"
            + argumentsJson
            + ",\"timeoutMillis\":10000}");
  }

  private DaemonEnvelope platformMessage(DaemonMessageType messageType) {
    // WELCOME 必须通告正的资源字节预算（协议要求）；其余平台消息只承载空 payload。
    if (messageType == DaemonMessageType.WELCOME) {
      return new DaemonEnvelope(
          DaemonProtocol.VERSION,
          messageType,
          ENVIRONMENT_ID,
          null,
          "{\"maxResourceBytes\":" + MAX_RESOURCE_BYTES + "}");
    }
    return new DaemonEnvelope(DaemonProtocol.VERSION, messageType, ENVIRONMENT_ID, null, "{}");
  }

  /**
   * 在后台线程触发能力完成回调。
   *
   * <p>终态编码在完成回调内同步完成直传（申请票据 → PUT → 提交），因此完成回调必须与测试线程驱动协议帧并行执行，否则测试线程会先被回调阻塞。
   */
  private Thread completeAsync(TestCapability tool, EnvironmentCapabilityResult result) {
    Thread thread = new Thread(() -> tool.complete(result), "complete-" + result.callId());
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private void awaitCompletion(Thread completion) throws InterruptedException {
    completion.join(TimeUnit.SECONDS.toMillis(ASYNC_TEST_TIMEOUT_SECONDS));
    assertFalse(completion.isAlive(), "能力完成回调未在预算内收敛");
  }

  /** 上传票据帧：envelope 的 invocationId 是被调用的活动调用，transferId 只在 payload 中。 */
  private DaemonEnvelope ticketEnvelope(String invocationId, String ticketPayload) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION,
        DaemonMessageType.RESOURCE_UPLOAD_TICKET,
        ENVIRONMENT_ID,
        invocationId,
        ticketPayload);
  }

  /** 一个已上传的瞬态引用：blob-upload scheme + 规范 size/sha，仅用于终态编码的输入检查。 */
  private static ResourceRef uploadedRef(UUID uploadId, String mediaType) {
    return new ResourceRef(
        ResourceRef.blobUploadUri(uploadId), mediaType, null, 1L, "0".repeat(64));
  }

  private DaemonEnvelope cancel(String invocationId) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION, DaemonMessageType.CANCEL, ENVIRONMENT_ID, invocationId, "{}");
  }

  private List<String> jsonTexts(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.asText()));
    return values;
  }

  private void assertMessageTypes(List<DaemonEnvelope> envelopes, DaemonMessageType... expected) {
    assertEquals(List.of(expected), envelopes.stream().map(DaemonEnvelope::messageType).toList());
  }

  private final class FakeTransport implements DaemonTransport {

    private final Semaphore connections = new Semaphore(0);
    private final LinkedBlockingQueue<String> sent = new LinkedBlockingQueue<>();
    private final AtomicBoolean failNextConnection = new AtomicBoolean();
    private final AtomicBoolean failNextSend = new AtomicBoolean();
    private final AtomicBoolean throwNextConnection = new AtomicBoolean();
    private final AtomicBoolean returnNullNextConnection = new AtomicBoolean();
    private final AtomicBoolean delayNextConnection = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closeThrows = new AtomicBoolean();
    private final List<DaemonTransportListener> listeners = new CopyOnWriteArrayList<>();
    private volatile DaemonTransportListener listener;
    private volatile FakeConnection connection;
    private volatile CompletableFuture<DaemonConnection> delayedConnection;

    @Override
    public CompletionStage<DaemonConnection> connect(DaemonTransportListener listener) {
      this.listener = listener;
      listeners.add(listener);
      connections.release();
      if (throwNextConnection.compareAndSet(true, false)) {
        throw new IllegalStateException("connection threw");
      }
      if (failNextConnection.compareAndSet(true, false)) {
        return CompletableFuture.failedFuture(new IllegalStateException("connection failed"));
      }
      if (returnNullNextConnection.compareAndSet(true, false)) {
        return CompletableFuture.completedFuture(null);
      }
      connection = new FakeConnection();
      if (delayNextConnection.compareAndSet(true, false)) {
        delayedConnection = new CompletableFuture<>();
        return delayedConnection;
      }
      return CompletableFuture.completedFuture(connection);
    }

    private void failNextConnection() {
      failNextConnection.set(true);
    }

    private void failNextSend() {
      failNextSend.set(true);
    }

    private void throwNextConnection() {
      throwNextConnection.set(true);
    }

    private void returnNullNextConnection() {
      returnNullNextConnection.set(true);
    }

    private void delayNextConnection() {
      delayNextConnection.set(true);
    }

    private void completeDelayedConnection() {
      delayedConnection.complete(connection);
    }

    private void receive(DaemonEnvelope envelope) {
      receiveRaw(codec.encode(envelope));
    }

    private void receiveRaw(String message) {
      listener.onMessage(message);
    }

    private void receiveFromConnection(int connectionIndex, DaemonEnvelope envelope) {
      listeners.get(connectionIndex).onMessage(codec.encode(envelope));
    }

    private void receiveRawFromConnection(int connectionIndex, String message) {
      listeners.get(connectionIndex).onMessage(message);
    }

    private void disconnect() {
      connection.open = false;
      listener.onDisconnected(null);
    }

    private void awaitConnections(int expected) throws InterruptedException {
      assertTrue(connections.tryAcquire(expected, ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    /** 断言在给定毫秒内没有新的连接尝试（终态失败后不得重连）。 */
    private void awaitNoNewConnection(long millis) throws InterruptedException {
      if (connections.tryAcquire(1, millis, TimeUnit.MILLISECONDS)) {
        throw new AssertionError("unexpected additional connection attempt");
      }
    }

    private boolean awaitConnection(Duration timeout) throws InterruptedException {
      return connections.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private List<DaemonEnvelope> takeMessages(int count) throws InterruptedException {
      List<DaemonEnvelope> messages = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        String message = sent.poll(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(message != null, "expected daemon message " + (index + 1));
        messages.add(codec.decode(message));
      }
      return messages;
    }

    private void awaitNextMessageType(DaemonMessageType expected) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ASYNC_TEST_TIMEOUT_SECONDS);
      String message;
      while ((message = sent.peek()) == null && System.nanoTime() < deadline) {
        TimeUnit.MILLISECONDS.sleep(1);
      }
      assertTrue(message != null, "expected daemon message " + expected);
      assertEquals(expected, codec.decode(message).messageType());
    }

    private DaemonEnvelope takeNextMessage() throws InterruptedException {
      String message = sent.poll(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(message != null, "expected daemon message");
      return codec.decode(message);
    }

    private boolean hasMessages() {
      return !sent.isEmpty();
    }

    private boolean awaitMessage(Duration timeout) throws InterruptedException {
      return sent.poll(timeout.toMillis(), TimeUnit.MILLISECONDS) != null;
    }

    @Override
    public void close() {
      closed.set(true);
      if (closeThrows.compareAndSet(true, false)) {
        throw new IllegalStateException("transport close failed");
      }
    }

    private final class FakeConnection implements DaemonConnection {

      private volatile boolean open = true;
      private final CountDownLatch closed = new CountDownLatch(1);

      @Override
      public CompletionStage<Void> sendText(String message) {
        if (failNextSend.compareAndSet(true, false)) {
          open = false;
          return CompletableFuture.failedFuture(new IllegalStateException("send failed"));
        }
        sent.add(message);
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public void close() {
        open = false;
        closed.countDown();
      }

      @Override
      public boolean isOpen() {
        return open;
      }

      private boolean awaitClosed(Duration timeout) throws InterruptedException {
        return closed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
      }
    }
  }

  private static final class DefaultTimeoutCapability implements EnvironmentCapability {

    private final EnvironmentCapabilityDescriptor descriptor =
        new EnvironmentCapabilityDescriptor(
            new EnvironmentCapabilityId("fallback"),
            "1.0.0",
            new InputSchema("fallback arguments", Map.of(), Set.of(), false),
            Duration.ZERO);
    private final TestCapabilityHandle handle = new TestCapabilityHandle();
    private volatile EnvironmentCapabilityExecutionListener listener;
    private volatile EnvironmentCapabilityExecutionRequest request;

    @Override
    public EnvironmentCapabilityDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public EnvironmentCapabilityExecutionHandle execute(
        EnvironmentCapabilityExecutionRequest request,
        EnvironmentCapabilityExecutionListener listener) {
      this.request = request;
      this.listener = listener;
      return handle;
    }

    private void complete() {
      listener.onComplete(
          new EnvironmentCapabilityResult(request.call().id(), List.of(), false, "{}"));
    }
  }

  private static final class TestCapability implements EnvironmentCapability {

    private final EnvironmentCapabilityDescriptor descriptor;
    private final AtomicInteger executions = new AtomicInteger();
    private final TestCapabilityHandle handle = new TestCapabilityHandle();
    private volatile EnvironmentCapabilityExecutionListener listener;
    private volatile EnvironmentCapabilityExecutionRequest request;

    private TestCapability() {
      this("test", new InputSchema("test arguments", Map.of(), Set.of(), false));
    }

    private TestCapability(String name, InputSchema schema) {
      descriptor =
          new EnvironmentCapabilityDescriptor(
              new EnvironmentCapabilityId(name), "1.0.0", schema, Duration.ofSeconds(10));
    }

    @Override
    public EnvironmentCapabilityDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public EnvironmentCapabilityExecutionHandle execute(
        EnvironmentCapabilityExecutionRequest request,
        EnvironmentCapabilityExecutionListener listener) {
      executions.incrementAndGet();
      this.request = request;
      this.listener = listener;
      return handle;
    }

    private void partial(EnvironmentCapabilityResult result) {
      listener.onPartial(result);
    }

    private void complete(EnvironmentCapabilityResult result) {
      listener.onComplete(result);
    }

    private void error(Throwable error) {
      listener.onError(error);
    }
  }

  private static final class ExplodingMessageException extends RuntimeException {

    @Override
    public String getMessage() {
      throw new IllegalStateException("message rendering failed");
    }
  }

  private static final class BlockingCapability implements EnvironmentCapability {

    private final EnvironmentCapabilityDescriptor descriptor =
        new EnvironmentCapabilityDescriptor(
            new EnvironmentCapabilityId("blocking"),
            "1.0.0",
            new InputSchema("blocking arguments", Map.of(), Set.of(), false),
            Duration.ofSeconds(10));
    private final CountDownLatch executionStarted = new CountDownLatch(1);
    private final CountDownLatch allowReturn = new CountDownLatch(1);
    private final TestCapabilityHandle handle = new TestCapabilityHandle();

    @Override
    public EnvironmentCapabilityDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public EnvironmentCapabilityExecutionHandle execute(
        EnvironmentCapabilityExecutionRequest request,
        EnvironmentCapabilityExecutionListener listener) {
      executionStarted.countDown();
      try {
        if (!allowReturn.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test did not release blocking tool");
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("blocking tool interrupted", error);
      }
      return handle;
    }
  }

  private static final class ReconnectRejectingScheduler extends ScheduledThreadPoolExecutor {

    private ReconnectRejectingScheduler() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      throw new RejectedExecutionException("reconnect scheduling rejected");
    }
  }

  private static final class TestCapabilityHandle implements EnvironmentCapabilityExecutionHandle {

    private final AtomicInteger cancelCalls = new AtomicInteger();

    @Override
    public void cancel() {
      cancelCalls.incrementAndGet();
    }

    @Override
    public boolean isCancelled() {
      return cancelCalls.get() > 0;
    }
  }
}
