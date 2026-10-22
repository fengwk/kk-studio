package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.builtin.environment.ReadTool;
import fun.fengwk.kkstudio.harness.builtin.environment.ReadToolExecutor;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.CustomStateSnapshot;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.GoalSnapshot;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.permission.BashSurfaceAnalyzer;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionRule;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.catalog.tool.TestRuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.harness.contributor.ContributorBranchViewLoader;
import fun.fengwk.kkstudio.platform.testing.TestEnvironments;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link ToolExecutionGateway} 测试共享基座：可编程 Tool / Transport / ResourceStager / Listener 与请求 fixture。
 */
final class ToolGatewayTestSupport {

  static final EnvironmentId ENV_A = TestEnvironments.environmentId("env-1");
  static final EnvironmentId ENV_B = TestEnvironments.environmentId("env-2");
  static final UUID INVOCATION_ID = new UUID(0L, 42L);
  static final UUID THREAD_ID = new UUID(0L, 7L);
  static final UUID ASSISTANT_ENTRY_ID = new UUID(0L, 11L);

  /** 测试默认的工具资源单对象上限（与生产默认一致）。 */
  static final int RESOURCE_MAX_BYTES = 16 * 1024 * 1024;

  /** 测试默认的空 BranchView。 */
  static final BranchView EMPTY_BRANCH_VIEW =
      new BranchView() {
        @Override
        public List<CustomStateSnapshot> customEntries(String customType) {
          return List.of();
        }

        @Override
        public Optional<CustomStateSnapshot> latestCustomEntry(String customType) {
          return Optional.empty();
        }

        @Override
        public Optional<GoalSnapshot> goal() {
          return Optional.empty();
        }
      };

  /** 测试默认的 ContributorBranchViewLoader。 */
  static final ContributorBranchViewLoader DEFAULT_CONTRIBUTOR_BRANCH_LOADER =
      (assistantEntryId, contributorId) -> EMPTY_BRANCH_VIEW;

  /** 测试默认明确失败的 ContributorBranchViewLoader。 */
  static final ContributorBranchViewLoader FAILING_CONTRIBUTOR_BRANCH_LOADER =
      (assistantEntryId, contributorId) -> {
        throw new IllegalStateException("no contributor branch loader is configured");
      };

  /** 固定 busy/overload 延迟 supplier。 */
  static final Supplier<Duration> BUSY_RETRY_DELAY = () -> Duration.ofSeconds(2);

  static final Supplier<Duration> OVERLOAD_RETRY_DELAY = () -> Duration.ofSeconds(7);

  static final Clock TEST_CLOCK = Clock.systemUTC();

  private ToolGatewayTestSupport() {}

  static ReadTool dummyReadTool() {
    return new ReadTool(mock(ReadToolExecutor.class));
  }

  static Tool dummyTaskTool() {
    return new FakeTool(hostDescriptor("task"));
  }

  static BuiltinHarnessContributor builtinContributor() {
    return new BuiltinHarnessContributor(dummyReadTool(), dummyTaskTool());
  }

  static HarnessCatalog defaultCatalog(Tool... tools) {
    List<HarnessContributor> list = new ArrayList<>();
    list.add(builtinContributor());
    if (tools.length > 0) {
      list.add(
          HarnessContributor.of(
              new ContributorDescriptor(new ContributorId("test"), "Test", "1", Set.of()),
              registrar -> {
                for (int i = 0; i < tools.length; i++) {
                  Tool tool = tools[i];
                  registrar.registerTool(
                      "host-tool" + (i == 0 ? "" : "-" + i), tool, ToolVisibility.SELECTABLE, 0);
                }
              }));
    }
    return HarnessCatalog.from(list);
  }

  static ToolDescriptor hostDescriptor(String name) {
    return new ToolDescriptor(
        name,
        "description of " + name,
        name,
        new InputSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(1));
  }

  static ToolInvocationRequest hostRequest(String callId, ToolDescriptor descriptor) {
    return new ToolInvocationRequest(
        new ToolCall(callId, descriptor.name(), "{}"),
        new ToolBinding(
            new AgentToolDefinition(descriptor, ToolVisibility.SELECTABLE),
            new ContributorBinding("test", "host-tool", List.of()),
            EnvironmentSupport.NONE,
            null,
            null));
  }

  /** 真实 daemon capability 的 ENVIRONMENT 请求：绑定 {@code bash} 并路由到指定 canonical 环境。 */
  static ToolInvocationRequest environmentRequest(String callId, EnvironmentId environmentId) {
    return environmentRequest(
        callId, environmentId, "{\"command\":\"ls\",\"workdir\":\"/home/dev\"}");
  }

  /** 真实 daemon capability 的 ENVIRONMENT 请求，使用显式 arguments 验证 arguments 级超时解析。 */
  static ToolInvocationRequest environmentRequest(
      String callId, EnvironmentId environmentId, String argumentsJson) {
    HarnessCatalog catalog = defaultCatalog();
    ToolContribution bashContribution = catalog.findTool("bash").orElseThrow();
    ContributorBinding contributor =
        new ContributorBinding(
            bashContribution.id().contributorId().value(),
            bashContribution.id().localName(),
            List.of());
    return new ToolInvocationRequest(
        new ToolCall(callId, "bash", argumentsJson),
        new ToolBinding(
            bashContribution.definition(),
            contributor,
            EnvironmentSupport.REQUIRED,
            environmentId,
            "test-env"));
  }

  /** branch 未选择 Environment 的环境工具请求：environmentRequired=true 但路由身份为 null。 */
  static ToolInvocationRequest unselectedEnvironmentRequest(String callId) {
    HarnessCatalog catalog = defaultCatalog();
    ToolContribution bashContribution = catalog.findTool("bash").orElseThrow();
    ContributorBinding contributor =
        new ContributorBinding(
            bashContribution.id().contributorId().value(),
            bashContribution.id().localName(),
            List.of());
    ToolBinding binding = mock(ToolBinding.class);
    when(binding.definition()).thenReturn(bashContribution.definition());
    when(binding.descriptor()).thenReturn(bashContribution.definition().descriptor());
    when(binding.contributor()).thenReturn(contributor);
    when(binding.environmentSupport()).thenReturn(EnvironmentSupport.REQUIRED);
    when(binding.environmentId()).thenReturn(null);
    when(binding.environmentName()).thenReturn(null);
    return new ToolInvocationRequest(
        new ToolCall(callId, "bash", "{\"command\":\"ls\",\"workdir\":\"/home/dev\"}"), binding);
  }

  static ToolGateway.Execution execution(ToolInvocationRequest request) {
    return new ToolGateway.Execution(INVOCATION_ID, THREAD_ID, ASSISTANT_ENTRY_ID, request);
  }

  static ToolExecutionGateway gateway(
      HarnessCatalog catalog, FakeTransport transport, FakeResourceStager store) {
    return gateway(catalog, transport, store, RESOURCE_MAX_BYTES, settings(PermissionAction.ALLOW));
  }

  static ToolExecutionGateway gateway(
      HarnessCatalog catalog,
      FakeTransport transport,
      FakeResourceStager store,
      int resourceMaxBytes) {
    return gateway(catalog, transport, store, resourceMaxBytes, settings(PermissionAction.ALLOW));
  }

  static ToolExecutionGateway gateway(
      HarnessCatalog catalog,
      FakeTransport transport,
      FakeResourceStager store,
      ToolSettings settings) {
    return gateway(catalog, transport, store, RESOURCE_MAX_BYTES, settings);
  }

  static ToolExecutionGateway gateway(
      HarnessCatalog catalog,
      FakeTransport transport,
      FakeResourceStager store,
      int resourceMaxBytes,
      ToolSettings settings) {
    return gateway(
        catalog,
        transport,
        store,
        resourceMaxBytes,
        settings,
        new ConcurrencyAdmission(Integer.MAX_VALUE));
  }

  static ToolExecutionGateway gateway(
      HarnessCatalog catalog,
      FakeTransport transport,
      FakeResourceStager store,
      int resourceMaxBytes,
      ToolSettings settings,
      ConcurrencyAdmission admission) {
    return gateway(
        TestRuntimeToolCatalogSource.of(catalog),
        transport,
        store,
        resourceMaxBytes,
        settings,
        admission);
  }

  static ToolExecutionGateway gateway(
      HarnessCatalog catalog,
      ContributorBranchViewLoader branchLoader,
      FakeTransport transport,
      FakeResourceStager store,
      ConcurrencyAdmission admission) {
    return new ToolExecutionGateway(
        TestRuntimeToolCatalogSource.of(catalog),
        branchLoader,
        transport,
        new PermissionEvaluator(new BashSurfaceAnalyzer()),
        new FixedToolSettingsProvider(settings(PermissionAction.ALLOW)),
        store,
        RESOURCE_MAX_BYTES,
        OVERLOAD_RETRY_DELAY,
        TEST_CLOCK,
        admission);
  }

  static ToolExecutionGateway gateway(
      RuntimeToolCatalogSource toolCatalogSource,
      FakeTransport transport,
      FakeResourceStager store,
      int resourceMaxBytes,
      ToolSettings settings,
      ConcurrencyAdmission admission) {
    return new ToolExecutionGateway(
        toolCatalogSource,
        DEFAULT_CONTRIBUTOR_BRANCH_LOADER,
        transport,
        new PermissionEvaluator(new BashSurfaceAnalyzer()),
        new FixedToolSettingsProvider(settings),
        store,
        resourceMaxBytes,
        OVERLOAD_RETRY_DELAY,
        TEST_CLOCK,
        admission);
  }

  /** admit 并断言 Admitted，返回可执行的 run。 */
  static ToolGateway.Run admitted(ToolExecutionGateway gateway, ToolInvocationRequest request) {
    return assertInstanceOf(ToolGateway.Admitted.class, gateway.admit(execution(request))).run();
  }

  static ToolSettings settings(PermissionAction action) {
    return new ToolSettings(Map.of("*", List.of(new PermissionRule("*", action))));
  }

  static ToolResult result(String callId, String text) {
    return new ToolResult(callId, List.of(new TextResultContent(text)), false, "{}");
  }

  static EnvironmentCapabilityResult capabilityResult(String callId, String text) {
    return new EnvironmentCapabilityResult(
        callId, List.of(new TextResultContent(text)), false, "{}");
  }

  static final class FixedToolSettingsProvider implements ToolSettingsProvider {
    private final ToolSettings settings;

    FixedToolSettingsProvider(ToolSettings settings) {
      this.settings = settings;
    }

    @Override
    public ToolSettings get() {
      return settings;
    }
  }

  /** 记录读取次数的 ToolSettingsProvider：用于证明 preflight 短路时没有读取权限策略。 */
  static final class CountingToolSettingsProvider implements ToolSettingsProvider {
    private final ToolSettings settings;
    private final AtomicInteger reads = new AtomicInteger();

    CountingToolSettingsProvider(ToolSettings settings) {
      this.settings = settings;
    }

    @Override
    public ToolSettings get() {
      reads.incrementAndGet();
      return settings;
    }

    int readCount() {
      return reads.get();
    }
  }

  /** 可编程 Tool：记录 execution request，按 body 阻塞执行。 */
  static class FakeTool implements Tool {
    private final ToolDescriptor descriptor;
    final List<ToolExecutionRequest> requests = new CopyOnWriteArrayList<>();

    /** resolveTimeout 的调用次数：用于证明超时只在执行前解析一次。 */
    final AtomicInteger resolveTimeoutCalls = new AtomicInteger();

    /** 非 null 时作为 resolveTimeout 的结果，用于验证显式解析值与解析失败路径。 */
    volatile Duration resolvedTimeout;

    volatile ToolBody body = (request, progress) -> null;

    FakeTool(ToolDescriptor descriptor) {
      this.descriptor = descriptor;
    }

    @Override
    public ToolDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public Duration resolveTimeout(ToolCall call) {
      resolveTimeoutCalls.incrementAndGet();
      Duration explicit = resolvedTimeout;
      return explicit == null ? Tool.super.resolveTimeout(call) : explicit;
    }

    @Override
    public ToolOutcome execute(ToolExecutionRequest request, Consumer<ToolResult> progress)
        throws InterruptedException {
      requests.add(request);
      try {
        return body.execute(request, progress);
      } catch (InterruptedException | RuntimeException | Error failure) {
        throw failure;
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    }
  }

  @FunctionalInterface
  interface ToolBody {
    ToolOutcome execute(ToolExecutionRequest request, Consumer<ToolResult> progress)
        throws Exception;
  }

  /** Environment capability transport 的可观察句柄。 */
  static final class FakeToolHandle implements EnvironmentCapabilityExecutionHandle {
    private final AtomicInteger cancels = new AtomicInteger();

    @Override
    public void cancel() {
      cancels.incrementAndGet();
    }

    @Override
    public boolean isCancelled() {
      return cancels.get() > 0;
    }

    int cancelCount() {
      return cancels.get();
    }
  }

  /** 可编程 EnvironmentCapabilityTransport：记录路由目标，按 action 决定返回 / 同步回调 / 抛异常。 */
  static final class FakeTransport implements EnvironmentCapabilityTransport {
    final List<InvokeRecord> invocations = new CopyOnWriteArrayList<>();
    final List<FakeToolHandle> returnedHandles = new CopyOnWriteArrayList<>();
    volatile InvokeAction action = InvokeAction.RETURN_HANDLE;
    volatile ToolResult syncResult;
    volatile String callbackCallId;
    volatile boolean returnNullHandle;
    volatile int syncPartialCount;

    enum InvokeAction {
      RETURN_HANDLE,
      SYNC_COMPLETE,
      SYNC_PARTIALS,
      THROW_BUSY,
      THROW_UNAVAILABLE,
      THROW_UNCERTAIN,
      THROW_INVALID,
      THROW_GENERIC
    }

    record InvokeRecord(
        EnvironmentId environmentId,
        EnvironmentCapabilityExecutionRequest request,
        EnvironmentCapabilityExecutionListener listener) {}

    @Override
    public EnvironmentCapabilityExecutionHandle invoke(
        EnvironmentId environmentId,
        EnvironmentCapabilityExecutionRequest request,
        EnvironmentCapabilityExecutionListener listener) {
      invocations.add(new InvokeRecord(environmentId, request, listener));
      switch (action) {
        case SYNC_COMPLETE:
          listener.onComplete(
              toCapabilityResult(
                  callbackCallId == null ? request.call().id() : callbackCallId, syncResult));
          break;
        case SYNC_PARTIALS:
          for (int i = 0; i < syncPartialCount; i++) {
            listener.onPartial(
                new EnvironmentCapabilityResult(
                    callbackCallId == null ? request.call().id() : callbackCallId,
                    List.of(new TextResultContent("progress-" + i)),
                    false,
                    "{}"));
          }
          break;
        case THROW_BUSY:
          throw new EnvironmentCapabilityBusyException("environment already active");
        case THROW_UNAVAILABLE:
          throw new EnvironmentCapabilityUnavailableException("environment offline");
        case THROW_UNCERTAIN:
          throw new EnvironmentCapabilitySendUncertainException("send uncertain");
        case THROW_INVALID:
          throw new IllegalArgumentException("invalid request");
        case THROW_GENERIC:
          throw new IllegalStateException("transport broken");
        case RETURN_HANDLE:
        default:
          break;
      }
      return returnNullHandle ? null : newReturnedHandle();
    }

    private static EnvironmentCapabilityResult toCapabilityResult(
        String callId, ToolResult result) {
      if (result == null) {
        return null;
      }
      return new EnvironmentCapabilityResult(
          callId, result.contents(), result.error(), result.detailsJson());
    }

    private FakeToolHandle newReturnedHandle() {
      FakeToolHandle handle = new FakeToolHandle();
      returnedHandles.add(handle);
      return handle;
    }
  }

  /** 记录全部 staging 内容的 {@link ToolResourceStager} 假件；可注入失败或返回不一致的引用。 */
  static final class FakeResourceStager implements ToolResourceStager {
    final List<PutRecord> puts = new CopyOnWriteArrayList<>();
    final List<ResourceRef> discarded = new CopyOnWriteArrayList<>();
    volatile RuntimeException putFailure;
    volatile boolean mismatchReturnedRef;

    record PutRecord(String mediaType, String name, byte[] content) {}

    @Override
    public ResourceRef stage(String mediaType, String name, byte[] content) {
      RuntimeException failure = putFailure;
      if (failure != null) {
        throw failure;
      }
      byte[] payload = content.clone();
      puts.add(new PutRecord(mediaType, name, payload));
      return new ResourceRef(
          ResourceRef.blobUploadUri(UUID.randomUUID()),
          mediaType,
          name,
          mismatchReturnedRef ? payload.length + 1L : payload.length,
          sha256(payload));
    }

    @Override
    public void discard(ResourceRef resource) {
      discarded.add(resource);
    }
  }

  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
