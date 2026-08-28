package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.contributor.api.AppendCustomEntry;
import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.DeclarativeToolContext;
import fun.fengwk.kkstudio.harness.contributor.api.DeclarativeToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.DeclarativeToolResult;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentCapabilityToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HostToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.StateMode;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluationContext;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionPromptPreview;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolSuccess;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolResultSizeLimits;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCancelledException;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityFailedException;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.platform.harness.contributor.ContributorBranchViewLoader;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
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
import java.util.function.Supplier;

/**
 * Production {@link ToolGateway}：冻结 Tool request 的权限 preflight 与 admission 路由。
 *
 * <p>{@link #preflight} 是纯判定：先由 catalog 按冻结 definition id 恢复 ToolContribution/AgentToolId，再用 {@link
 * PermissionEvaluator} + 部署 {@link ToolSettings} + 配置的 workdir/environmentRoot 评估冻结的
 * call/binding；缺失 contribution 或 definition 漂移直接生成确定性 Deny，绝不进入 evaluator，也不改写
 * binding/arguments。YOLO 短路由由 Runtime 决定，本类不感知 YOLO 也不查询 HarnessStore。 {@link #start} 按冻结 binding 的
 * {@link AgentToolBackend} 路由：HOST 走 {@link HostToolContribution#tool()}，DECLARATIVE 走 {@link
 * DeclarativeToolContribution}，ENVIRONMENT_CAPABILITY 只按冻结的完整 binding 经 {@link
 * EnvironmentCapabilityTransport} 发送。missing capability / 发送前目标不可用（离线/未 READY/心跳过期）都依据可证明的未接受映射
 * Rejected；同 Environment 已有 active remote invocation 映射 Busy，由 Harness 按配置延迟重试并序列化 sibling；本地
 * executor 拒绝映射 Overloaded（正整毫秒延迟）；发送不确定 / 提交结果不确定映射 Indeterminate。
 *
 * <p>回调桥（{@link GatedToolExecutionListener}）：两阶段激活——{@code start()} 绝不打开回调 gate（Tool 的同步回调只进缓冲），
 * {@link ToolGateway.Handle#activate()} 由 Processor 在 attach + durable markRunning 后调用，直接打开 gate +
 * 释放 HOST 等待任务 + 串行重放缓冲（HOST / DECLARATIVE / ENVIRONMENT_CAPABILITY 同一路径，绝不提交独立重放任务）；重放与直接转发都绝不发生在
 * {@code start()} 调用栈上，且整个桥是单线程串行 FIFO + terminal-once：迟到 / 重复信号一律忽略，缓冲有界（≤{@value
 * #MAX_BUFFERED_SIGNALS}，溢出即清空缓冲并在激活/分发时确定性收敛恰好一次 UNKNOWN，绝不产生 resource 写入、绝不出现第二个 terminal），非法
 * partial 确定性 terminal 失败并阻止后续任何 resource 写入。terminal 回调 fire-once：listener 在 onSucceeded /
 * onFailed / onCancelled / onUnknown 上抛异常只记录日志、绝不发出第二个 terminal 回调，只有非 terminal 的 onPartial
 * 失败才会选择第一个 terminal UNKNOWN。terminal success 通过 {@link ToolResultExternalizer} 做 managed Resource
 * 外部化（all-or-nothing）；partial 拒绝 Binary/Resource 且零存储 I/O。错误映射只把 {@link
 * EnvironmentCapabilityFailedException} （daemon FAILED）当作非可重试已知失败；cancelled /
 * unavailable（retryable=true）/ uncertain 保留显式分类，其余未分类错误（含 HOST/DECLARATIVE {@code tool.execute}
 * 抛异常、onError(null)） 一律 UNKNOWN。等待任务在 release 前被中断（而非取消）时队列一个未分类失败，activate 时恰好一次
 * UNKNOWN；cancel-before-activate 保持静默。activate 抛异常即激活失败，Processor 收敛一次 UNKNOWN。
 *
 * <p>本类不查询 HarnessStore：持久线程所有权由 {@link ToolGateway.Execution} 的 {@code invocationId/threadId}
 * 提供。构造时拒绝 inline executor 与静默丢弃策略的 executor（inline executor 会使 admission gate 死锁，静默丢弃 会让 Started
 * 之后没有任何执行）。
 */
@Slf4j
public final class PlatformToolGateway implements ToolGateway {

  static final String PERMISSION_DENIED_KIND = "PERMISSION_DENIED";
  static final String TOOL_NOT_FOUND_KIND = "TOOL_NOT_FOUND";
  static final String TOOL_DEFINITION_MISMATCH_KIND = "TOOL_DEFINITION_MISMATCH";
  static final String CANCELLED_KIND = "CANCELLED";
  static final String UNAVAILABLE_KIND = "UNAVAILABLE";
  static final String REMOTE_UNCERTAIN_KIND = "REMOTE_UNCERTAIN";
  static final String INVALID_REQUEST_KIND = "INVALID_REQUEST";
  static final String EXECUTION_FAILED_KIND = "EXECUTION_FAILED";
  static final String INVALID_PARTIAL_KIND = "INVALID_PARTIAL";
  static final String CONTRIBUTOR_CONTRACT_VIOLATION_KIND = "CONTRIBUTOR_CONTRACT_VIOLATION";

  /** Ask reason 的字符上限（ToolGateway.Ask 契约）。 */
  private static final int ASK_REASON_MAX_CHARACTERS = 1024;

  /** 回调桥缓冲队列的保守上限：gate 打开前的同步回调（adversarial invoke / execute）绝不能无界缓冲。 */
  static final int MAX_BUFFERED_SIGNALS = 256;

  private static final String PERMISSION_DENIED_MESSAGE = "Tool permission was denied.";

  private final HarnessCatalog catalog;
  private final ContributorBranchViewLoader contributorBranchViewLoader;
  private final EnvironmentCapabilityTransport capabilityTransport;
  private final PermissionEvaluator permissionEvaluator;
  private final ToolSettingsProvider toolSettingsProvider;
  private final ToolResultExternalizer externalizer;
  private final Path workdir;
  private final Path environmentRoot;
  private final ExecutorService executor;
  private final Supplier<Duration> busyRetryDelay;
  private final Supplier<Duration> overloadRetryDelay;
  private final Clock clock;
  private final ConcurrencyAdmission admission;

  /**
   * 生产与测试共用的唯一构造器。{@code busyRetryDelay} / {@code overloadRetryDelay} 在每次 Busy / Overloaded 判定时现读，
   * 由装配方决定来源——生产装配传入 SystemSettingsSnapshot 的 live suppliers（每次 start 从 {@code
   * tool.toolGatewayBusyRetryMillis} / {@code tool.toolGatewayOverloadRetryMillis} 现读）。admission
   * 必须由装配方或测试显式提供， 不允许以无界容量绕过执行上限。
   */
  PlatformToolGateway(
      HarnessCatalog catalog,
      ContributorBranchViewLoader contributorBranchViewLoader,
      EnvironmentCapabilityTransport capabilityTransport,
      PermissionEvaluator permissionEvaluator,
      ToolSettingsProvider toolSettingsProvider,
      ResourceStore resourceStore,
      Path workdir,
      Path environmentRoot,
      int resourceMaxBytes,
      ExecutorService executor,
      Supplier<Duration> busyRetryDelay,
      Supplier<Duration> overloadRetryDelay,
      Clock clock,
      ConcurrencyAdmission admission) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.contributorBranchViewLoader =
        Objects.requireNonNull(contributorBranchViewLoader, "contributorBranchViewLoader");
    this.capabilityTransport = Objects.requireNonNull(capabilityTransport, "capabilityTransport");
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
    this.busyRetryDelay = Objects.requireNonNull(busyRetryDelay, "busyRetryDelay");
    this.overloadRetryDelay = Objects.requireNonNull(overloadRetryDelay, "overloadRetryDelay");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.admission = Objects.requireNonNull(admission, "admission");
    rejectUnsafeExecutorPolicies(executor);
    rejectInlineExecutor(executor);
  }

  private record ResolvedContribution(ToolContribution contribution, ToolInvocationError error) {}

  private ResolvedContribution validateContribution(ToolBinding binding) {
    AgentToolDefinition frozenDefinition = binding.definition();
    ToolContribution contribution = catalog.findTool(frozenDefinition.id()).orElse(null);
    if (contribution == null) {
      return new ResolvedContribution(
          null,
          new ToolInvocationError(
              TOOL_NOT_FOUND_KIND,
              "Frozen tool definition " + frozenDefinition.id() + " is not registered."));
    }
    if (!contribution.definition().equals(frozenDefinition)) {
      return new ResolvedContribution(
          null,
          new ToolInvocationError(
              TOOL_DEFINITION_MISMATCH_KIND,
              "Frozen tool definition "
                  + frozenDefinition.id()
                  + " does not match its catalog definition."));
    }
    ContributorBinding frozenContributor = binding.contributor();
    ContributionId expectedContributionId =
        new ContributionId(
            new ContributorId(frozenContributor.contributorId()), frozenContributor.localName());
    if (!contribution.id().equals(expectedContributionId)) {
      return new ResolvedContribution(
          null,
          new ToolInvocationError(
              TOOL_DEFINITION_MISMATCH_KIND,
              "Frozen contributor binding "
                  + frozenContributor.contributorId()
                  + "/"
                  + frozenContributor.localName()
                  + " does not match the catalog contribution."));
    }
    if (contribution.definition().backend() != frozenDefinition.backend()) {
      return new ResolvedContribution(
          null,
          new ToolInvocationError(
              TOOL_DEFINITION_MISMATCH_KIND,
              "Frozen tool definition backend "
                  + frozenDefinition.backend()
                  + " does not match the catalog contribution backend "
                  + contribution.definition().backend()));
    }
    if (contribution instanceof DeclarativeToolContribution declarative) {
      if (!declarativeBindingMatches(frozenContributor, declarative)) {
        return new ResolvedContribution(
            null,
            new ToolInvocationError(
                TOOL_DEFINITION_MISMATCH_KIND,
                "Declarative tool definition "
                    + frozenDefinition.id()
                    + " no longer matches its frozen state access declaration."));
      }
    }
    return new ResolvedContribution(contribution, null);
  }

  private static boolean declarativeBindingMatches(
      ContributorBinding binding, DeclarativeToolContribution contribution) {
    if (binding.stateAccesses().size() != contribution.stateAccesses().size()) {
      return false;
    }
    for (int i = 0; i < binding.stateAccesses().size(); i++) {
      ContributorStateAccess frozen = binding.stateAccesses().get(i);
      StateDeclaration current = contribution.stateAccesses().get(i);
      if (!frozen.customType().equals(current.customType())
          || !frozen.mode().name().equals(current.mode().name())) {
        return false;
      }
    }
    return true;
  }

  @Override
  public PreflightResult preflight(ToolInvocationRequest request) {
    Objects.requireNonNull(request, "request");
    ResolvedContribution resolved = validateContribution(request.binding());
    if (resolved.error() != null) {
      return new ToolGateway.Deny(resolved.error());
    }
    ToolSettings settings = toolSettingsProvider.get();
    PermissionEvaluator.Evaluation evaluation =
        permissionEvaluator.evaluate(
            new PermissionEvaluationContext(
                resolved.contribution().definition().id(),
                request.call().argumentsJson(),
                permissionWorkdir(request),
                settings));
    PermissionAction action = evaluation.action();
    return switch (action) {
      case ALLOW -> new ToolGateway.Allow();
      case ASK -> new ToolGateway.Ask(reason(evaluation.promptPreview()));
      case DENY -> new ToolGateway.Deny(
          new ToolInvocationError(PERMISSION_DENIED_KIND, PERMISSION_DENIED_MESSAGE));
    };
  }

  /**
   * ENVIRONMENT_CAPABILITY tool 的权限路径上下文体现冻结 binding 的 workspace：以现有 {@link #environmentRoot} 作为逻辑
   * root，把 canonical {@code workspacePath} 纯路径解析为 effective workdir（不查询 live registry、不做文件系统
   * IO）；HOST、 DECLARATIVE 与 null Environment binding 保持 server 默认 workdir（null binding 的确定性拒绝仍发生在
   * {@link #start}）。
   */
  private Path permissionWorkdir(ToolInvocationRequest request) {
    if (request.binding().definition().backend() == AgentToolBackend.ENVIRONMENT_CAPABILITY
        && request.binding().environment() != null) {
      return environmentRoot.resolve(Path.of(request.binding().environment().workspacePath()));
    }
    return workdir;
  }

  @Override
  public StartResult start(Execution execution, Listener listener) {
    Objects.requireNonNull(execution, "execution");
    Objects.requireNonNull(listener, "listener");
    Optional<ConcurrencyAdmission.Lease> acquired = admission.tryAcquire();
    if (acquired.isEmpty()) {
      return new ToolGateway.Overloaded(overloadRetryDelay.get());
    }
    ConcurrencyAdmission.Lease lease = acquired.orElseThrow();
    try {
      ResolvedContribution resolved = validateContribution(execution.request().binding());
      if (resolved.error() != null) {
        lease.close();
        return new ToolGateway.Rejected(resolved.error());
      }
      StartResult result =
          switch (execution.request().binding().definition().backend()) {
            case HOST -> startHost(execution, listener, lease, resolved.contribution());
            case DECLARATIVE -> startDeclarative(
                execution, listener, lease, resolved.contribution());
            case ENVIRONMENT_CAPABILITY -> startEnvironment(
                execution, listener, lease, resolved.contribution());
          };
      if (!(result instanceof ToolGateway.Started)) {
        lease.close();
      }
      return result;
    } catch (RuntimeException | Error failure) {
      // 顶层意外异常也必须释放本次 admission；Started 之前没有任何执行句柄可供调用方取消。
      lease.close();
      throw failure;
    }
  }

  /** HOST：按 frozen definition 校验并提交 executor 执行（拒绝即 Overloaded）。 */
  private StartResult startHost(
      Execution execution,
      Listener listener,
      ConcurrencyAdmission.Lease lease,
      ToolContribution contribution) {
    if (!(contribution instanceof HostToolContribution hostContribution)) {
      return new ToolGateway.Rejected(
          new ToolInvocationError(
              TOOL_DEFINITION_MISMATCH_KIND,
              "Frozen host tool definition is not a HOST contribution: "
                  + contribution.definition().id()));
    }
    Tool tool = hostContribution.tool();
    if (!tool.descriptor().equals(execution.request().binding().definition().descriptor())) {
      return new ToolGateway.Rejected(
          new ToolInvocationError(
              TOOL_DEFINITION_MISMATCH_KIND,
              "Registered tool descriptor does not match frozen descriptor: "
                  + contribution.definition().id()));
    }
    ToolExecutionRequest request = request(execution, tool.descriptor());
    GatedToolExecutionListener bridge =
        new GatedToolExecutionListener(
            listener, externalizer, tool.descriptor().name(), request.call().id(), lease);
    // 两阶段激活：executor 任务在 activate() 前只等待 release，绝不提前打开 gate / 触碰 Tool。
    GatewayHandle handle = new GatewayHandle(bridge::activate, bridge::cancel, lease);
    return submitLocalExecution(
        execution, bridge, handle, () -> runTool(tool, request, bridge, handle));
  }

  private StartResult submitLocalExecution(
      Execution execution,
      GatedToolExecutionListener bridge,
      GatewayHandle handle,
      Runnable runner) {
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
            runner.run();
          });
    } catch (RejectedExecutionException rejected) {
      // 肯定未接受：cancel 唤醒 broken executor 可能已启动的等待任务（它绝不触碰 Tool），Overloaded。
      bridge.cancel();
      log.warn(
          "tool gateway executor rejected local execution for invocation {}",
          execution.invocationId());
      return new ToolGateway.Overloaded(overloadRetryDelay.get());
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

  /** DECLARATIVE Tool：恢复 contribution，并在 externalize 前校验全部声明式 intents 与 WRITE ownership。 */
  private StartResult startDeclarative(
      Execution execution,
      Listener listener,
      ConcurrencyAdmission.Lease lease,
      ToolContribution contribution) {
    if (!(contribution instanceof DeclarativeToolContribution declarativeContribution)) {
      return new ToolGateway.Rejected(
          new ToolInvocationError(
              TOOL_DEFINITION_MISMATCH_KIND,
              "Frozen declarative tool definition is not a DECLARATIVE contribution: "
                  + contribution.definition().id()));
    }
    GatedToolExecutionListener bridge =
        new GatedToolExecutionListener(
            listener,
            externalizer,
            declarativeContribution.definition().descriptor().name(),
            execution.request().call().id(),
            lease);
    GatewayHandle handle = new GatewayHandle(bridge::activate, bridge::cancel, lease);
    return submitLocalExecution(
        execution,
        bridge,
        handle,
        () -> runDeclarative(execution, declarativeContribution, bridge));
  }

  private void runDeclarative(
      Execution execution,
      DeclarativeToolContribution contribution,
      GatedToolExecutionListener bridge) {
    DeclarativeToolResult outcome;
    try {
      DeclarativeToolContext context =
          new DeclarativeToolContext(
              contributorBranchViewLoader.load(execution.assistantEntryId()), clock.instant());
      outcome = contribution.tool().execute(context, execution.request().call());
    } catch (RuntimeException failure) {
      bridge.onError(failure);
      return;
    }
    if (outcome == null) {
      bridge.onDeclarativeFailure(
          new ToolInvocationError(
              CONTRIBUTOR_CONTRACT_VIOLATION_KIND, "Declarative tool returned a null outcome."));
      return;
    }
    ToolEffectBatch effects;
    try {
      effects = mapDeclarativeEffects(contribution, outcome);
    } catch (RuntimeException invalid) {
      bridge.onDeclarativeFailure(
          new ToolInvocationError(
              CONTRIBUTOR_CONTRACT_VIOLATION_KIND,
              failureMessage(invalid, "Declarative tool returned invalid intents.")));
      return;
    }
    bridge.onDeclarativeComplete(outcome.result(), effects);
  }

  private ToolEffectBatch mapDeclarativeEffects(
      DeclarativeToolContribution contribution, DeclarativeToolResult outcome) {
    List<CustomEntryPayload> payloads = new ArrayList<>();
    for (AppendCustomEntry append : outcome.intents()) {
      CustomEntryPayload payload = append.payload();
      if (!payload.contributorId().equals(contribution.id().contributorId().value())) {
        throw new IllegalArgumentException(
            "declarative Tool intent owner does not match its contribution");
      }
      if (catalog
          .findCustomEntryType(contribution.id().contributorId(), payload.customType())
          .isEmpty()) {
        throw new IllegalArgumentException(
            "declarative Tool intent targets an unregistered custom entry type: "
                + payload.customType());
      }
      boolean declaredWrite = false;
      for (StateDeclaration access : contribution.stateAccesses()) {
        if (access.customType().equals(payload.customType()) && access.mode() == StateMode.WRITE) {
          declaredWrite = true;
          break;
        }
      }
      if (!declaredWrite) {
        throw new IllegalArgumentException(
            "declarative Tool intent targets state without a declared WRITE access: "
                + payload.customType());
      }
      payloads.add(payload);
    }
    return new ToolEffectBatch(payloads);
  }

  /**
   * ENVIRONMENT_CAPABILITY：从 contribution 的 capability descriptor 构造独立 capability request，再按完整
   * binding 路由。能力缺失 / descriptor 漂移 / 发送前目标不可用（离线、未 READY、心跳过期）都是确定性 Rejected；同 Environment
   * 的瞬时容量冲突是 Busy； 发送不确定 / 未知异常是 Indeterminate（可能已开始，绝不能抛）。
   */
  private StartResult startEnvironment(
      Execution execution,
      Listener listener,
      ConcurrencyAdmission.Lease lease,
      ToolContribution contribution) {
    if (!(contribution instanceof EnvironmentCapabilityToolContribution envContribution)) {
      return new ToolGateway.Rejected(
          new ToolInvocationError(
              TOOL_DEFINITION_MISMATCH_KIND,
              "Frozen environment tool definition is not an ENVIRONMENT_CAPABILITY contribution: "
                  + contribution.definition().id()));
    }
    AgentToolDefinition frozenDefinition = execution.request().binding().definition();
    if (execution.request().binding().environment() == null) {
      // 冻结 binding 没有 Environment（分支最新 settings 未选中/被清空）：发送前确定性拒绝，
      // 绝不进入 transport（否则 null binding 会变成不确定结果）。
      return new ToolGateway.Rejected(
          new ToolInvocationError(
              UNAVAILABLE_KIND,
              "Environment tool "
                  + frozenDefinition.id()
                  + " has no environment binding (the branch has no selected environment)."));
    }
    EnvironmentCapabilityDescriptor capability = envContribution.capability();
    GatedToolExecutionListener bridge =
        new GatedToolExecutionListener(
            listener,
            externalizer,
            frozenDefinition.descriptor().name(),
            execution.request().call().id(),
            lease);
    // 两阶段激活：activate() 之前 gate 保持关闭（同步回调只进缓冲）；activate() 直接打开 gate 并串行重放（不提交独立重放任务）。
    GatewayHandle handle = new GatewayHandle(bridge::activate, bridge::cancel, lease);
    EnvironmentCapabilityExecutionHandle transportHandle;
    try {
      EnvironmentCapabilityCall capabilityCall =
          new EnvironmentCapabilityCall(
              execution.invocationId().toString(), execution.request().call().argumentsJson());
      EnvironmentCapabilityExecutionRequest capabilityRequest =
          new EnvironmentCapabilityExecutionRequest(
              capability, capabilityCall, Duration.ZERO, null);
      EnvironmentCapabilityExecutionListener capabilityListener =
          new EnvironmentCapabilityListenerAdapter(
              bridge, execution.invocationId().toString(), execution.request().call().id());
      transportHandle =
          capabilityTransport.invoke(
              execution.request().binding().environment(), capabilityRequest, capabilityListener);
    } catch (EnvironmentCapabilityBusyException busy) {
      // 同 Environment 已有 active remote invocation：INVOKE 肯定未发送，由 Harness 按固定延迟重新 admission，
      // 不创建 durable error。
      bridge.cancel();
      return new ToolGateway.Busy(busyRetryDelay.get());
    } catch (EnvironmentCapabilityUnavailableException unavailable) {
      // 发送前目标不可用（路由缺失/未 READY/心跳过期）：肯定未开始，且当前分支配置下重试不会改变结论——
      // 确定性拒绝，让模型看到 durable 错误结果并继续收敛。
      bridge.cancel();
      return new ToolGateway.Rejected(
          new ToolInvocationError(
              UNAVAILABLE_KIND, failureMessage(unavailable, "Tool is unavailable.")));
    } catch (EnvironmentCapabilitySendUncertainException uncertain) {
      // 发送不确定：可能已开始，绝不能抛。取消本地桥：迟到的 transport 回调被丢弃而不是永远缓冲。
      bridge.cancel();
      return new ToolGateway.Indeterminate(
          new ToolInvocationError(
              REMOTE_UNCERTAIN_KIND,
              failureMessage(
                  uncertain, "Remote tool send outcome is uncertain; result is unknown.")));
    } catch (IllegalArgumentException invalid) {
      bridge.cancel();
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
              "remote capability returned a null execution handle; outcome cannot be confirmed"));
      return new ToolGateway.Started(handle);
    }
    handle.attach(transportHandle::cancel);
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
    handle.attach(toolHandle::cancel);
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
   * Environment capability 回调桥：只在 capability call id 与 durable invocation UUID 一致时恢复 model call
   * id；错误 correlation 保留原值交给 Gated bridge 的既有 INVALID_PARTIAL / INVALID_RESULT 校验，从而在任何 Resource
   * externalization 前 terminalize。
   */
  private static final class EnvironmentCapabilityListenerAdapter
      implements EnvironmentCapabilityExecutionListener {
    private final GatedToolExecutionListener bridge;
    private final String expectedCapabilityCallId;
    private final String modelCallId;

    private EnvironmentCapabilityListenerAdapter(
        GatedToolExecutionListener bridge, String expectedCapabilityCallId, String modelCallId) {
      this.bridge = Objects.requireNonNull(bridge, "bridge");
      this.expectedCapabilityCallId =
          Objects.requireNonNull(expectedCapabilityCallId, "expectedCapabilityCallId");
      this.modelCallId = Objects.requireNonNull(modelCallId, "modelCallId");
    }

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {
      bridge.onPartial(toToolResult(partial));
    }

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      bridge.onComplete(toToolResult(result));
    }

    @Override
    public void onError(Throwable error) {
      bridge.onError(error);
    }

    private ToolResult toToolResult(EnvironmentCapabilityResult result) {
      if (result == null) {
        return null;
      }
      String toolCallId = result.callId();
      if (expectedCapabilityCallId.equals(toolCallId)) {
        toolCallId = modelCallId;
      } else if (modelCallId.equals(toolCallId)) {
        // 即使恶意 capability callId 恰好等于 model call id，也必须让 Gated bridge 看到 correlation mismatch。
        toolCallId = expectedCapabilityCallId;
      }
      return new ToolResult(toolCallId, result.contents(), result.error(), result.detailsJson());
    }
  }

  private interface CancellableHandle {
    void cancel();
  }

  /**
   * 本地取消 / 激活控制：包装 Tool 的 execution handle；handle 尚未返回时记录取消意图，返回后立即取消；幂等且 best effort。 activate /
   * cancel 都原子且幂等：CAS 标志保证 cancel 已先到则 activate 绝不再打开投递（不会重新投递或产生 resource 写入）， 二者在桥 monitor 内与
   * {@link GatedToolExecutionListener#activate()} / {@code cancel()} 互斥。
   */
  private static final class GatewayHandle implements ToolGateway.Handle {
    private final AtomicBoolean activated = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<CancellableHandle> cancellableHandle = new AtomicReference<>();
    private final Runnable activation;
    private final Runnable abort;
    private final ConcurrencyAdmission.Lease lease;

    GatewayHandle(Runnable activation, Runnable abort, ConcurrencyAdmission.Lease lease) {
      this.activation = Objects.requireNonNull(activation, "activation");
      this.abort = Objects.requireNonNull(abort, "abort");
      this.lease = Objects.requireNonNull(lease, "lease");
    }

    void attach(Runnable cancellation) {
      Objects.requireNonNull(cancellation, "cancellation");
      CancellableHandle handle =
          () -> {
            cancellation.run();
          };
      cancellableHandle.set(handle);
      if (cancelled.get()) {
        handle.cancel();
      }
    }

    /**
     * 两阶段激活：直接打开桥 gate（释放 HOST 等待任务 + 串行重放缓冲回调），不提交任何独立任务。幂等；cancel 已先到则 no-op（桥 monitor 内
     * cancelled/open 原子互斥，绝不可能重新打开投递）。
     */
    @Override
    public void activate() {
      if (activated.compareAndSet(false, true) && !cancelled.get()) {
        try {
          activation.run();
        } catch (RuntimeException failure) {
          lease.close();
          throw failure;
        }
      }
    }

    @Override
    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        try {
          // 与 activate 在桥 monitor 内原子互斥：cancel 先到则 activate no-op；activate 先到则此后所有信号被丢弃。
          abort.run();
          CancellableHandle handle = cancellableHandle.get();
          if (handle != null) {
            handle.cancel();
          }
        } finally {
          lease.close();
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
   * 任务（HOST 路径，之后任务才运行 Tool）+ 串行重放缓冲——不再提交任何独立重放任务，HOST / DECLARATIVE / ENVIRONMENT_CAPABILITY
   * 行为一致。重放与直接转发都绝不发生在 {@code start()} 的调用栈上。等待任务在 release 前被中断（而非取消）时队列一个未分类失败，activate 时恰好一次
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
    private final ConcurrencyAdmission.Lease lease;
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
        String expectedCallId,
        ConcurrencyAdmission.Lease lease) {
      this.listener = Objects.requireNonNull(listener, "listener");
      this.externalizer = Objects.requireNonNull(externalizer, "externalizer");
      this.toolName = Objects.requireNonNull(toolName, "toolName");
      this.expectedCallId = Objects.requireNonNull(expectedCallId, "expectedCallId");
      this.lease = Objects.requireNonNull(lease, "lease");
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
      lease.close();
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
      deliver(new Signal(SignalKind.PARTIAL, partial, null, ToolEffectBatch.EMPTY, null, null));
    }

    @Override
    public void onComplete(ToolResult result) {
      deliver(new Signal(SignalKind.COMPLETE, null, result, ToolEffectBatch.EMPTY, null, null));
    }

    void onDeclarativeComplete(ToolResult result, ToolEffectBatch effects) {
      deliver(
          new Signal(
              SignalKind.COMPLETE,
              null,
              result,
              Objects.requireNonNull(effects, "effects"),
              null,
              null));
    }

    void onDeclarativeFailure(ToolInvocationError error) {
      deliver(
          new Signal(
              SignalKind.DECLARATIVE_FAILURE,
              null,
              null,
              ToolEffectBatch.EMPTY,
              null,
              Objects.requireNonNull(error, "error")));
    }

    @Override
    public void onError(Throwable error) {
      deliver(new Signal(SignalKind.ERROR, null, null, ToolEffectBatch.EMPTY, error, null));
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
        case COMPLETE -> processComplete(signal.result, signal.effects);
        case ERROR -> processError(signal.error);
        case DECLARATIVE_FAILURE -> fail(signal.declarativeFailure);
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

    private boolean processComplete(ToolResult result, ToolEffectBatch effects) {
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
          deliverTerminal(() -> listener.onSucceeded(new ToolSuccess(success.result(), effects)));
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
     * 错误分类：只有 {@link EnvironmentCapabilityFailedException}（daemon FAILED）是已确认的非可重试已知失败；cancelled /
     * unavailable（retryable=true）/ uncertain 保留显式分类；其余未分类错误（含 HOST/DECLARATIVE {@code tool.execute}
     * 抛异常）一律 UNKNOWN。任何错误信号都使桥 terminal。
     */
    private boolean processError(Throwable error) {
      if (error == null) {
        // onError(null)：无法确认执行结果，恰好一次 UNKNOWN。
        return unknown(null, "tool reported a null error; outcome cannot be confirmed");
      }
      if (error instanceof EnvironmentCapabilityCancelledException cancelled) {
        deliverTerminal(
            () ->
                listener.onCancelled(
                    new ToolInvocationError(
                        CANCELLED_KIND, failureMessage(cancelled, "Tool execution cancelled."))));
        return true;
      }
      if (error instanceof EnvironmentCapabilityFailedException failed) {
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
      if (error instanceof EnvironmentCapabilitySendUncertainException uncertain) {
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
      if (error instanceof EnvironmentCapabilityUnavailableException unavailable) {
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
      } finally {
        lease.close();
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
    ERROR,
    DECLARATIVE_FAILURE
  }

  private record Signal(
      SignalKind kind,
      ToolResult partial,
      ToolResult result,
      ToolEffectBatch effects,
      Throwable error,
      ToolInvocationError declarativeFailure) {}
}
