package fun.fengwk.kkstudio.harness.daemon;

import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.ACK;
import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.CANCELLED;
import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.COMPLETED;
import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.ERROR;
import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.HELLO;
import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.PARTIAL;
import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.READY;
import static fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType.STARTED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.daemon.coding.CodingToolsConfig;
import fun.fengwk.kkstudio.harness.daemon.coding.InMemoryResourceStore;
import fun.fengwk.kkstudio.harness.daemon.coding.ReadCapability;
import fun.fengwk.kkstudio.harness.daemon.coding.ResourceStore;
import fun.fengwk.kkstudio.harness.daemon.coding.WriteCapability;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationState;
import fun.fengwk.kkstudio.harness.daemon.journal.InMemoryDaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillRegistry;
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
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityResultCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceRef;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  private static final EnvironmentId ENVIRONMENT_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final Path ENVIRONMENT_ROOT = Path.of(System.getProperty("user.dir"));

  private final DaemonEnvelopeCodec codec = new DaemonEnvelopeCodec();
  private final DaemonCapabilityResultCodec resultCodec = new DaemonCapabilityResultCodec();
  private DaemonRuntime runtime;
  private FakeTransport handshakeTransport;

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
    completeHandshake(0);
    transport.takeMessages(2);

    transport.receiveRaw(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"ERROR\",\"environmentId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"sequence\":1,\"payload\":{\"code\":\"REGISTRATION_REJECTED\","
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
    completeHandshake(0);
    transport.takeMessages(2);

    transport.receiveRaw(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"ERROR\",\"environmentId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"sequence\":1,\"payload\":{\"code\":\"RETRY_LATER\","
            + "\"message\":\"server busy, retry later\"}}");

    assertNotEquals(DaemonRuntimeState.FAILED, runtime.state());
    transport.awaitConnections(1);
    completeHandshake(0);
    assertMessageTypes(transport.takeMessages(2), HELLO, READY);
  }

  /** 非冲突 ERROR（如普通协议提示）不终止 daemon。 */
  @Test
  void nonConflictErrorDoesNotTerminate() throws Exception {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestCapability());

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receiveRaw(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"ERROR\",\"environmentId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"sequence\":1,\"payload\":{\"message\":\"informational\"}}");
    assertEquals(DaemonRuntimeState.READY, runtime.state());
  }

  /** 断线后必须重连并重新完成 HELLO/WELCOME/READY 的握手，使 daemon 在新连接上重新进入 READY 状态。 */
  @Test
  void reconnectsAfterDisconnectAndReannouncesReadiness() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestCapability(), "Custom & stable environment.");

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    List<DaemonEnvelope> handshake = transport.takeMessages(2);
    assertMessageTypes(handshake, HELLO, READY);
    JsonNode hello = codec.readPayload(handshake.get(0));
    assertEquals("test-registration-token", hello.path("registrationToken").asText());
    assertEquals(DaemonProtocol.VERSION, hello.path("protocolVersion").asInt());
    assertEquals(
        EnvironmentCapabilityCatalog.version(), hello.path("capabilityCatalogVersion").asText());
    assertTrue(hello.path("toolCatalogVersion").isMissingNode());
    DaemonCapabilitiesCodec capabilitiesCodec = new DaemonCapabilitiesCodec();
    DaemonEnvironmentInfo firstEnvironment =
        capabilitiesCodec.decode(handshake.get(1).payloadJson()).environment();
    assertEquals("Custom & stable environment.", firstEnvironment.note());
    assertTrue(codec.readPayload(handshake.get(1)).path("skills").isArray());

    transport.disconnect();
    transport.awaitConnections(1);
    completeHandshake(0);
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
            "test-registration-token",
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            null,
            ENVIRONMENT_ROOT,
            List.of());

    assertThrows(
        IllegalStateException.class,
        () ->
            DaemonRuntime.create(
                config,
                DaemonSkillRegistry.empty(),
                null,
                (registry, executor, scheduler) -> registry.register(new TestCapability())));
  }

  /** 生产工厂必须在同一装配点创建资源、注入完整固定 capability 目录，并把生命周期移交给可关闭 runtime。 */
  @Test
  void productionFactoryCreatesCompleteClosableRuntime() {
    DaemonConfig config =
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            "test-registration-token",
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            null,
            ENVIRONMENT_ROOT,
            List.of());
    CodingToolsConfig toolsConfig =
        new CodingToolsConfig(
            ENVIRONMENT_ROOT, 2000, 50 * 1024, "bash", new InMemoryResourceStore());

    runtime = DaemonRuntime.create(config, toolsConfig, DaemonSkillRegistry.empty());

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
            "test-registration-token",
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            null,
            ENVIRONMENT_ROOT,
            List.of());
    AtomicReference<ExecutorService> executorRef = new AtomicReference<>();
    AtomicReference<ScheduledExecutorService> schedulerRef = new AtomicReference<>();

    assertThrows(
        IllegalStateException.class,
        () ->
            DaemonRuntime.create(
                config,
                DaemonSkillRegistry.empty(),
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
      DaemonSkillRegistry skillRegistry = DaemonSkillRegistry.discover(List.of(skillRoot));
      runtime =
          runtime(
              transport,
              new TestCapability(),
              skillRegistry,
              Duration.ofMinutes(1),
              Duration.ofSeconds(10),
              null);

      runtime.start();
      transport.awaitConnections(1);
      completeHandshake(0);
      List<DaemonEnvelope> handshake = transport.takeMessages(2);
      assertMessageTypes(handshake, HELLO, READY);

      DaemonCapabilities capabilities = capabilitiesCodec.decode(handshake.get(1).payloadJson());

      assertEquals(DaemonCapabilities.VERSION, capabilities.version());
      assertEquals(ZoneId.systemDefault().getId(), capabilities.environment().timeZone());
      assertEquals(
          DaemonOperatingSystemDetector.detectCurrent(),
          capabilities.environment().operatingSystem());
      assertEquals(
          new DaemonConfig(
                  URI.create("ws://localhost/gateway"),
                  "test-registration-token",
                  Duration.ofMinutes(1),
                  Duration.ZERO,
                  Duration.ofSeconds(1),
                  Duration.ofSeconds(10),
                  null,
                  ENVIRONMENT_ROOT,
                  List.of())
              .effectiveNote(capabilities.environment().operatingSystem()),
          capabilities.environment().note());
      assertEquals(1, capabilities.skills().size());
      assertEquals("demo", capabilities.skills().get(0).name());
      assertEquals("Demo skill", capabilities.skills().get(0).description());
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
    completeHandshake(0);
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
    completeHandshake(0);

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
    completeHandshake(8);
    transport.takeMessages(2);
    transport.failNextSend();
    transport.receive(invoke("send-failure", 9));
    transport.awaitConnections(1);
    completeHandshake(0);
    assertMessageTypes(transport.takeMessages(2), HELLO, READY);
    assertEquals(1, tool.executions.get());

    transport.receive(invoke("send-failure", 1));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
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
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("unknown-invocation", 1, "missing"));

    List<DaemonEnvelope> messages = transport.takeMessages(2);
    assertMessageTypes(messages, ACK, DaemonMessageType.FAILED);
    assertTrue(messages.get(1).payloadJson().contains("unknown Environment capability"));
    assertEquals(
        DaemonInvocationState.FAILED, journal.find("unknown-invocation").orElseThrow().state());
  }

  /**
   * 测试意图：v2 INVOKE 外壳不携带目录，coding workdir 只存在于具体 arguments 中——携带已删除的 {@code workspacePath}
   * 字段（文本或数字）必须在协议边界作为 unknown field 拒绝，且不触达 capability SPI。
   */
  @Test
  void rejectsUnknownWorkspacePathFieldInV2InvokePayloadBeforeSideEffects()
      throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);

    transport.receiveRaw(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"INVOKE\","
            + "\"environmentId\":\"11111111-1111-1111-1111-111111111111\",\"sequence\":1,\"payload\":{\"capabilityId\":\"test\","
            + "\"capabilityVersion\":\"1.0.0\",\"arguments\":{},\"timeoutMillis\":100}}");
    assertMessageTypes(transport.takeMessages(1), ERROR);

    transport.receiveRaw(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"INVOKE\","
            + "\"environmentId\":\"11111111-1111-1111-1111-111111111111\",\"sequence\":2,\"payload\":{\"capabilityId\":\"test\","
            + "\"capabilityVersion\":\"1.0.0\",\"workspacePath\":1,\"arguments\":{},\"timeoutMillis\":100}}");
    assertMessageTypes(transport.takeMessages(1), ERROR);

    transport.receiveRaw(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"INVOKE\","
            + "\"environmentId\":\"11111111-1111-1111-1111-111111111111\",\"sequence\":3,\"payload\":{\"capabilityId\":\"test\","
            + "\"capabilityVersion\":\"1.0.0\",\"workspacePath\":\".\",\"arguments\":{},\"timeoutMillis\":100}}");
    assertMessageTypes(transport.takeMessages(1), ERROR);
    assertEquals(0, tool.executions.get());

    // 去掉 workspacePath 的严格 v2 payload 正常执行，证明拒绝只针对该未知字段。
    transport.receive(invoke("valid-after-rejected-workspace", 1));
    transport.takeMessages(2);
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
              new CodingToolsConfig(root, 2000, 50 * 1024, "bash", new InMemoryResourceStore()),
              Executors.newVirtualThreadPerTaskExecutor()));
      runtime = runtime(transport, registry, root);

      runtime.start();
      transport.awaitConnections(1);
      completeHandshake(0);
      transport.takeMessages(2);

      // 省略 workdir：schema 必填校验在构造执行请求时失败 → 直接 FAILED，绝不发出 STARTED。
      transport.receive(
          invoke("missing-workdir", 1, "fs.read", "2", 100, "{\"path\":\"local.txt\"}"));
      List<DaemonEnvelope> missing = transport.takeMessages(2);
      assertMessageTypes(missing, ACK, DaemonMessageType.FAILED);
      String failure = missing.get(1).payloadJson();
      assertTrue(failure.contains("workdir"), failure);
      assertFalse(failure.contains("from-environment-root"), failure);

      // 显式绝对 workdir 正常执行并读到该目录下的文件；可见内容证明目录来自 arguments 而非 Environment Root。
      transport.receive(
          invoke(
              "explicit-workdir",
              2,
              "fs.read",
              "2",
              100,
              "{\"path\":\"local.txt\",\"workdir\":\"" + jsonEscape(root.toString()) + "\"}"));
      assertMessageTypes(transport.takeMessages(3), ACK, STARTED, DaemonMessageType.COMPLETED);
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
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION,
            DaemonMessageType.INVOKE,
            EnvironmentId.parse("22222222-2222-2222-2222-222222222222"),
            "wrong-scope",
            1,
            "{\"capabilityId\":\"test\",\"arguments\":{}}"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);

    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION,
            DaemonMessageType.INVOKE,
            ENVIRONMENT_ID,
            "bad-payload",
            2,
            "{\"capabilityId\":\"test\",\"arguments\":[]}"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);

    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION,
            DaemonMessageType.INVOKE,
            ENVIRONMENT_ID,
            "invalid-capability-id",
            3,
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
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receiveRaw(
        "{\"protocolVersion\":3,\"messageType\":\"INVOKE\","
            + "\"environmentId\":\"11111111-1111-1111-1111-111111111111\",\"sequence\":1,"
            + "\"payload\":{\"capabilityId\":\"test\",\"capabilityVersion\":\"1.0.0\","
            + "\"arguments\":{},\"timeoutMillis\":1000}}");
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    assertEquals(0, tool.executions.get());
    transport.receiveRaw(
        "{\"protocolVersion\":4,\"messageType\":\"INVOKE\","
            + "\"environmentId\":\"11111111-1111-1111-1111-111111111111\",\"sequence\":1,"
            + "\"payload\":{\"capabilityId\":\"test\",\"capabilityVersion\":\"1.0.0\","
            + "\"arguments\":{},\"timeoutMillis\":1000}}");
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    assertEquals(0, tool.executions.get());
    transport.receive(invoke("valid-after-unsupported-version", 1));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
  }

  /** 缺失 invocationId 在 codec 边界失败，不得触达 journal 或 capability SPI。 */
  @Test
  void rejectsInvokeWithoutInvocationIdBeforeSideEffects() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receiveRaw(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"INVOKE\","
            + "\"environmentId\":\"11111111-1111-1111-1111-111111111111\",\"sequence\":1,\"payload\":{\"capabilityId\":\"test\","
            + "\"capabilityVersion\":\"1.0.0\",\"arguments\":{},"
            + "\"timeoutMillis\":1000}}");

    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    transport.receiveRaw(
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"CANCEL\","
            + "\"environmentId\":\"11111111-1111-1111-1111-111111111111\",\"sequence\":1,\"payload\":{}}");
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    assertEquals(0, tool.executions.get());
    transport.receive(invoke("valid-after-missing-id", 1));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
  }

  /** 当前连接只接受连续 sequence；完全相同的最新 envelope 重发会 ACK 并复用 journal。 */
  @Test
  void rejectsOutOfOrderSequenceAndHandlesIdenticalReplayIdempotently()
      throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(3);
    transport.takeMessages(2);
    DaemonEnvelope invoke = invoke("sequence-invocation", 4);
    transport.receive(invoke);
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    transport.receive(invoke);
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    assertEquals(1, tool.executions.get());

    transport.receive(invoke("backward", 3));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    transport.receive(invoke("jump", 6));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    transport.receive(cancel("sequence-invocation", 5));
    assertMessageTypes(transport.takeMessages(2), ACK, CANCELLED);
  }

  /** 相同 sequence 的 messageType、invocationId 或 payload 冲突必须在任何副作用前拒绝。 */
  @Test
  void rejectsConflictingEnvelopeThatReusesLatestSequence() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(3);
    transport.takeMessages(2);
    transport.receive(invoke("original", 4));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);

    transport.receive(invoke("different-invocation", 4));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    transport.receive(invoke("original", 4, "test", "1.0.0", 2000));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    transport.receive(cancel("original", 4));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);
    assertEquals(1, tool.executions.get());
    assertEquals(0, tool.handle.cancelCalls.get());

    transport.receive(invoke("different-invocation", 5));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    assertEquals(2, tool.executions.get());
  }

  /** ACK/ERROR 只推进连接 sequence，不创建 invocation 或发送额外响应；WELCOME 由握手阶段消耗。 */
  @Test
  void acceptsInboundPlatformProtocolMessagesWithinSequence() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(platformMessage(DaemonMessageType.ACK, 1));
    transport.receive(platformMessage(DaemonMessageType.ERROR, 2));
    assertFalse(transport.hasMessages());

    transport.receive(invoke("after-control", 3));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    assertEquals(1, tool.executions.get());
  }

  /** skill.load 作为标准 capability 执行，成功返回指令正文。 */
  @Test
  void loadsSkillViaCapabilityInvocation() throws Exception {
    Path skillRoot = Files.createTempDirectory("daemon-skill-root");
    Path skillDir = skillRoot.resolve("my-skill");
    Files.createDirectories(skillDir);
    Files.writeString(
        skillDir.resolve("SKILL.md"),
        "---\nname: my-skill\ndescription: A test skill\n---\n# My Skill Body\nInstruction content.");
    try {
      DaemonSkillRegistry skillRegistry = DaemonSkillRegistry.discover(List.of(skillRoot));
      FakeTransport transport = new FakeTransport();
      DaemonCapabilityRegistry registry = new DaemonCapabilityRegistry();
      registry.register(
          new SkillLoadCapability(skillRegistry, Executors.newVirtualThreadPerTaskExecutor()));
      runtime = runtime(transport, registry, skillRegistry);

      runtime.start();
      transport.awaitConnections(1);
      completeHandshake(0);
      transport.takeMessages(2);

      transport.receive(
          invoke(
              "skill-1", 1, EnvironmentCapabilityIds.SKILL_LOAD, "1", "{\"name\":\"my-skill\"}"));
      List<DaemonEnvelope> messages = transport.takeMessages(3);
      assertMessageTypes(messages, ACK, STARTED, COMPLETED);
      EnvironmentCapabilityResult result = resultCodec.decodeResult(messages.get(2).payloadJson());
      assertFalse(result.error());
      assertEquals(
          "# My Skill Body\nInstruction content.",
          ((TextResultContent) result.contents().get(0)).text());
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
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("version-mismatch", 1, "test", "2.0.0", 1000));

    assertMessageTypes(transport.takeMessages(2), ACK, DaemonMessageType.FAILED);
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
    completeHandshake(6);
    transport.takeMessages(2);
    DaemonEnvelope invoke = invoke("invocation-1", 7);
    transport.receive(invoke);
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    assertEquals(1, tool.executions.get());

    transport.receive(invoke);
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
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
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("invocation-1", 1));
    assertMessageTypes(transport.takeMessages(2), ACK, COMPLETED);
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
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("timeout", 1, "test", "1.0.0", 30));
    transport.takeMessages(2);

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
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("maximum-timeout", 1, "test", "1.0.0", Long.MAX_VALUE));

    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    assertFalse(transport.awaitMessage(Duration.ofMillis(100)));
    assertEquals(
        DaemonInvocationState.RUNNING, journal.find("maximum-timeout").orElseThrow().state());

    transport.receive(cancel("maximum-timeout", 2));
    assertMessageTypes(transport.takeMessages(2), ACK, CANCELLED);
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
      registry.register(
          new WriteCapability(
              new CodingToolsConfig(root, 2000, 50 * 1024, "bash", new InMemoryResourceStore()),
              taskExecutor));
      runtime =
          new DaemonRuntime(
              new DaemonConfig(
                  URI.create("ws://localhost/gateway"),
                  "test-registration-token",
                  Duration.ofMinutes(1),
                  Duration.ZERO,
                  Duration.ofSeconds(1),
                  Duration.ofSeconds(10),
                  null,
                  root,
                  List.of()),
              transport,
              registry,
              DaemonSkillRegistry.empty(),
              new InMemoryDaemonInvocationJournal(),
              scheduler,
              taskExecutor);

      handshakeTransport = transport;
      runtime.start();
      transport.awaitConnections(1);
      completeHandshake(0);
      transport.takeMessages(2);
      transport.receive(
          invoke(
              "write-timeout",
              1,
              "fs.write",
              "2",
              100,
              "{\"workdir\":\""
                  + jsonEscape(root.toString())
                  + "\",\"path\":\"timeout.txt\",\"content\":\"must not be written\"}"));

      assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
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
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invokeWithoutTimeout("omitted-descriptor-timeout", 1, "test", "1.0.0"));
    transport.takeMessages(2);

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
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invokeWithoutTimeout("omitted-default-timeout", 1, "fallback", "1.0.0"));
    transport.takeMessages(2);

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
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("zero-timeout", 1, "test", "1.0.0", 0));
    transport.takeMessages(2);
    assertEquals(Duration.ofSeconds(10), tool.request.effectiveTimeout());
    tool.complete(new EnvironmentCapabilityResult("zero-timeout", List.of(), false, "{}"));
    assertMessageTypes(transport.takeMessages(1), COMPLETED);

    transport.receive(invoke("cancel-before-timeout", 2, "test", "1.0.0", 30));
    transport.takeMessages(2);
    transport.receive(cancel("cancel-before-timeout", 3));
    assertMessageTypes(transport.takeMessages(2), ACK, CANCELLED);
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
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("default-timeout", 1, "fallback", "1.0.0", 0));
    transport.takeMessages(2);

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
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("complete-before-timeout", 1, "test", "1.0.0", 50));
    transport.takeMessages(2);
    tool.complete(
        new EnvironmentCapabilityResult("complete-before-timeout", List.of(), false, "{}"));

    assertMessageTypes(transport.takeMessages(1), COMPLETED);
    assertFalse(transport.awaitMessage(Duration.ofMillis(100)));
    assertEquals(0, tool.handle.cancelCalls.get());
  }

  /** 流式 PARTIAL 只承载 text/json；resource 内容在 PARTIAL 路径被拒绝并收敛为 FAILED。 */
  @Test
  void partialSerializesTextAndJsonButRejectsResourceContents() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    InMemoryResourceStore store = new InMemoryResourceStore();
    DaemonResourceRef stored = store.store(new byte[] {1, 2}, "application/json");
    runtime = runtime(transport, tool, store);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("structured-content", 1));
    transport.takeMessages(2);
    tool.partial(
        new EnvironmentCapabilityResult(
            "structured-content",
            List.of(new JsonResultContent("[1,2]"), new TextResultContent("hi")),
            false,
            "{}"));

    List<DaemonEnvelope> messages = transport.takeMessages(1);
    assertMessageTypes(messages, PARTIAL);
    String payload = messages.get(0).payloadJson();
    assertTrue(payload.contains("\"type\":\"json\""));
    assertTrue(payload.contains("\"json\":[1,2]"));
    assertTrue(payload.contains("\"type\":\"text\""));
    assertTrue(payload.contains("\"text\":\"hi\""));

    // PARTIAL 携带 resource → 编码在任何 store 访问前拒绝，收敛为 FAILED。
    transport.receive(invoke("structured-content-2", 2));
    transport.takeMessages(2);
    ResourceRef storedRef =
        new ResourceRef(
            stored.uri(), stored.mediaType(), stored.name(), stored.size(), stored.sha256());
    tool.partial(
        new EnvironmentCapabilityResult(
            "structured-content-2", List.of(new ResourceResultContent(storedRef)), false, "{}"));
    List<DaemonEnvelope> partialFailure = transport.takeMessages(1);
    assertMessageTypes(partialFailure, DaemonMessageType.FAILED);
    assertTrue(partialFailure.get(0).payloadJson().contains("cannot partial"));
  }

  /**
   * Capability 重构不可退化的 fitness gate：daemon wire 必须按序发送两个 PARTIAL，再发送唯一 COMPLETED，并保留
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
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke(invocationId, 1));
    tool.partial(
        new EnvironmentCapabilityResult(
            invocationId, List.of(new TextResultContent("partial1")), false, "{}"));
    tool.partial(
        new EnvironmentCapabilityResult(
            invocationId, List.of(new TextResultContent("partial2")), false, "{}"));
    tool.complete(
        new EnvironmentCapabilityResult(
            invocationId, List.of(new TextResultContent("complete")), false, "{}"));

    List<DaemonEnvelope> messages = transport.takeMessages(5);
    assertMessageTypes(messages, ACK, STARTED, PARTIAL, PARTIAL, COMPLETED);
    assertNull(messages.get(0).invocationId());
    for (int index = 1; index < messages.size(); index++) {
      assertEquals(invocationId, messages.get(index).invocationId());
    }
    assertEquals(1, codec.readPayload(messages.get(0)).path("acknowledgedSequence").asLong());
    assertTrue(codec.readPayload(messages.get(1)).isEmpty());

    DaemonCapabilityResultCodec resultCodec = new DaemonCapabilityResultCodec();
    List<EnvironmentCapabilityResult> results =
        messages.subList(2, 5).stream()
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

  /** wire resource 必须包含 Base64 字节，使接收端可独立持久化；终端 payload 自包含，不依赖连接内映射。 */
  @Test
  void resourcePayloadIsSelfContainedAndDecodesOnReceiver() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    InMemoryResourceStore store = new InMemoryResourceStore();
    byte[] data = new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
    DaemonResourceRef stored = store.store(data, "application/octet-stream");
    runtime = runtime(transport, tool, store);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("resource-rewrite", 1));
    transport.takeMessages(2);
    ResourceRef ref =
        new ResourceRef(
            stored.uri(), stored.mediaType(), stored.name(), stored.size(), stored.sha256());
    tool.complete(
        new EnvironmentCapabilityResult(
            "resource-rewrite", List.of(new ResourceResultContent(ref)), false, "{}"));

    List<DaemonEnvelope> terminal = transport.takeMessages(1);
    assertMessageTypes(terminal, COMPLETED);
    String payload = terminal.get(0).payloadJson();
    JsonNode resultNode = codec.readPayload(terminal.get(0)).get("result");
    JsonNode content = resultNode.get("contents").get(0);
    assertEquals("resource", content.get("type").asText());
    assertEquals(stored.uri(), content.get("uri").asText());
    assertEquals(stored.mediaType(), content.get("mediaType").asText());
    assertEquals(stored.size(), content.get("size").asLong());
    assertEquals(stored.sha256(), content.get("sha256").asText());
    String base64 = content.get("contentBase64").asText();
    assertEquals(Base64.getEncoder().encodeToString(data), base64);

    // 接收端解码为不可变 Resource 引用。
    DaemonCapabilityResultCodec resultCodec = new DaemonCapabilityResultCodec();
    EnvironmentCapabilityResult decoded = resultCodec.decodeResult(payload);
    assertEquals(1, decoded.contents().size());
    ResourceResultContent resource = (ResourceResultContent) decoded.contents().get(0);
    assertEquals(stored.uri(), resource.resource().uri());
    assertEquals(stored.mediaType(), resource.resource().mediaType());
  }

  /** BinaryResultContent 必须先经 resource store 落盘再编码为 wire resource，wire ref 可被 store 读回。 */
  @Test
  void storesBinaryResultContentBeforeEncoding() throws Exception {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    InMemoryResourceStore store = new InMemoryResourceStore();
    runtime = runtime(transport, tool, store);
    byte[] data = new byte[] {1, 2, 3};

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("binary-content", 1));
    transport.takeMessages(2);
    tool.complete(
        new EnvironmentCapabilityResult(
            "binary-content",
            List.of(new BinaryResultContent("application/octet-stream", data)),
            false,
            "{}"));

    List<DaemonEnvelope> terminal = transport.takeMessages(1);
    assertMessageTypes(terminal, COMPLETED);
    JsonNode content = codec.readPayload(terminal.get(0)).get("result").get("contents").get(0);
    assertEquals("resource", content.get("type").asText());
    assertEquals("application/octet-stream", content.get("mediaType").asText());
    assertEquals(3, content.get("size").asLong());
    assertEquals(Base64.getEncoder().encodeToString(data), content.get("contentBase64").asText());
    DaemonResourceRef wireRef =
        new DaemonResourceRef(
            content.get("uri").asText(),
            content.get("mediaType").asText(),
            null,
            content.get("size").asLong(),
            content.get("sha256").asText());
    assertArrayEquals(data, store.read(wireRef));
  }

  /** resource reader / 编码失败必须让 PARTIAL/COMPLETED 收敛为 FAILED，callback 不会泄漏 local-only ref。 */
  @Test
  void convergesResourceFailuresToFailedTerminal() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    ResourceStore failingStore =
        new ResourceStore() {
          @Override
          public DaemonResourceRef store(byte[] bytes, String mediaType) throws IOException {
            throw new IOException("store down");
          }

          @Override
          public byte[] read(DaemonResourceRef ref) throws IOException {
            throw new IOException("missing resource: " + ref.uri());
          }
        };
    runtime = runtime(transport, tool, failingStore);
    DaemonResourceRef local =
        new DaemonResourceRef(
            "file:///export/local-1", "application/json", null, 3L, "0".repeat(64));

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("resource-fail", 1));
    transport.takeMessages(2);

    ResourceRef localRef =
        new ResourceRef(local.uri(), local.mediaType(), local.name(), local.size(), local.sha256());
    // PARTIAL 失败必须收敛为 FAILED，且不再发出 PARTIAL 或 COMPLETED。
    tool.partial(
        new EnvironmentCapabilityResult(
            "resource-fail", List.of(new ResourceResultContent(localRef)), false, "{}"));
    List<DaemonEnvelope> partialFailure = transport.takeMessages(1);
    assertMessageTypes(partialFailure, DaemonMessageType.FAILED);
    assertTrue(partialFailure.get(0).payloadJson().contains("cannot partial"));

    // FAILED 之后迟到的 COMPLETED 必须被忽略（由 journal 守卫）。
    tool.complete(
        new EnvironmentCapabilityResult(
            "resource-fail", List.of(new ResourceResultContent(localRef)), false, "{}"));
    assertFalse(transport.hasMessages());

    // 现在一次带 COMPLETED 失败的独立 invocation 也必须收敛为 FAILED。
    transport.receive(invoke("resource-fail-2", 2));
    transport.takeMessages(2);
    tool.complete(
        new EnvironmentCapabilityResult(
            "resource-fail-2",
            List.of(
                new ResourceResultContent(
                    new ResourceRef(
                        "file:///export/local-2", "text/plain", null, 1L, "0".repeat(64)))),
            false,
            "{}"));
    List<DaemonEnvelope> completeFailure = transport.takeMessages(1);
    assertMessageTypes(completeFailure, DaemonMessageType.FAILED);
    assertTrue(completeFailure.get(0).payloadJson().contains("cannot complete"));
  }

  /** 无 resource store 的 generic runtime 遇 resource 必须确定性 FAILED，不能发送不可解析的内容。 */
  @Test
  void failsClosedWhenResourceStoreIsAbsent() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool); // no resource store

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("no-source", 1));
    transport.takeMessages(2);

    tool.complete(
        new EnvironmentCapabilityResult(
            "no-source",
            List.of(
                new ResourceResultContent(
                    new ResourceRef(
                        "file:///export/local-only",
                        "application/json",
                        null,
                        0L,
                        "0".repeat(64)))),
            false,
            "{}"));

    List<DaemonEnvelope> terminal = transport.takeMessages(1);
    assertMessageTypes(terminal, DaemonMessageType.FAILED);
    String payload = terminal.get(0).payloadJson();
    assertTrue(payload.contains("cannot complete"));
    assertFalse(payload.contains("\"type\":\"resource\""));
    assertFalse(payload.contains("\"contentBase64\""));
  }

  /** Tool error 和错误关联 ID 的完成回调都必须收敛为 FAILED。 */
  @Test
  void convertsToolErrorsAndMismatchedResultsToFailedTerminal() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("tool-error", 1));
    transport.takeMessages(2);
    tool.error(new IllegalStateException("tool failed"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.FAILED);

    transport.receive(invoke("wrong-result", 2));
    transport.takeMessages(2);
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
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("null-error", 1));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);

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
    ResourceStore adversarialStore =
        new ResourceStore() {
          @Override
          public DaemonResourceRef store(byte[] bytes, String mediaType) {
            throw new ExplodingMessageException();
          }

          @Override
          public byte[] read(DaemonResourceRef ref) {
            throw new ExplodingMessageException();
          }
        };
    runtime = runtime(transport, tool, journal, adversarialStore);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("adversarial-error", 1));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);

    tool.error(new ExplodingMessageException());
    List<DaemonEnvelope> errorTerminal = transport.takeMessages(1);
    assertMessageTypes(errorTerminal, DaemonMessageType.FAILED);
    assertEquals(
        "{\"message\":\"capability execution failed\"}", errorTerminal.get(0).payloadJson());
    assertEquals(
        DaemonInvocationState.FAILED, journal.find("adversarial-error").orElseThrow().state());

    tool.complete(new EnvironmentCapabilityResult("adversarial-error", List.of(), false, "{}"));
    assertFalse(transport.hasMessages());

    transport.receive(invoke("adversarial-result", 2));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
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
    assertEquals(
        "{\"message\":\"cannot complete capability result: capability execution failed\"}",
        resultTerminal.get(0).payloadJson());
    assertEquals(
        DaemonInvocationState.FAILED, journal.find("adversarial-result").orElseThrow().state());

    transport.receive(invoke("runtime-still-usable", 3));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    tool.complete(new EnvironmentCapabilityResult("runtime-still-usable", List.of(), false, "{}"));
    assertMessageTypes(transport.takeMessages(1), COMPLETED);
    assertEquals(
        DaemonInvocationState.COMPLETED,
        journal.find("runtime-still-usable").orElseThrow().state());
  }

  /** PARTIAL 必须流式转发，CANCEL 后迟到 complete callback 不能覆盖 CANCELLED 终态。 */
  @Test
  void forwardsPartialAndGuardsCancelledInvocationAgainstLateCallbacks()
      throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("invocation-2", 1));
    transport.takeMessages(2);

    tool.partial(
        new EnvironmentCapabilityResult(
            "invocation-2", List.of(new TextResultContent("chunk")), false, "{}"));
    List<DaemonEnvelope> partial = transport.takeMessages(1);
    assertMessageTypes(partial, PARTIAL);
    assertTrue(partial.get(0).payloadJson().contains("chunk"));

    transport.receive(cancel("invocation-2", 2));
    assertMessageTypes(transport.takeMessages(2), ACK, CANCELLED);
    assertEquals(1, tool.handle.cancelCalls.get());

    tool.complete(
        new EnvironmentCapabilityResult(
            "invocation-2", List.of(new TextResultContent("late")), false, "{}"));
    assertFalse(transport.hasMessages());
  }

  /** READY 能力对象只携带 environment、skills 摘要，且不含正文。 */
  @Test
  void announcesSkillsInCapabilities() throws Exception {
    Path skillRoot = Files.createTempDirectory("daemon-skills");
    Path skillDir = skillRoot.resolve("demo");
    Files.createDirectories(skillDir);
    String body = "---\nname: demo\ndescription: Demo skill\n---\n# Demo\n";
    Files.writeString(skillDir.resolve("SKILL.md"), body);
    try {
      FakeTransport transport = new FakeTransport();
      DaemonSkillRegistry skills = DaemonSkillRegistry.discover(List.of(skillRoot));
      runtime =
          runtime(
              transport,
              new TestCapability(),
              skills,
              Duration.ofMinutes(1),
              Duration.ofSeconds(10),
              null);

      runtime.start();
      transport.awaitConnections(1);
      completeHandshake(0);
      List<DaemonEnvelope> handshake = transport.takeMessages(2);
      assertMessageTypes(handshake, HELLO, READY);

      JsonNode payload = codec.readPayload(handshake.get(1));
      assertFalse(payload.has("tools"));
      assertEquals(DaemonCapabilities.VERSION, payload.path("version").asInt());
      assertTrue(payload.path("environment").path("workingDirectory").isMissingNode());
      assertTrue(payload.path("environment").path("note").isTextual());
      assertTrue(payload.path("environment").path("rootPath").isTextual());
      assertEquals(1, payload.path("skills").size());
      assertEquals("demo", payload.path("skills").get(0).path("name").asText());
      assertEquals("Demo skill", payload.path("skills").get(0).path("description").asText());
      assertTrue(payload.path("skills").get(0).path("path").isMissingNode());
      assertTrue(payload.path("skills").get(0).path("content").isMissingNode());
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
    completeHandshake(0);
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

  /** 上一代连接迟到的消息不得进入当前连接的 sequence 或 invocation 生命周期。 */
  @Test
  void ignoresLateMessageFromSupersededConnection() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    TestCapability tool = new TestCapability();
    runtime = runtime(transport, tool);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.disconnect();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);

    transport.receiveFromConnection(0, invoke("stale-invocation", 1));
    assertFalse(transport.hasMessages());
    assertEquals(0, tool.executions.get());

    transport.receive(invoke("current-invocation", 1));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
    assertEquals(1, tool.executions.get());
  }

  /** CANCEL 的即时 ACK 只允许回到接收该消息的连接，不能泄漏到重连后的连接。 */
  @Test
  void doesNotRouteStaleCancelAcknowledgementToReplacementConnection() throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestCapability());

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.disconnect();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);

    transport.receiveFromConnection(0, cancel("stale-cancel", 1));
    assertFalse(transport.hasMessages());

    transport.receive(cancel("current-cancel", 1));
    assertMessageTypes(transport.takeMessages(1), ACK);
  }

  /** stale INVOKE 响应及其协议 ERROR 均不得被发送到 replacement connection。 */
  @Test
  void doesNotRouteStaleInvokeOrMalformedInputResponsesToReplacementConnection()
      throws InterruptedException {
    FakeTransport transport = new FakeTransport();
    runtime = runtime(transport, new TestCapability(), DaemonSkillRegistry.empty());

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);
    transport.disconnect();
    transport.awaitConnections(1);
    completeHandshake(0);
    transport.takeMessages(2);

    transport.receiveFromConnection(0, invoke("stale-invoke", 1));
    transport.receiveRawFromConnection(
        0,
        "{\"protocolVersion\":"
            + DaemonProtocol.VERSION
            + ",\"messageType\":\"INVOKE\","
            + "\"environmentId\":\"11111111-1111-1111-1111-111111111111\",\"sequence\":2,\"payload\":{}}");
    assertFalse(transport.hasMessages());

    transport.receive(invoke("current-invoke", 1));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);
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
    completeHandshake(0);
    transport.takeMessages(2);

    transport.receive(
        new DaemonEnvelope(DaemonProtocol.VERSION, DaemonMessageType.HELLO, null, null, 1, "{}"));
    assertMessageTypes(transport.takeMessages(1), DaemonMessageType.ERROR);

    transport.receive(cancel("unknown-cancel", 1));
    assertMessageTypes(transport.takeMessages(1), ACK);

    transport.receive(
        new DaemonEnvelope(
            DaemonProtocol.VERSION,
            DaemonMessageType.INVOKE,
            ENVIRONMENT_ID,
            "invalid-invoke-payload",
            2,
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
    completeHandshake(0);
    transport.takeMessages(2);
    transport.receive(invoke("shutdown-invocation", 1));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);

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
                "test-registration-token",
                Duration.ofMinutes(1),
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                null,
                ENVIRONMENT_ROOT,
                List.of()),
            transport,
            registry,
            DaemonSkillRegistry.empty(),
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
                "test-registration-token",
                Duration.ofMillis(20),
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                null,
                ENVIRONMENT_ROOT,
                List.of()),
            transport,
            registry,
            DaemonSkillRegistry.empty(),
            new InMemoryDaemonInvocationJournal(),
            scheduler,
            taskExecutor);

    try {
      runtime.start();
      transport.awaitConnections(1);
      completeHandshake(0);
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
            DaemonSkillRegistry.empty(),
            Duration.ofMinutes(1),
            Duration.ofSeconds(10),
            null);

    runtime.start();
    transport.awaitConnections(1);
    completeHandshake(0);
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
    completeHandshake(0);
    transport.takeMessages(2);
    Thread invocationThread =
        new Thread(
            () -> transport.receive(invoke("shutdown-race", 1, "blocking")),
            "invoke-shutdown-race");
    invocationThread.start();
    assertTrue(tool.executionStarted.await(ASYNC_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    assertMessageTypes(transport.takeMessages(2), ACK, STARTED);

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
            "test-registration-token",
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            note,
            ENVIRONMENT_ROOT,
            List.of()),
        transport,
        registry,
        DaemonSkillRegistry.empty(),
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
            "test-registration-token",
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            null,
            environmentRoot,
            List.of()),
        transport,
        registry,
        DaemonSkillRegistry.empty(),
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
            "test-registration-token",
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            null,
            environmentRoot,
            List.of()),
        transport,
        registry,
        DaemonSkillRegistry.empty(),
        new InMemoryDaemonInvocationJournal(),
        Executors.newSingleThreadScheduledExecutor(),
        Executors.newVirtualThreadPerTaskExecutor(),
        new InMemoryResourceStore());
  }

  private DaemonRuntime runtime(
      FakeTransport transport,
      DaemonCapabilityRegistry registry,
      DaemonSkillRegistry skillRegistry) {
    handshakeTransport = transport;
    return new DaemonRuntime(
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            "test-registration-token",
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            null,
            ENVIRONMENT_ROOT,
            List.of()),
        transport,
        registry,
        skillRegistry,
        new InMemoryDaemonInvocationJournal(),
        Executors.newSingleThreadScheduledExecutor(),
        Executors.newVirtualThreadPerTaskExecutor(),
        new InMemoryResourceStore());
  }

  private DaemonRuntime runtime(
      FakeTransport transport, EnvironmentCapability capability, ResourceStore resourceStore) {
    return runtime(
        transport, capability, Duration.ofMinutes(1), Duration.ofSeconds(10), resourceStore);
  }

  private DaemonRuntime runtime(
      FakeTransport transport,
      EnvironmentCapability capability,
      InMemoryDaemonInvocationJournal journal,
      ResourceStore resourceStore) {
    handshakeTransport = transport;
    DaemonCapabilityRegistry registry = new DaemonCapabilityRegistry();
    registry.register(capability);
    return new DaemonRuntime(
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            "test-registration-token",
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            null,
            ENVIRONMENT_ROOT,
            List.of()),
        transport,
        registry,
        DaemonSkillRegistry.empty(),
        journal,
        Executors.newSingleThreadScheduledExecutor(),
        Executors.newVirtualThreadPerTaskExecutor(),
        resourceStore);
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
            "test-registration-token",
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            null,
            ENVIRONMENT_ROOT,
            List.of()),
        transport,
        registry,
        DaemonSkillRegistry.empty(),
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
            "test-registration-token",
            Duration.ofMinutes(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            null,
            ENVIRONMENT_ROOT,
            List.of()),
        transport,
        registry,
        DaemonSkillRegistry.empty(),
        new InMemoryDaemonInvocationJournal(),
        scheduler,
        Executors.newVirtualThreadPerTaskExecutor());
  }

  private DaemonRuntime runtime(
      FakeTransport transport,
      EnvironmentCapability capability,
      Duration heartbeatInterval,
      Duration defaultToolTimeout) {
    return runtime(transport, capability, heartbeatInterval, defaultToolTimeout, null);
  }

  private DaemonRuntime runtime(
      FakeTransport transport,
      EnvironmentCapability capability,
      Duration heartbeatInterval,
      Duration defaultToolTimeout,
      ResourceStore resourceStore) {
    return runtime(
        transport,
        capability,
        DaemonSkillRegistry.empty(),
        heartbeatInterval,
        defaultToolTimeout,
        resourceStore);
  }

  private DaemonRuntime runtime(
      FakeTransport transport,
      EnvironmentCapability capability,
      DaemonSkillRegistry skillRegistry) {
    return runtime(
        transport, capability, skillRegistry, Duration.ofMinutes(1), Duration.ofSeconds(10), null);
  }

  private DaemonRuntime runtime(
      FakeTransport transport,
      EnvironmentCapability capability,
      DaemonSkillRegistry skillRegistry,
      Duration heartbeatInterval,
      Duration defaultToolTimeout,
      ResourceStore resourceStore) {
    handshakeTransport = transport;
    DaemonCapabilityRegistry registry = new DaemonCapabilityRegistry();
    registry.register(capability);
    return new DaemonRuntime(
        new DaemonConfig(
            URI.create("ws://localhost/gateway"),
            "test-registration-token",
            heartbeatInterval,
            Duration.ZERO,
            Duration.ofSeconds(1),
            defaultToolTimeout,
            null,
            ENVIRONMENT_ROOT,
            List.of()),
        transport,
        registry,
        skillRegistry,
        new InMemoryDaemonInvocationJournal(),
        Executors.newSingleThreadScheduledExecutor(),
        Executors.newVirtualThreadPerTaskExecutor(),
        resourceStore);
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

  /**
   * 模拟 Cloud 完成 HELLO/WELCOME/READY 握手：发送 WELCOME 让 daemon 推进到 READY。后续入站 sequence 必须从 {@code
   * welcomeSequence + 1} 起严格递增。
   */
  private void completeHandshake(long welcomeSequence) throws InterruptedException {
    handshakeTransport.awaitNextMessageType(DaemonMessageType.HELLO);
    handshakeTransport.receive(platformMessage(DaemonMessageType.WELCOME, welcomeSequence));
  }

  private DaemonEnvelope invoke(String invocationId, long sequence) {
    return invoke(invocationId, sequence, "test");
  }

  private DaemonEnvelope invoke(String invocationId, long sequence, String capabilityId) {
    return invoke(invocationId, sequence, capabilityId, "1.0.0", 1000);
  }

  private DaemonEnvelope invoke(
      String invocationId,
      long sequence,
      String capabilityId,
      String capabilityVersion,
      long timeoutMillis) {
    return invoke(invocationId, sequence, capabilityId, capabilityVersion, timeoutMillis, "{}");
  }

  /** 严格 v2 INVOKE：外壳只有 capabilityId/capabilityVersion/arguments/timeoutMillis，目录只来自 arguments。 */
  private DaemonEnvelope invoke(
      String invocationId,
      long sequence,
      String capabilityId,
      String capabilityVersion,
      long timeoutMillis,
      String argumentsJson) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION,
        DaemonMessageType.INVOKE,
        ENVIRONMENT_ID,
        invocationId,
        sequence,
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
      String invocationId, long sequence, String capabilityId, String capabilityVersion) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION,
        DaemonMessageType.INVOKE,
        ENVIRONMENT_ID,
        invocationId,
        sequence,
        "{\"capabilityId\":\""
            + capabilityId
            + "\",\"capabilityVersion\":\""
            + capabilityVersion
            + "\",\"arguments\":{},\"timeoutMillis\":0}");
  }

  private DaemonEnvelope invoke(
      String invocationId,
      long sequence,
      EnvironmentCapabilityId capabilityId,
      String capabilityVersion,
      String argumentsJson) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION,
        DaemonMessageType.INVOKE,
        ENVIRONMENT_ID,
        invocationId,
        sequence,
        "{\"capabilityId\":\""
            + capabilityId.value()
            + "\",\"capabilityVersion\":\""
            + capabilityVersion
            + "\",\"arguments\":"
            + argumentsJson
            + ",\"timeoutMillis\":10000}");
  }

  private DaemonEnvelope platformMessage(DaemonMessageType messageType, long sequence) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION, messageType, ENVIRONMENT_ID, null, sequence, "{}");
  }

  private DaemonEnvelope cancel(String invocationId, long sequence) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION,
        DaemonMessageType.CANCEL,
        ENVIRONMENT_ID,
        invocationId,
        sequence,
        "{}");
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
