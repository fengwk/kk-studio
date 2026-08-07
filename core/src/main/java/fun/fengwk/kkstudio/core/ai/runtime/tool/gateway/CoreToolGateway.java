package fun.fengwk.kkstudio.core.ai.runtime.tool.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;

import fun.fengwk.kkstudio.core.ai.runtime.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluationContext;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionPromptPreview;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolResultSizeLimits;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolCancelledException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolFailedException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolSendUncertainException;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolTransport;
import fun.fengwk.kkstudio.harness.tool.remote.RemoteToolUnavailableException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy;
import java.util.concurrent.ThreadPoolExecutor.DiscardPolicy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Production {@link ToolGateway}：冻结 Tool request 的权限 preflight 与 admission 路由。
 *
 * <p>{@link #preflight} 是纯判定：用 {@link PermissionEvaluator} + 部署 {@link ToolSettings} + 配置的
 * workdir/environmentRoot 评估冻结的 call/binding，YOLO 在加载 settings / evaluator 之前直接返回 Allow；绝不改写
 * binding/arguments。{@link #start} 按冻结 binding 的 {@link ToolType} 路由：PLATFORM 走 {@link
 * ToolFactories} 精确 name/version + descriptor equality 后提交注入的 {@link ExecutorService}
 * 执行；ENVIRONMENT 只按 {@code binding.environmentName()} 经 {@link RemoteToolTransport} 发送。missing
 * capability / 发送前目标不可用（离线/未 READY/心跳过期）都依据可证明的未接受映射 Rejected；本地 executor 拒绝映射
 * Overloaded（正整毫秒延迟）；发送不确定 / 提交结果不确定映射 Indeterminate。
 *
 * <p>回调桥（{@link GatedToolExecutionListener}）：两阶段激活——{@code start()} 绝不打开回调 gate（Tool 的同步回调只进缓冲），
 * {@link ToolGateway.Handle#activate()} 由 Processor 在 attach + durable markRunning 后调用，直接打开 gate +
 * 释放 PLATFORM 等待任务 + 串行重放缓冲（PLATFORM / ENVIRONMENT 同一路径，绝不提交独立重放任务）；重放与直接转发都绝不发生在 {@code start()}
 * 调用栈上，且整个桥是单线程串行 FIFO + terminal-once：迟到 / 重复信号一律忽略，缓冲有界（≤{@value #MAX_BUFFERED_SIGNALS}，
 * 溢出即清空缓冲并在激活/分发时确定性收敛恰好一次 UNKNOWN，绝不产生 resource 写入、绝不出现第二个 terminal），非法 partial 确定性 terminal
 * 失败并阻止后续任何 resource 写入。terminal 回调 fire-once：listener 在 onSucceeded / onFailed / onCancelled /
 * onUnknown 上抛异常只记录日志、绝不发出第二个 terminal 回调，只有非 terminal 的 onPartial 失败才会选择第一个 terminal
 * UNKNOWN。terminal success 通过 {@link ToolResultExternalizer} 做 managed Resource
 * 外部化（all-or-nothing）；partial 拒绝 Binary/Resource 且零存储 I/O。错误映射只把 {@link RemoteToolFailedException}
 * （daemon FAILED）当作非可重试已知失败；cancelled / unavailable（retryable=true）/ uncertain 保留显式分类，其余未分类错误 （含
 * Platform {@code tool.execute} 抛异常、onError(null)） 一律 UNKNOWN。等待任务在 release
 * 前被中断（而非取消）时队列一个未分类失败，activate 时恰好一次 UNKNOWN；cancel-before- activate 保持静默。activate
 * 抛异常即激活失败，Processor 收敛一次 UNKNOWN。
 *
 * <p>本类不查询 HarnessStore：持久线程所有权由 {@link ToolGateway.Execution} 的 {@code invocationId/threadId}
 * 提供。构造时拒绝 inline executor 与静默丢弃策略的 executor（inline executor 会使 admission gate 死锁，静默丢弃 会让 Started
 * 之后没有任何执行）。
 */
@Slf4j
public final class CoreToolGateway implements ToolGateway {

  static final String PERMISSION_DENIED_KIND = "PERMISSION_DENIED";
  static final String TOOL_NOT_FOUND_KIND = "TOOL_NOT_FOUND";
  static final String TOOL_DESCRIPTOR_MISMATCH_KIND = "TOOL_DESCRIPTOR_MISMATCH";
  static final String CANCELLED_KIND = "CANCELLED";
  static final String UNAVAILABLE_KIND = "UNAVAILABLE";
  static final String REMOTE_UNCERTAIN_KIND = "REMOTE_UNCERTAIN";
  static final String INVALID_REQUEST_KIND = "INVALID_REQUEST";
  static final String EXECUTION_FAILED_KIND = "EXECUTION_FAILED";
  static final String INVALID_PARTIAL_KIND = "INVALID_PARTIAL";

  /** Ask reason 的字符上限（ToolGateway.Ask 契约）。 */
  private static final int ASK_REASON_MAX_CHARACTERS = 1024;

  /** 回调桥缓冲队列的保守上限：gate 打开前的同步回调（adversarial invoke / execute）绝不能无界缓冲。 */
  static final int MAX_BUFFERED_SIGNALS = 256;

  private static final String PERMISSION_DENIED_MESSAGE = "Tool permission was denied.";

  private final ToolFactories toolFactories;
  private final RemoteToolTransport remoteTransport;
  private final PermissionEvaluator permissionEvaluator;
  private final ToolSettingsProvider toolSettingsProvider;
  private final ToolResultExternalizer externalizer;
  private final Path workdir;
  private final Path environmentRoot;
  private final ExecutorService executor;
  private final ToolGatewayConfig config;

  public CoreToolGateway(
      ToolFactories toolFactories,
      RemoteToolTransport remoteTransport,
      PermissionEvaluator permissionEvaluator,
      ToolSettingsProvider toolSettingsProvider,
      ResourceStore resourceStore,
      HarnessRuntimeProperties runtimeProperties,
      @Qualifier("toolGatewayExecutor") ExecutorService executor,
      ToolGatewayConfig config) {
    this(
        toolFactories,
        remoteTransport,
        permissionEvaluator,
        toolSettingsProvider,
        resourceStore,
        Objects.requireNonNull(runtimeProperties, "runtimeProperties").resolvedWorkdir(),
        runtimeProperties.resolvedEnvironmentRoot(),
        runtimeProperties.getResourceMaxBytes(),
        executor,
        config);
  }

  CoreToolGateway(
      ToolFactories toolFactories,
      RemoteToolTransport remoteTransport,
      PermissionEvaluator permissionEvaluator,
      ToolSettingsProvider toolSettingsProvider,
      ResourceStore resourceStore,
      Path workdir,
      Path environmentRoot,
      int resourceMaxBytes,
      ExecutorService executor,
      ToolGatewayConfig config) {
    this.toolFactories = Objects.requireNonNull(toolFactories, "toolFactories");
    this.remoteTransport = Objects.requireNonNull(remoteTransport, "remoteTransport");
    this.permissionEvaluator = Objects.requireNonNull(permissionEvaluator, "permissionEvaluator");
    this.toolSettingsProvider =
        Objects.requireNonNull(toolSettingsProvider, "toolSettingsProvider");
    this.externalizer =
        new ToolResultExternalizer(
            Objects.requireNonNull(resourceStore, "resourceStore"), resourceMaxBytes);
    this.workdir = Objects.requireNonNull(workdir, "workdir").toAbsolutePath().normalize();
    this.environmentRoot =
        Objects.requireNonNull(environmentRoot, "environmentRoot").toAbsolutePath().normalize();
    this.executor = Objects.requireNonNull(executor, "executor");
    this.config = Objects.requireNonNull(config, "config");
    rejectUnsafeExecutorPolicies(executor);
    rejectInlineExecutor(executor);
  }

  @Override
  public PreflightResult preflight(ToolInvocationRequest request, boolean yoloEnabled) {
    Objects.requireNonNull(request, "request");
    if (yoloEnabled) {
      // YOLO 覆盖一切权限判定：在加载 settings / evaluator 之前直接 Allow（两者可能依赖外部资源，YOLO 路径绝不触碰）。
      return new ToolGateway.Allow();
    }
    ToolSettings settings = toolSettingsProvider.get();
    PermissionEvaluator.Evaluation evaluation =
        permissionEvaluator.evaluate(
            new PermissionEvaluationContext(
                request.binding().descriptor().name(),
                request.call().argumentsJson(),
                workdir,
                environmentRoot,
                settings));
    PermissionAction action = evaluation.action();
    return switch (action) {
      case ALLOW -> new ToolGateway.Allow();
      case ASK -> new ToolGateway.Ask(reason(evaluation.promptPreview()));
      case DENY -> new ToolGateway.Deny(
          new ToolInvocationError(PERMISSION_DENIED_KIND, PERMISSION_DENIED_MESSAGE));
    };
  }

  @Override
  public StartResult start(Execution execution, Listener listener) {
    Objects.requireNonNull(execution, "execution");
    Objects.requireNonNull(listener, "listener");
    return switch (execution.request().binding().type()) {
      case PLATFORM -> startPlatform(execution, listener);
      case ENVIRONMENT -> startEnvironment(execution, listener);
    };
  }

  /** PLATFORM：ToolFactories 精确查找 + descriptor equality，然后提交 executor 执行（拒绝即 Overloaded）。 */
  private StartResult startPlatform(Execution execution, Listener listener) {
    ToolDescriptor bindingDescriptor = execution.request().binding().descriptor();
    Optional<Tool> found;
    try {
      found = toolFactories.find(bindingDescriptor.name(), bindingDescriptor.version());
    } catch (IllegalArgumentException invalid) {
      return new ToolGateway.Rejected(
          new ToolInvocationError(
              INVALID_REQUEST_KIND,
              failureMessage(invalid, "Frozen platform tool binding is invalid.")));
    }
    if (found.isEmpty()) {
      return new ToolGateway.Rejected(
          new ToolInvocationError(
              TOOL_NOT_FOUND_KIND,
              "Frozen tool "
                  + bindingDescriptor.name()
                  + "@"
                  + bindingDescriptor.version()
                  + " is not registered."));
    }
    Tool tool = found.orElseThrow();
    if (!bindingDescriptor.equals(tool.descriptor())) {
      return new ToolGateway.Rejected(
          new ToolInvocationError(
              TOOL_DESCRIPTOR_MISMATCH_KIND,
              "Registered tool "
                  + bindingDescriptor.name()
                  + "@"
                  + bindingDescriptor.version()
                  + " no longer matches its frozen descriptor."));
    }
    ToolExecutionRequest request = request(execution, tool.descriptor());
    GatedToolExecutionListener bridge =
        new GatedToolExecutionListener(
            listener, externalizer, tool.descriptor().name(), request.call().id());
    // 两阶段激活：executor 任务在 activate() 前只等待 release，绝不提前打开 gate / 触碰 Tool。
    GatewayHandle handle = new GatewayHandle(bridge::activate, bridge::cancel);
    try {
      executor.execute(
          () -> {
            if (!bridge.awaitRelease()) {
              // 等待期被中断（而非取消）：执行已被接受且可能已 RUNNING，绝不能静默消失——队列一个未分类失败，
              // activate 时恰好一次 UNKNOWN；cancel-before-activate 丢弃缓冲、保持静默。
              if (!bridge.isCancelled()) {
                bridge.onError(
                    new IllegalStateException(
                        "tool gateway transport task was interrupted before activation"));
              }
              return;
            }
            runTool(tool, request, bridge, handle);
          });
    } catch (RejectedExecutionException rejected) {
      // 肯定未接受：cancel 唤醒 broken executor 可能已启动的等待任务（它绝不触碰 Tool），Overloaded。
      bridge.cancel();
      log.warn(
          "tool gateway executor rejected platform execution for invocation {}",
          execution.invocationId());
      return new ToolGateway.Overloaded(config.overloadRetryDelay());
    } catch (RuntimeException ambiguous) {
      // 可能已启动：cancel 唤醒等待任务并使其中止，按 Indeterminate 收敛，绝不抛。
      bridge.cancel();
      log.warn(
          "tool gateway executor submission failed for invocation {}",
          execution.invocationId(),
          ambiguous);
      return new ToolGateway.Indeterminate(
          new ToolInvocationError(
              EXECUTION_FAILED_KIND,
              failureMessage(
                  ambiguous, "Tool execution submission failed; outcome cannot be confirmed.")));
    }
    return new ToolGateway.Started(handle);
  }

  /**
   * ENVIRONMENT：只按冻结 {@code binding.environmentName()} 路由。能力缺失 / descriptor 漂移 / 发送前目标不可用（离线、未
   * READY、心跳过期）都是确定性 Rejected；发送不确定 / 未知异常是 Indeterminate（可能已开始，绝不能抛）。
   */
  private StartResult startEnvironment(Execution execution, Listener listener) {
    ToolDescriptor bindingDescriptor = execution.request().binding().descriptor();
    if (execution.request().binding().environmentName() == null) {
      // 冻结 binding 没有 Environment route（分支最新 settings 未选中/被清空）：发送前确定性拒绝，
      // 绝不进入 transport（否则 null route 会变成不确定结果）。
      return new ToolGateway.Rejected(
          new ToolInvocationError(
              UNAVAILABLE_KIND,
              "Environment tool "
                  + bindingDescriptor.name()
                  + " has no environment route (the branch has no selected environment)."));
    }
    Optional<ToolDescriptor> capability =
        EnvironmentToolCatalog.find(bindingDescriptor.name(), bindingDescriptor.version());
    if (capability.isEmpty()) {
      return new ToolGateway.Rejected(
          new ToolInvocationError(
              TOOL_NOT_FOUND_KIND,
              "Environment tool "
                  + bindingDescriptor.name()
                  + "@"
                  + bindingDescriptor.version()
                  + " is not a daemon capability."));
    }
    if (!bindingDescriptor.equals(capability.orElseThrow())) {
      return new ToolGateway.Rejected(
          new ToolInvocationError(
              TOOL_DESCRIPTOR_MISMATCH_KIND,
              "Environment tool "
                  + bindingDescriptor.name()
                  + "@"
                  + bindingDescriptor.version()
                  + " no longer matches the daemon capability descriptor."));
    }
    ToolExecutionRequest request = request(execution, bindingDescriptor);
    GatedToolExecutionListener bridge =
        new GatedToolExecutionListener(
            listener, externalizer, bindingDescriptor.name(), request.call().id());
    // 两阶段激活：activate() 之前 gate 保持关闭（同步回调只进缓冲）；activate() 直接打开 gate 并串行重放（不提交独立重放任务）。
    GatewayHandle handle = new GatewayHandle(bridge::activate, bridge::cancel);
    ToolExecutionHandle transportHandle;
    try {
      transportHandle =
          remoteTransport.invoke(execution.request().binding().environmentName(), request, bridge);
    } catch (RemoteToolUnavailableException unavailable) {
      // 发送前目标不可用（路由缺失/未注册/未 READY/心跳过期）：肯定未开始，且当前分支配置下重试不会改变结论——
      // 确定性拒绝，让模型看到 durable 错误结果并继续收敛。
      return new ToolGateway.Rejected(
          new ToolInvocationError(
              UNAVAILABLE_KIND, failureMessage(unavailable, "Tool is unavailable.")));
    } catch (RemoteToolSendUncertainException uncertain) {
      // 发送不确定：可能已开始，绝不能抛。取消本地桥：迟到的 transport 回调被丢弃而不是永远缓冲。
      bridge.cancel();
      return new ToolGateway.Indeterminate(
          new ToolInvocationError(
              REMOTE_UNCERTAIN_KIND,
              failureMessage(
                  uncertain, "Remote tool send outcome is uncertain; result is unknown.")));
    } catch (IllegalArgumentException invalid) {
      return new ToolGateway.Rejected(
          new ToolInvocationError(
              INVALID_REQUEST_KIND, failureMessage(invalid, "Remote tool request is invalid.")));
    } catch (RuntimeException failure) {
      // 无法证明未接受：按 Indeterminate 收敛，绝不能抛。取消本地桥：迟到的 transport 回调被丢弃而不是永远缓冲。
      bridge.cancel();
      return new ToolGateway.Indeterminate(
          new ToolInvocationError(
              EXECUTION_FAILED_KIND,
              failureMessage(
                  failure, "Remote tool invocation failed; outcome cannot be confirmed.")));
    }
    if (transportHandle == null) {
      // 已接受的执行返回 null handle：队列一个未分类失败（先于它的同步 terminal 已按 FIFO/terminal-once 生效），
      // activate 时恰好一次 UNKNOWN。
      bridge.onError(
          new IllegalStateException(
              "remote tool returned a null execution handle; outcome cannot be confirmed"));
      return new ToolGateway.Started(handle);
    }
    handle.attach(transportHandle);
    return new ToolGateway.Started(handle);
  }

  private void runTool(
      Tool tool,
      ToolExecutionRequest request,
      GatedToolExecutionListener bridge,
      GatewayHandle handle) {
    ToolExecutionHandle toolHandle;
    try {
      toolHandle = tool.execute(request, bridge);
    } catch (RuntimeException failure) {
      bridge.onError(failure);
      return;
    }
    if (toolHandle == null) {
      // 已接受的执行返回 null handle：无法确认执行结果，队列一个未分类失败（先于它的同步 terminal 已按 FIFO/terminal-once
      // 生效），activate 时恰好一次 UNKNOWN。
      bridge.onError(
          new IllegalStateException(
              "tool returned a null execution handle; outcome cannot be confirmed"));
      return;
    }
    handle.attach(toolHandle);
  }

  private static ToolExecutionRequest request(Execution execution, ToolDescriptor descriptor) {
    return new ToolExecutionRequest(
        descriptor,
        execution.request().call(),
        Duration.ZERO,
        new ToolExecutionContext(execution.invocationId(), execution.threadId()));
  }

  /**
   * 从 bounded prompt preview 组合 canonical Ask reason（单行、无首尾空白、≤{@value #ASK_REASON_MAX_CHARACTERS}
   * 字符）。按 UTF-16 单位截断（Ask 契约按 {@link String#length()} 约束）， 若截断点落在代理对中间则回退一个单位，绝不产出孤立 surrogate。
   */
  private static String reason(PermissionPromptPreview preview) {
    String value =
        preview.tool() + " requires approval in " + preview.workdir() + ": " + preview.arguments();
    if (value.length() <= ASK_REASON_MAX_CHARACTERS) {
      return value;
    }
    int prefixChars = ASK_REASON_MAX_CHARACTERS - 3;
    if (Character.isHighSurrogate(value.charAt(prefixChars - 1))
        && Character.isLowSurrogate(value.charAt(prefixChars))) {
      prefixChars--;
    }
    return value.substring(0, prefixChars) + "...";
  }

  private static String failureMessage(Throwable error, String fallback) {
    String message = safeMessage(error);
    return message == null ? fallback : message;
  }

  /** 读取错误消息且绝不再抛（adversarial getMessage 只影响 UNKNOWN 的 detail，不影响收敛）。 */
  private static String safeMessage(Throwable error) {
    if (error == null) {
      return null;
    }
    try {
      String message = error.getMessage();
      return message == null || message.isBlank() ? null : message;
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  /** 拒绝 inline executor（admission gate 死锁）与静默丢弃策略（Started 后无执行）。 */
  private static void rejectUnsafeExecutorPolicies(ExecutorService executor) {
    if (executor instanceof ThreadPoolExecutor threadPool) {
      RejectedExecutionHandler handler = threadPool.getRejectedExecutionHandler();
      if (handler instanceof CallerRunsPolicy) {
        throw new IllegalStateException(
            "tool gateway requires a non-inline executor; CallerRunsPolicy is not supported");
      }
      if (handler instanceof DiscardPolicy || handler instanceof DiscardOldestPolicy) {
        throw new IllegalStateException(
            "tool gateway requires a rejecting executor; silent discard policies are not supported");
      }
    }
  }

  /** 探测并拒绝直接 inline executor；异步 executor 的任务必然在别的线程运行，不会被误判。 */
  private static void rejectInlineExecutor(ExecutorService executor) {
    Thread caller = Thread.currentThread();
    AtomicReference<Thread> runner = new AtomicReference<>();
    try {
      executor.execute(() -> runner.set(Thread.currentThread()));
    } catch (RuntimeException ignored) {
      // 拒绝型 / 已损坏的 executor 不可能 inline 运行任务；失败由 start() 的 admission 分类处理。
      return;
    }
    if (runner.get() == caller) {
      throw new IllegalStateException("tool gateway requires a non-inline executor");
    }
  }

  /**
   * 本地取消 / 激活控制：包装 Tool 的 execution handle；handle 尚未返回时记录取消意图，返回后立即取消；幂等且 best effort。 activate /
   * cancel 都原子且幂等：CAS 标志保证 cancel 已先到则 activate 绝不再打开投递（不会重新投递或产生 resource 写入）， 二者在桥 monitor 内与
   * {@link GatedToolExecutionListener#activate()} / {@code cancel()} 互斥。
   */
  private static final class GatewayHandle implements ToolGateway.Handle {
    private final AtomicBoolean activated = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<ToolExecutionHandle> toolHandle = new AtomicReference<>();
    private final Runnable activation;
    private final Runnable abort;

    GatewayHandle(Runnable activation, Runnable abort) {
      this.activation = Objects.requireNonNull(activation, "activation");
      this.abort = Objects.requireNonNull(abort, "abort");
    }

    void attach(ToolExecutionHandle handle) {
      if (handle == null) {
        return;
      }
      toolHandle.set(handle);
      if (cancelled.get()) {
        handle.cancel();
      }
    }

    /**
     * 两阶段激活：直接打开桥 gate（释放 PLATFORM 等待任务 + 串行重放缓冲回调），不提交任何独立任务。幂等；cancel 已先到则 no-op（桥 monitor 内
     * cancelled/open 原子互斥，绝不可能重新打开投递）。
     */
    @Override
    public void activate() {
      if (activated.compareAndSet(false, true) && !cancelled.get()) {
        activation.run();
      }
    }

    @Override
    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        // 与 activate 在桥 monitor 内原子互斥：cancel 先到则 activate no-op；activate 先到则此后所有信号被丢弃。
        abort.run();
        ToolExecutionHandle handle = toolHandle.get();
        if (handle != null) {
          handle.cancel();
        }
      }
    }
  }

  /**
   * Tool 回调门控桥：gate 打开前到达的回调（adversarial 同步回调）先缓冲（有界，≤{@value #MAX_BUFFERED_SIGNALS}，溢出即清空缓冲并
   * 确定性收敛恰好一次 UNKNOWN），gate 打开后由单一分发循环按到达顺序串行处理；迟到 / 重复信号（terminal 之后或 cancel 之后）一律忽略。
   * terminal-once：terminal 信号（success / error / 非法 partial 的确定性失败）一经处理，队列中与后续所有信号全部丢弃，阻止后续任何
   * resource 写入；terminal 回调 fire-once——listener 在 onSucceeded / onFailed / onCancelled / onUnknown
   * 上抛异常只记录， 绝不发出第二个 terminal 回调，只有非 terminal 的 onPartial 失败才会选择第一个 terminal UNKNOWN。
   *
   * <p>两阶段激活：{@code start()} 绝不打开 gate（同步回调只进缓冲）；{@link #activate()} 由 {@code handle.activate()}
   * 直接调用（Processor 在 attach + durable markRunning 之后、start() 已返回），原子地打开 gate + 释放等待的 executor
   * 任务（PLATFORM 路径，之后任务才运行 Tool）+ 串行重放缓冲——不再提交任何独立重放任务，PLATFORM / ENVIRONMENT 行为一致。 重放与直接转发都绝不发生在
   * {@code start()} 的调用栈上。等待任务在 release 前被中断（而非取消）时队列一个未分类失败，activate 时恰好一次
   * UNKNOWN（绝不静默消失）；cancel-before-activate 则丢弃缓冲并中止任务。partial 先做与 Runtime 完全一致的校验（非空 / toolCallId
   * 精确匹配 / 拒绝 Binary+Resource / canonical JSON ≤ 256 KiB），terminal 在外部化前校验非空 / toolCallId
   * 精确匹配，且外部化计划验证「外部化后归一投影」的 canonical JSON ≤ 1 MiB——任何确定性拒绝都在零存储副作用下发生；terminal success 先做
   * all-or-nothing 外部化（每次 put 核对计划引用）再投递；Tool / transport 返回 null execution handle 是
   * 已接受执行上的未分类失败（UNKNOWN，先于它的同步 terminal 按 FIFO/terminal-once 生效）；onError(null) 与分发循环内任何 意外
   * RuntimeException 都收敛恰好一次 UNKNOWN，绝不让 dispatching 卡死。
   */
  private static final class GatedToolExecutionListener implements ToolExecutionListener {
    private final ToolGateway.Listener listener;
    private final ToolResultExternalizer externalizer;
    private final String toolName;
    private final String expectedCallId;
    private final Object monitor = new Object();
    private final ArrayDeque<Signal> queue = new ArrayDeque<>();
    private boolean released;
    private boolean cancelled;
    private boolean open;
    private boolean dispatching;
    private boolean terminal;
    private boolean overflowed;

    GatedToolExecutionListener(
        ToolGateway.Listener listener,
        ToolResultExternalizer externalizer,
        String toolName,
        String expectedCallId) {
      this.listener = Objects.requireNonNull(listener, "listener");
      this.externalizer = Objects.requireNonNull(externalizer, "externalizer");
      this.toolName = Objects.requireNonNull(toolName, "toolName");
      this.expectedCallId = Objects.requireNonNull(expectedCallId, "expectedCallId");
    }

    /**
     * {@code handle.activate()} 直接调用：打开 gate + 释放等待的 executor 任务 + 串行重放缓冲。幂等；cancel / terminal 已先到则
     * no-op（与 {@link #cancel()} 在 monitor 内原子互斥，cancel 先到则绝不可能重新打开投递）。
     */
    void activate() {
      boolean dispatch = false;
      synchronized (monitor) {
        if (open || cancelled || terminal) {
          return;
        }
        open = true;
        released = true;
        monitor.notifyAll();
        if (!dispatching) {
          dispatching = true;
          dispatch = true;
        }
      }
      if (dispatch) {
        dispatchLoop();
      }
    }

    boolean isCancelled() {
      synchronized (monitor) {
        return cancelled;
      }
    }

    /** cancel-before-activate：唤醒等待的 executor 任务使其中止（绝不触碰 Tool、绝不投递回调），丢弃缓冲信号。 */
    void cancel() {
      synchronized (monitor) {
        cancelled = true;
        queue.clear();
        monitor.notifyAll();
      }
    }

    /**
     * executor 任务在触碰 Tool 前等待 {@link #activate()} 的 release。
     *
     * @return false 表示等待期间被中断或已 cancel：被取消的任务必须静默中止；被中断的任务必须队列一个未分类失败（激活时恰好一次 UNKNOWN），绝不静默消失、绝不触碰
     *     Tool
     */
    boolean awaitRelease() {
      synchronized (monitor) {
        while (!released && !cancelled) {
          try {
            monitor.wait();
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
          }
        }
        return released && !cancelled;
      }
    }

    @Override
    public void onPartial(ToolResult partial) {
      deliver(new Signal(SignalKind.PARTIAL, partial, null, null));
    }

    @Override
    public void onComplete(ToolResult result) {
      deliver(new Signal(SignalKind.COMPLETE, null, result, null));
    }

    @Override
    public void onError(Throwable error) {
      deliver(new Signal(SignalKind.ERROR, null, null, error));
    }

    private void deliver(Signal signal) {
      boolean dispatch = false;
      synchronized (monitor) {
        if (terminal || cancelled) {
          // 迟到 / 重复信号：terminal 或 cancel 之后一律忽略。
          return;
        }
        if (overflowed) {
          // 溢出已确定性选定 UNKNOWN：后续信号一律丢弃。
          return;
        }
        if (queue.size() >= MAX_BUFFERED_SIGNALS) {
          // 保守上界：缓冲无界增长时结果已不可信——清空缓冲并标记溢出，下一次分发（activate / 当前循环）
          // 确定性选择恰好一次 UNKNOWN；绝不产生 resource 写入、绝不出现第二个 terminal。
          overflowed = true;
          queue.clear();
          if (open && !dispatching) {
            dispatching = true;
            dispatch = true;
          }
        } else {
          queue.add(signal);
          if (open && !dispatching) {
            dispatching = true;
            dispatch = true;
          }
        }
      }
      if (dispatch) {
        dispatchLoop();
      }
    }

    /** 单一分发循环：每次恰好一个线程处理，FIFO 顺序；terminal 信号处理后清空队列并关闭 gate；任何意外异常收敛恰好一次 UNKNOWN。 */
    private void dispatchLoop() {
      while (true) {
        Signal signal;
        synchronized (monitor) {
          signal = queue.poll();
          if (signal == null && !overflowed) {
            dispatching = false;
            return;
          }
        }
        boolean terminalSignal;
        if (signal == null) {
          // 缓冲溢出：确定性选择恰好一次 UNKNOWN（fire-once；无 store 写入、无第二个 terminal）。
          terminalSignal =
              unknown(
                  new IllegalStateException(
                      "tool callback buffer exceeded " + MAX_BUFFERED_SIGNALS + " signals"),
                  "tool callback buffer overflowed; outcome cannot be confirmed");
        } else {
          try {
            terminalSignal = process(signal);
          } catch (RuntimeException failure) {
            // 分发循环内意外异常（如 adversarial getMessage）：收敛恰好一次 UNKNOWN，绝不让 dispatching 卡死。
            terminalSignal =
                unknown(failure, "tool callback dispatch failed; outcome cannot be confirmed");
          }
        }
        if (terminalSignal) {
          synchronized (monitor) {
            terminal = true;
            queue.clear();
            dispatching = false;
          }
          return;
        }
      }
    }

    /**
     * @return true 表示该信号已使桥 terminal（后续信号一律忽略）。
     */
    private boolean process(Signal signal) {
      return switch (signal.kind) {
        case PARTIAL -> processPartial(signal.partial);
        case COMPLETE -> processComplete(signal.result);
        case ERROR -> processError(signal.error);
      };
    }

    /**
     * partial 校验与 Runtime {@code ToolExecution} 完全一致（非空、toolCallId 精确匹配、不得携带 Binary / Resource
     * content、canonical JSON ≤ {@value
     * ToolResultSizeLimits#MAX_PARTIAL_RESULT_UTF8_BYTES}）：任何违反都使桥确定性 terminal（INVALID_PARTIAL，非
     * retryable），清空后续信号并在任何 store 访问之前生效。
     */
    private boolean processPartial(ToolResult partial) {
      if (partial == null) {
        return fail(new ToolInvocationError(INVALID_PARTIAL_KIND, "partial must not be null"));
      }
      if (!partial.toolCallId().equals(expectedCallId)) {
        return fail(
            new ToolInvocationError(
                INVALID_PARTIAL_KIND, "partial toolCallId does not match the request call"));
      }
      for (ToolContent content : partial.contents()) {
        if (content instanceof BinaryToolContent || content instanceof ResourceToolContent) {
          return fail(
              new ToolInvocationError(
                  INVALID_PARTIAL_KIND, "partial must not carry binary or resource content"));
        }
      }
      if (ToolResultJsonCodec.exceedsEncodedUtf8Bytes(
          partial, ToolResultSizeLimits.MAX_PARTIAL_RESULT_UTF8_BYTES)) {
        return fail(
            new ToolInvocationError(
                INVALID_PARTIAL_KIND,
                "partial must not exceed "
                    + ToolResultSizeLimits.MAX_PARTIAL_RESULT_UTF8_BYTES
                    + " bytes of canonical tool result JSON"));
      }
      // 只有非 terminal 的 onPartial 失败才允许选择第一个 terminal UNKNOWN。
      return deliverPartialOrUnknown(() -> listener.onPartial(partial));
    }

    private boolean processComplete(ToolResult result) {
      if (result == null) {
        return fail(
            new ToolInvocationError(
                ToolResultExternalizer.INVALID_RESULT_KIND, "terminal result must not be null"));
      }
      if (!result.toolCallId().equals(expectedCallId)) {
        // 外部化前校验 toolCallId：确定性 INVALID_RESULT，任何 store 副作用之前。
        return fail(
            new ToolInvocationError(
                ToolResultExternalizer.INVALID_RESULT_KIND,
                "terminal result toolCallId does not match the request call"));
      }
      ToolResultExternalizer.Outcome outcome;
      try {
        outcome = externalizer.externalize(toolName, result);
      } catch (RuntimeException failure) {
        // 外部化内部意外失败：结果无法确认，UNKNOWN。
        return unknown(failure, "tool result externalization failed; outcome cannot be confirmed");
      }
      switch (outcome) {
        case ToolResultExternalizer.Outcome.Success success -> {
          // terminal 选择已经发生：listener 拒绝只记录，绝不发出第二个 terminal 回调。
          deliverTerminal(() -> listener.onSucceeded(success.result()));
          return true;
        }
        case ToolResultExternalizer.Outcome.Invalid invalid -> {
          return fail(invalid.error());
        }
        case ToolResultExternalizer.Outcome.StoreFailed storeFailed -> {
          deliverTerminal(() -> listener.onUnknown(storeFailed.error()));
          return true;
        }
      }
    }

    /**
     * 错误分类：只有 {@link RemoteToolFailedException}（daemon FAILED）是已确认的非可重试已知失败；cancelled /
     * unavailable（retryable=true）/ uncertain 保留显式分类；其余未分类错误（含 Platform {@code tool.execute} 抛异常）一律
     * UNKNOWN。任何错误信号都使桥 terminal。
     */
    private boolean processError(Throwable error) {
      if (error == null) {
        // onError(null)：无法确认执行结果，恰好一次 UNKNOWN。
        return unknown(null, "tool reported a null error; outcome cannot be confirmed");
      }
      if (error instanceof RemoteToolCancelledException cancelled) {
        deliverTerminal(
            () ->
                listener.onCancelled(
                    new ToolInvocationError(
                        CANCELLED_KIND, failureMessage(cancelled, "Tool execution cancelled."))));
        return true;
      }
      if (error instanceof RemoteToolFailedException failed) {
        deliverTerminal(
            () ->
                listener.onFailed(
                    new ToolGateway.Failure(
                        new ToolInvocationError(
                            EXECUTION_FAILED_KIND,
                            failureMessage(failed, "Tool execution failed.")),
                        false)));
        return true;
      }
      if (error instanceof RemoteToolSendUncertainException uncertain) {
        deliverTerminal(
            () ->
                listener.onUnknown(
                    new ToolInvocationError(
                        REMOTE_UNCERTAIN_KIND,
                        failureMessage(
                            uncertain,
                            "Remote tool outcome is uncertain; side effect result is unknown."))));
        return true;
      }
      if (error instanceof RemoteToolUnavailableException unavailable) {
        // 肯定未开始（发送前不可用）：重试安全，明确 retryable。
        deliverTerminal(
            () ->
                listener.onFailed(
                    new ToolGateway.Failure(
                        new ToolInvocationError(
                            UNAVAILABLE_KIND, failureMessage(unavailable, "Tool is unavailable.")),
                        true)));
        return true;
      }
      // 未分类错误：无法确认执行结果，UNKNOWN（绝不知情地当作已知 FAILED）。
      return unknown(error, "unclassified tool failure; outcome cannot be confirmed");
    }

    private boolean fail(ToolInvocationError error) {
      // terminal 选择已经发生：listener 拒绝只记录，绝不发出第二个 terminal 回调。
      deliverTerminal(() -> listener.onFailed(new ToolGateway.Failure(error, false)));
      return true;
    }

    /**
     * 投递一次非 terminal 回调（仅 partial）：listener 抛异常 ⇒ 已接受执行上的内部失败，选择第一个 terminal UNKNOWN （至多一次；该
     * UNKNOWN 的投递仍 fire-once）。除此之外任何 listener 异常都绝不产生第二个 terminal 回调。
     *
     * @return true 表示 listener 抛异常且已收敛为 UNKNOWN terminal
     */
    private boolean deliverPartialOrUnknown(Runnable callback) {
      try {
        callback.run();
        return false;
      } catch (RuntimeException failure) {
        return unknown(
            failure, "listener failed to process tool callback; outcome cannot be confirmed");
      }
    }

    /**
     * 投递 terminal 回调（fire-once）：恰好调用一次。listener 抛异常只记录日志，绝不发出第二个 terminal 回调—— terminal
     * 选择已经发生，结果（Succeeded / Failed / Cancelled / Unknown）是确定的，不得用 UNKNOWN 覆盖。
     */
    private void deliverTerminal(Runnable callback) {
      try {
        callback.run();
      } catch (RuntimeException failure) {
        // terminal 已 fire-once；日志本身也必须 best effort，恶意 Throwable 渲染不得逃逸到
        // dispatchLoop 后触发第二个 UNKNOWN。
        try {
          log.warn(
              "tool gateway listener threw while accepting a terminal callback for {}: {}; "
                  + "no further terminal will be issued",
              listener.getClass().getSimpleName(),
              safeMessage(failure));
        } catch (RuntimeException ignored) {
          // 状态已 terminal；日志失败不得改变协议结果。
        }
      }
    }

    private boolean unknown(Throwable failure, String reason) {
      String detail = safeMessage(failure);
      ToolInvocationError error =
          new ToolInvocationError(
              EXECUTION_FAILED_KIND, detail == null ? reason : reason + ": " + detail);
      deliverTerminal(() -> listener.onUnknown(error));
      return true;
    }
  }

  /** 桥队列中的一次回调信号（FIFO 顺序即投递顺序）。 */
  private enum SignalKind {
    PARTIAL,
    COMPLETE,
    ERROR
  }

  private record Signal(SignalKind kind, ToolResult partial, ToolResult result, Throwable error) {}
}
