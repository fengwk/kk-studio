package fun.fengwk.kkstudio.core.ai.runtime.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.core.ai.runtime.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.core.ai.runtime.plugin.PluginBranchViewLoader;
import fun.fengwk.kkstudio.core.testing.TestEnvironmentBindings;
import fun.fengwk.kkstudio.harness.plugin.PluginCatalog;
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
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ResourceRef;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolBusyException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolSendUncertainException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolTransport;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolUnavailableException;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
 * {@link CoreToolGateway} 测试共享基座：可编程 Tool / Transport / ResourceStore / Listener 与请求 fixture。
 *
 * <p>PLATFORM fixture 使用虚构 tool {@code demo}（绕过 bash 的 permission 分析路径）；ENVIRONMENT fixture 使用
 * {@link EnvironmentToolCatalog} 的真实 {@code bash} descriptor（capability 校验需要与 catalog 精确相等）。
 */
final class ToolGatewayTestSupport {

  static final Path WORKDIR = Path.of("/workspace").toAbsolutePath().normalize();
  static final Path ENVIRONMENT_ROOT = Path.of("/environment-root").toAbsolutePath().normalize();
  static final EnvironmentBinding ENV_A = TestEnvironmentBindings.binding("env-1");
  static final EnvironmentBinding ENV_B = TestEnvironmentBindings.binding("env-2");
  static final UUID INVOCATION_ID = new UUID(0L, 42L);
  static final UUID THREAD_ID = new UUID(0L, 7L);
  static final UUID ASSISTANT_ENTRY_ID = new UUID(0L, 11L);
  static final int PROPOSED_ATTEMPT = 3;

  /** 测试默认的 ResourceStore 单对象上限（与生产默认一致）。 */
  static final int RESOURCE_MAX_BYTES = 16 * 1024 * 1024;

  /** 测试默认空 PluginCatalog：非插件路径的 gateway 测试不装配插件。 */
  static final PluginCatalog EMPTY_PLUGIN_CATALOG = PluginCatalog.from(List.of());

  /** 测试默认明确失败的 PluginBranchViewLoader：任何插件路径都会暴露未装配。 */
  static final PluginBranchViewLoader FAILING_PLUGIN_BRANCH_LOADER =
      assistantEntryId -> {
        throw new IllegalStateException("no plugin branch loader is configured");
      };

  /** 固定 busy/overload 延迟 supplier：语义与生产一致——每次 Busy/Overloaded 判定现读。 */
  static final Supplier<Duration> BUSY_RETRY_DELAY = () -> Duration.ofSeconds(2);

  static final Supplier<Duration> OVERLOAD_RETRY_DELAY = () -> Duration.ofSeconds(7);

  static final Clock TEST_CLOCK = Clock.systemUTC();

  private ToolGatewayTestSupport() {}

  static ToolDescriptor platformDescriptor(String name) {
    return new ToolDescriptor(
        name,
        "1",
        ToolType.PLATFORM,
        "description of " + name,
        name,
        new ToolParamsSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofMinutes(1));
  }

  static ToolInvocationRequest platformRequest(String callId, ToolDescriptor descriptor) {
    return new ToolInvocationRequest(
        new ToolCall(callId, descriptor.name(), "{}"),
        new ToolBinding(descriptor, ToolType.PLATFORM, null));
  }

  /** 真实 daemon capability 的 ENVIRONMENT 请求：绑定 {@code bash} 并路由到指定 canonical 环境。 */
  static ToolInvocationRequest environmentRequest(String callId, EnvironmentBinding environment) {
    ToolDescriptor descriptor = EnvironmentToolCatalog.require("bash");
    return new ToolInvocationRequest(
        new ToolCall(callId, "bash", "{\"command\":\"ls\"}"),
        new ToolBinding(descriptor, ToolType.ENVIRONMENT, environment));
  }

  static ToolGateway.Execution execution(ToolInvocationRequest request) {
    return new ToolGateway.Execution(
        INVOCATION_ID, THREAD_ID, ASSISTANT_ENTRY_ID, PROPOSED_ATTEMPT, request);
  }

  static ToolFactories factories(Tool... tools) {
    List<ToolFactory> factories = new ArrayList<>(tools.length);
    for (Tool tool : tools) {
      factories.add(ToolFactory.singleton(tool));
    }
    return new ToolFactories(factories);
  }

  static CoreToolGateway gateway(
      ToolFactories toolFactories,
      FakeTransport transport,
      FakeResourceStore store,
      ExecutorService executor) {
    return gateway(
        toolFactories,
        transport,
        store,
        executor,
        RESOURCE_MAX_BYTES,
        settings(PermissionAction.ALLOW));
  }

  static CoreToolGateway gateway(
      ToolFactories toolFactories,
      FakeTransport transport,
      FakeResourceStore store,
      ExecutorService executor,
      int resourceMaxBytes) {
    return gateway(
        toolFactories,
        transport,
        store,
        executor,
        resourceMaxBytes,
        settings(PermissionAction.ALLOW));
  }

  static CoreToolGateway gateway(
      ToolFactories toolFactories,
      FakeTransport transport,
      FakeResourceStore store,
      ExecutorService executor,
      ToolSettings settings) {
    return gateway(toolFactories, transport, store, executor, RESOURCE_MAX_BYTES, settings);
  }

  static CoreToolGateway gateway(
      ToolFactories toolFactories,
      FakeTransport transport,
      FakeResourceStore store,
      ExecutorService executor,
      int resourceMaxBytes,
      ToolSettings settings) {
    return new CoreToolGateway(
        toolFactories,
        EMPTY_PLUGIN_CATALOG,
        FAILING_PLUGIN_BRANCH_LOADER,
        transport,
        new PermissionEvaluator(new ObjectMapper(), new BashSurfaceAnalyzer()),
        new FixedToolSettingsProvider(settings),
        store,
        WORKDIR,
        ENVIRONMENT_ROOT,
        resourceMaxBytes,
        executor,
        BUSY_RETRY_DELAY,
        OVERLOAD_RETRY_DELAY,
        TEST_CLOCK);
  }

  static CoreToolGateway gateway(
      ToolFactories toolFactories,
      FakeTransport transport,
      FakeResourceStore store,
      ExecutorService executor,
      ToolSettings settings,
      Path workdir,
      Path environmentRoot) {
    return new CoreToolGateway(
        toolFactories,
        EMPTY_PLUGIN_CATALOG,
        FAILING_PLUGIN_BRANCH_LOADER,
        transport,
        new PermissionEvaluator(new ObjectMapper(), new BashSurfaceAnalyzer()),
        new FixedToolSettingsProvider(settings),
        store,
        workdir,
        environmentRoot,
        RESOURCE_MAX_BYTES,
        executor,
        BUSY_RETRY_DELAY,
        OVERLOAD_RETRY_DELAY,
        TEST_CLOCK);
  }

  static CoreToolGateway gateway(
      ToolFactories toolFactories,
      FakeTransport transport,
      FakeResourceStore store,
      HarnessRuntimeProperties properties,
      ExecutorService executor,
      ToolSettings settings) {
    return new CoreToolGateway(
        toolFactories,
        EMPTY_PLUGIN_CATALOG,
        FAILING_PLUGIN_BRANCH_LOADER,
        transport,
        new PermissionEvaluator(new ObjectMapper(), new BashSurfaceAnalyzer()),
        new FixedToolSettingsProvider(settings),
        store,
        properties.resolvedWorkdir(),
        properties.resolvedEnvironmentRoot(),
        RESOURCE_MAX_BYTES,
        executor,
        BUSY_RETRY_DELAY,
        OVERLOAD_RETRY_DELAY,
        TEST_CLOCK);
  }

  static ToolSettings settings(PermissionAction action) {
    return new ToolSettings(Map.of("*", List.of(new PermissionRule("*", action))), false);
  }

  static ToolResult result(String callId, String text) {
    return new ToolResult(callId, List.of(new TextToolContent(text)), false, "{}");
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

  /** 可编程 Platform Tool：记录 execution request，按 handler 执行并返回可观察 handle。 */
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

  static final class FakeToolHandle implements ToolExecutionHandle {
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

  /** 可编程 RemoteToolTransport：记录路由目标，按 action 决定返回 / 同步回调 / 抛异常。 */
  static final class FakeTransport implements RemoteToolTransport {
    final List<InvokeRecord> invocations = new CopyOnWriteArrayList<>();
    final List<FakeToolHandle> returnedHandles = new CopyOnWriteArrayList<>();
    volatile InvokeAction action = InvokeAction.RETURN_HANDLE;
    volatile ToolResult syncResult;
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
        EnvironmentBinding environment,
        ToolExecutionRequest request,
        ToolExecutionListener listener) {}

    @Override
    public ToolExecutionHandle invoke(
        EnvironmentBinding environment,
        ToolExecutionRequest request,
        ToolExecutionListener listener) {
      invocations.add(new InvokeRecord(environment, request, listener));
      switch (action) {
        case SYNC_COMPLETE:
          listener.onComplete(syncResult);
          break;
        case SYNC_PARTIALS:
          for (int i = 0; i < syncPartialCount; i++) {
            listener.onPartial(
                new ToolResult(
                    "call-1", List.of(new TextToolContent("progress-" + i)), false, "{}"));
          }
          break;
        case THROW_BUSY:
          throw new RemoteToolBusyException("environment already active");
        case THROW_UNAVAILABLE:
          throw new RemoteToolUnavailableException("environment offline");
        case THROW_UNCERTAIN:
          throw new RemoteToolSendUncertainException("send uncertain");
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

    private FakeToolHandle newReturnedHandle() {
      FakeToolHandle handle = new FakeToolHandle();
      returnedHandles.add(handle);
      return handle;
    }
  }

  /** 内存 ResourceStore：记录 put 参数，可按需抛确定性 / IO 失败；reference 与 put 返回同一规范引用。 */
  static final class FakeResourceStore implements ResourceStore {
    final List<PutRecord> puts = new CopyOnWriteArrayList<>();
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
      puts.add(new PutRecord(mediaType, name, payload));
      ResourceRef planned = reference(mediaType, name, payload.length, sha);
      if (mismatchReturnedRef) {
        // 契约违反注入：put 返回与计划不一致的引用（已产生存储副作用）。
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
      for (PutRecord put : puts) {
        if (put.content().length == resource.size()
            && sha256(put.content()).equals(resource.sha256())) {
          payload = put.content();
        }
      }
      return payload == null ? null : payload.clone();
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

    /** 全部 terminal 回调（onSucceeded/onFailed/onCancelled/onUnknown）的总调用次数：即使抛异常也先计数。 */
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

    /** 轮询等待至少 {@code count} 个事件；超时以断言失败。 */
    void awaitCount(int count) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (events.size() < count && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertEquals(count, events.size(), "expected " + count + " events but got: " + events);
    }
  }

  /** 手动 executor：任务排队直到测试显式 runAll / runFirst；可切换为拒绝或抛出任意提交异常。 */
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

  /** 调用线程直接执行任务的 executor：用于验证构造时 inline executor 被拒绝。 */
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

  /**
   * 每个任务启动独立线程并阻塞 execute 直到任务线程进入 WAITING 后中断它；用于确定性触发 {@code awaitRelease} 的中断路径（任务在 gate release
   * 前被中断，必须中止且不触碰 Tool）。
   */
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
}
