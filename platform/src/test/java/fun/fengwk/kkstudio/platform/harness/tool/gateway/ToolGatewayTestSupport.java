package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.CustomStateSnapshot;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
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
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.permission.BashSurfaceAnalyzer;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionRule;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolSuccess;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.platform.harness.contributor.ContributorBranchViewLoader;
import fun.fengwk.kkstudio.platform.harness.tool.HarnessToolCatalogAdapter;
import fun.fengwk.kkstudio.platform.harness.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.testing.TestEnvironments;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * {@link ToolExecutionGateway} 测试共享基座：可编程 Tool / Transport / ResourceStore / Listener 与请求 fixture。
 */
final class ToolGatewayTestSupport {

  static final EnvironmentId ENV_A = TestEnvironments.environmentId("env-1");
  static final EnvironmentId ENV_B = TestEnvironments.environmentId("env-2");
  static final UUID INVOCATION_ID = new UUID(0L, 42L);
  static final UUID THREAD_ID = new UUID(0L, 7L);
  static final UUID ASSISTANT_ENTRY_ID = new UUID(0L, 11L);
  static final int PROPOSED_ATTEMPT = 3;

  /** 测试默认的 ResourceStore 单对象上限（与生产默认一致）。 */
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

  static Tool dummyLoadSkillTool() {
    return new FakeTool(hostDescriptor("load_skill"));
  }

  static Tool dummyTaskTool() {
    return new FakeTool(hostDescriptor("task"));
  }

  static BuiltinHarnessContributor builtinContributor() {
    return new BuiltinHarnessContributor(dummyLoadSkillTool(), dummyTaskTool());
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
            false,
            null));
  }

  /** 真实 daemon capability 的 ENVIRONMENT 请求：绑定 {@code bash} 并路由到指定 canonical 环境。 */
  static ToolInvocationRequest environmentRequest(String callId, EnvironmentId environmentId) {
    HarnessCatalog catalog = defaultCatalog();
    ToolContribution bashContribution = catalog.findTool("bash").orElseThrow();
    ContributorBinding contributor =
        new ContributorBinding(
            bashContribution.id().contributorId().value(),
            bashContribution.id().localName(),
            List.of());
    return new ToolInvocationRequest(
        new ToolCall(callId, "bash", "{\"command\":\"ls\",\"workdir\":\"/home/dev\"}"),
        new ToolBinding(bashContribution.definition(), contributor, true, environmentId));
  }

  static ToolGateway.Execution execution(ToolInvocationRequest request) {
    return new ToolGateway.Execution(
        INVOCATION_ID, THREAD_ID, ASSISTANT_ENTRY_ID, PROPOSED_ATTEMPT, request);
  }

  static ToolExecutionGateway gateway(
      HarnessCatalog catalog,
      FakeTransport transport,
      FakeResourceStore store,
      ExecutorService executor) {
    return gateway(
        catalog, transport, store, executor, RESOURCE_MAX_BYTES, settings(PermissionAction.ALLOW));
  }

  static ToolExecutionGateway gateway(
      HarnessCatalog catalog,
      FakeTransport transport,
      FakeResourceStore store,
      ExecutorService executor,
      int resourceMaxBytes) {
    return gateway(
        catalog, transport, store, executor, resourceMaxBytes, settings(PermissionAction.ALLOW));
  }

  static ToolExecutionGateway gateway(
      HarnessCatalog catalog,
      FakeTransport transport,
      FakeResourceStore store,
      ExecutorService executor,
      ToolSettings settings) {
    return gateway(catalog, transport, store, executor, RESOURCE_MAX_BYTES, settings);
  }

  static ToolExecutionGateway gateway(
      HarnessCatalog catalog,
      FakeTransport transport,
      FakeResourceStore store,
      ExecutorService executor,
      int resourceMaxBytes,
      ToolSettings settings) {
    return gateway(
        catalog,
        transport,
        store,
        executor,
        resourceMaxBytes,
        settings,
        new ConcurrencyAdmission(Integer.MAX_VALUE));
  }

  static ToolExecutionGateway gateway(
      HarnessCatalog catalog,
      FakeTransport transport,
      FakeResourceStore store,
      ExecutorService executor,
      int resourceMaxBytes,
      ToolSettings settings,
      ConcurrencyAdmission admission) {
    return gateway(
        new HarnessToolCatalogAdapter(catalog),
        catalog,
        transport,
        store,
        executor,
        resourceMaxBytes,
        settings,
        admission);
  }

  static ToolExecutionGateway gateway(
      RuntimeToolCatalog toolCatalog,
      HarnessCatalog catalog,
      FakeTransport transport,
      FakeResourceStore store,
      ExecutorService executor,
      int resourceMaxBytes,
      ToolSettings settings,
      ConcurrencyAdmission admission) {
    return new ToolExecutionGateway(
        toolCatalog,
        catalog,
        DEFAULT_CONTRIBUTOR_BRANCH_LOADER,
        transport,
        new PermissionEvaluator(new ObjectMapper(), new BashSurfaceAnalyzer()),
        new FixedToolSettingsProvider(settings),
        store,
        resourceMaxBytes,
        executor,
        OVERLOAD_RETRY_DELAY,
        TEST_CLOCK,
        admission);
  }

  static ToolExecutionGateway gateway(
      HarnessCatalog catalog,
      FakeTransport transport,
      FakeResourceStore store,
      HarnessRuntimeProperties properties,
      ExecutorService executor,
      ToolSettings settings) {
    return new ToolExecutionGateway(
        new HarnessToolCatalogAdapter(catalog),
        catalog,
        DEFAULT_CONTRIBUTOR_BRANCH_LOADER,
        transport,
        new PermissionEvaluator(new ObjectMapper(), new BashSurfaceAnalyzer()),
        new FixedToolSettingsProvider(settings),
        store,
        RESOURCE_MAX_BYTES,
        executor,
        OVERLOAD_RETRY_DELAY,
        TEST_CLOCK,
        new ConcurrencyAdmission(Integer.MAX_VALUE));
  }

  static ToolSettings settings(PermissionAction action) {
    return new ToolSettings(Map.of("*", List.of(new PermissionRule("*", action))), false);
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

  /** 可编程 Tool：记录 execution request，按 handler 执行并返回可观察 handle。 */
  static final class FakeTool implements Tool {
    private final ToolDescriptor descriptor;
    final List<ToolExecutionRequest> requests = new CopyOnWriteArrayList<>();
    final List<FakeToolHandle> handles = new CopyOnWriteArrayList<>();
    volatile ToolExecutionHandler handler = (request, listener) -> {};
    volatile boolean returnNullHandle;

    FakeTool(ToolDescriptor descriptor) {
      this.descriptor = descriptor;
    }

    @Override
    public ToolDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public ToolExecutionHandle execute(
        ToolExecutionRequest request, ToolExecutionListener listener) {
      requests.add(request);
      handler.execute(request, listener);
      if (returnNullHandle) {
        return null;
      }
      FakeToolHandle handle = new FakeToolHandle();
      handles.add(handle);
      return handle;
    }
  }

  @FunctionalInterface
  interface ToolExecutionHandler {
    void execute(ToolExecutionRequest request, ToolExecutionListener listener);
  }

  static final class FakeToolHandle
      implements ToolExecutionHandle, EnvironmentCapabilityExecutionHandle {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public void cancel() {
      cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
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

  /** 内存 ResourceStore：记录 put 参数，可按需抛确定性 / IO 失败；reference 与 put 返回同一规范引用。 */
  static final class FakeResourceStore implements ResourceStore {
    final List<PutRecord> puts = new CopyOnWriteArrayList<>();
    final List<PutRecord> resources = new CopyOnWriteArrayList<>();
    volatile RuntimeException referenceFailure;
    volatile RuntimeException putFailure;
    volatile boolean mismatchReturnedRef;

    record PutRecord(String mediaType, String name, byte[] content) {}

    @Override
    public ResourceRef put(String mediaType, String name, byte[] content) {
      RuntimeException failure = putFailure;
      if (failure != null) {
        throw failure;
      }
      byte[] payload = content.clone();
      String sha = sha256(payload);
      PutRecord stored = new PutRecord(mediaType, name, payload);
      puts.add(stored);
      resources.add(stored);
      ResourceRef planned = reference(mediaType, name, payload.length, sha);
      if (mismatchReturnedRef) {
        return new ResourceRef(
            planned.uri() + "-mismatch",
            planned.mediaType(),
            planned.name(),
            planned.size(),
            planned.sha256());
      }
      return planned;
    }

    @Override
    public ResourceRef reference(String mediaType, String name, long size, String sha256) {
      RuntimeException failure = referenceFailure;
      if (failure != null) {
        throw failure;
      }
      return new ResourceRef("file:///resources/" + sha256, mediaType, name, size, sha256);
    }

    @Override
    public byte[] read(ResourceRef resource) {
      byte[] payload = null;
      for (PutRecord put : resources) {
        if (put.content().length == resource.size()
            && sha256(put.content()).equals(resource.sha256())) {
          payload = put.content();
        }
      }
      return payload == null ? null : payload.clone();
    }

    ResourceRef seed(String mediaType, String name, byte[] content) {
      byte[] payload = content.clone();
      resources.add(new PutRecord(mediaType, name, payload));
      return reference(mediaType, name, payload.length, sha256(payload));
    }
  }

  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  /** 记录全部 listener 事件的运行时 Listener 假件；{@code store} 由测试 helper 回填供断言。 */
  static final class RecordingListener implements ToolGateway.Listener {
    final List<Event> events = new CopyOnWriteArrayList<>();
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger maxConcurrency = new AtomicInteger();
    final AtomicInteger terminalInvocations = new AtomicInteger();

    volatile boolean throwOnPartial;
    volatile boolean throwOnSucceeded;
    volatile boolean throwOnFailed;
    volatile boolean throwOnCancelled;
    volatile boolean throwOnUnknown;
    FakeResourceStore store;

    sealed interface Event
        permits Event.Partial, Event.Succeeded, Event.Failed, Event.Cancelled, Event.Unknown {

      record Partial(ToolResult partial) implements Event {}

      record Succeeded(ToolResult result, ToolEffectBatch effects) implements Event {}

      record Failed(ToolGateway.Failure failure) implements Event {}

      record Cancelled(ToolInvocationError error) implements Event {}

      record Unknown(ToolInvocationError error) implements Event {}
    }

    @Override
    public void onPartial(ToolResult partial) {
      enter();
      try {
        if (throwOnPartial) {
          throw new IllegalStateException("partial projection failed");
        }
        events.add(new Event.Partial(partial));
      } finally {
        exit();
      }
    }

    @Override
    public void onSucceeded(ToolSuccess success) {
      terminalInvocations.incrementAndGet();
      enter();
      try {
        if (throwOnSucceeded) {
          throw new IllegalStateException("listener rejected succeeded");
        }
        events.add(new Event.Succeeded(success.result(), success.effects()));
      } finally {
        exit();
      }
    }

    @Override
    public void onFailed(ToolGateway.Failure failure) {
      terminalInvocations.incrementAndGet();
      enter();
      try {
        if (throwOnFailed) {
          throw new IllegalStateException("listener rejected failed");
        }
        events.add(new Event.Failed(failure));
      } finally {
        exit();
      }
    }

    @Override
    public void onCancelled(ToolInvocationError error) {
      terminalInvocations.incrementAndGet();
      enter();
      try {
        if (throwOnCancelled) {
          throw new IllegalStateException("listener rejected cancelled");
        }
        events.add(new Event.Cancelled(error));
      } finally {
        exit();
      }
    }

    @Override
    public void onUnknown(ToolInvocationError error) {
      terminalInvocations.incrementAndGet();
      enter();
      try {
        if (throwOnUnknown) {
          throw new IllegalStateException("listener rejected unknown");
        }
        events.add(new Event.Unknown(error));
      } finally {
        exit();
      }
    }

    private void enter() {
      int current = inFlight.incrementAndGet();
      maxConcurrency.accumulateAndGet(current, Math::max);
    }

    private void exit() {
      inFlight.decrementAndGet();
    }

    void awaitCount(int count) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (events.size() < count && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertEquals(count, events.size(), "expected " + count + " events but got: " + events);
    }
  }

  static final class DirectQueueExecutor extends AbstractExecutorService {
    final List<Runnable> queued = new CopyOnWriteArrayList<>();
    volatile boolean rejectSubmissions;
    volatile RuntimeException ambiguousException;

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public void execute(Runnable command) {
      if (rejectSubmissions) {
        throw new RejectedExecutionException("test executor rejected");
      }
      if (ambiguousException != null) {
        throw ambiguousException;
      }
      queued.add(command);
    }

    void drain() {
      List<Runnable> copy = new ArrayList<>(queued);
      queued.clear();
      for (Runnable task : copy) {
        task.run();
      }
    }
  }

  static final class ManualExecutor implements ExecutorService {
    final List<Runnable> tasks = new CopyOnWriteArrayList<>();
    volatile boolean reject;
    volatile RuntimeException executeFailure;
    private volatile boolean shutdown;

    @Override
    public void execute(Runnable command) {
      if (reject || shutdown) {
        throw new RejectedExecutionException("rejected");
      }
      RuntimeException failure = executeFailure;
      if (failure != null) {
        throw failure;
      }
      tasks.add(command);
    }

    void runAll() {
      for (Runnable task : List.copyOf(tasks)) {
        task.run();
      }
    }

    void runFirst() {
      tasks.get(0).run();
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.copyOf(tasks);
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

    @Override
    public <T> Future<T> submit(Callable<T> task) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Future<?> submit(Runnable task) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
        throws InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> List<Future<T>> invokeAll(
        Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
        throws InterruptedException, ExecutionException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      throw new UnsupportedOperationException();
    }
  }

  static final class InlineExecutor extends AbstractExecutorService {
    @Override
    public void execute(Runnable command) {
      command.run();
    }

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return false;
    }
  }

  static final class InterruptingExecutor extends AbstractExecutorService {
    @Override
    public void execute(Runnable command) {
      Thread thread = new Thread(command, "gateway-interrupting-executor");
      thread.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (thread.isAlive()
          && thread.getState() != Thread.State.WAITING
          && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      thread.interrupt();
      try {
        thread.join();
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return false;
    }
  }

  static final class ImmediateExecutor extends AbstractExecutorService {
    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public void execute(Runnable command) {
      command.run();
    }
  }

  static final class UnsafeRejectingExecutor extends AbstractExecutorService {
    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public void execute(Runnable command) {
      throw new RejectedExecutionException("rejected");
    }
  }
}
