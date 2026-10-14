package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.tool.ToolErrorGuidance;
import fun.fengwk.kkstudio.harness.common.tool.ToolErrorGuidance.ExecutionFact;
import fun.fengwk.kkstudio.harness.contributor.api.AppendCustomEntry;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCancelledException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityFailedException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccessMode;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluationContext;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.port.ToolSuccess;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolResultSizeLimits;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.harness.GatewayExecutorSafety;
import fun.fengwk.kkstudio.platform.harness.contributor.ContributorBranchViewLoader;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 生产工具执行网关，连接 Harness Runtime、运行时工具目录和 Contributor {@link Tool} SPI。
 *
 * <p>{@link #preflight} 只校验冻结绑定与当前目录是否一致，拒绝未选择 Environment 的环境工具，然后执行权限判定。{@link #start}
 * 获取并发租约，装配分支视图与可选环境绑定，再提交异步执行。
 *
 * <p>工具在句柄 {@link ToolGateway.Handle#activate()} 前不会开始执行。回调桥以有界 FIFO
 * 串行派发信号，并保证单次终态；取消、拒绝、启动失败或终态都会释放租约。远程发送结果不确定时上报 {@code unknown}，不按普通失败处理。
 *
 * <p>终态结果进入 Runtime 前会校验 call ID、自定义条目注册与 WRITE 声明，并按大小限制外部化内容。
 */
@Slf4j
public final class ToolExecutionGateway implements ToolGateway {

  static final String PERMISSION_DENIED_KIND = "PERMISSION_DENIED";
  static final String TOOL_NOT_FOUND_KIND = "TOOL_NOT_FOUND";
  static final String TOOL_DEFINITION_MISMATCH_KIND = "TOOL_DEFINITION_MISMATCH";
  static final String ENVIRONMENT_NOT_SELECTED_KIND = "ENVIRONMENT_NOT_SELECTED";
  static final String CANCELLED_KIND = "CANCELLED";
  static final String UNAVAILABLE_KIND = "UNAVAILABLE";
  static final String REMOTE_UNCERTAIN_KIND = "REMOTE_UNCERTAIN";
  static final String INVALID_REQUEST_KIND = "INVALID_REQUEST";
  static final String EXECUTION_FAILED_KIND = "EXECUTION_FAILED";
  static final String INVALID_PARTIAL_KIND = "INVALID_PARTIAL";
  static final String CONTRIBUTOR_CONTRACT_VIOLATION_KIND = "CONTRIBUTOR_CONTRACT_VIOLATION";

  /** 回调桥缓冲队列的保守上限：gate 打开前的同步回调绝不能无界缓冲。 */
  static final int MAX_BUFFERED_SIGNALS = 256;

  private static final String PERMISSION_DENIED_MESSAGE =
      ToolErrorGuidance.message(
          "Tool permission was denied",
          ExecutionFact.NOT_EXECUTED,
          "Request approval or adjust the tool permission policy");

  /** 冻结定义与当前 catalog 不一致时的确定性下一步：模型无法原地修复，只能以新冻结重开。 */
  private static final String DEFINITION_MISMATCH_NEXT_ACTION =
      "Start a new thread so the frozen tool definition is re-frozen against the current catalog";

  /** 确定性拒绝的下一步：同一调用在修复前会得到同样结果，不构成自动重试建议。 */
  private static final String DETERMINISTIC_REJECTION_NEXT_ACTION =
      "Correct the underlying contract violation before calling the tool again";

  /** 结果不确定时的下一步：先核查副作用再决定，绝不能假设未执行。 */
  private static final String VERIFY_EFFECT_NEXT_ACTION =
      "Check whether the tool already took effect before calling it again";

  private final RuntimeToolCatalog toolCatalog;
  private final HarnessCatalog harnessCatalog;
  private final ContributorBranchViewLoader contributorBranchViewLoader;
  private final EnvironmentCapabilityTransport capabilityTransport;
  private final PermissionEvaluator permissionEvaluator;
  private final ToolSettingsProvider toolSettingsProvider;
  private final ToolResultFinalizer finalizer;
  private final ExecutorService executor;
  private final Supplier<Duration> retryDelay;
  private final Clock clock;
  private final ConcurrencyAdmission admission;

  public ToolExecutionGateway(
      RuntimeToolCatalog toolCatalog,
      HarnessCatalog harnessCatalog,
      ContributorBranchViewLoader contributorBranchViewLoader,
      EnvironmentCapabilityTransport capabilityTransport,
      PermissionEvaluator permissionEvaluator,
      ToolSettingsProvider toolSettingsProvider,
      ResourceStore resourceStore,
      ToolResourceStager resourceStager,
      int resourceMaxBytes,
      ExecutorService executor,
      Supplier<Duration> retryDelay,
      Clock clock,
      ConcurrencyAdmission admission) {
    this.toolCatalog = Objects.requireNonNull(toolCatalog, "toolCatalog");
    this.harnessCatalog = Objects.requireNonNull(harnessCatalog, "harnessCatalog");
    this.contributorBranchViewLoader =
        Objects.requireNonNull(contributorBranchViewLoader, "contributorBranchViewLoader");
    this.capabilityTransport = Objects.requireNonNull(capabilityTransport, "capabilityTransport");
    this.permissionEvaluator = Objects.requireNonNull(permissionEvaluator, "permissionEvaluator");
    this.toolSettingsProvider =
        Objects.requireNonNull(toolSettingsProvider, "toolSettingsProvider");
    this.finalizer =
        new ToolResultFinalizer(
            Objects.requireNonNull(resourceStore, "resourceStore"),
            Objects.requireNonNull(resourceStager, "resourceStager"),
            resourceMaxBytes);
    this.executor = Objects.requireNonNull(executor, "executor");
    this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.admission = Objects.requireNonNull(admission, "admission");
    GatewayExecutorSafety.requireSafeAsyncExecutor(executor, "tool gateway");
  }

  private record ResolvedContribution(ToolContribution contribution, ToolInvocationError error) {}

  private ResolvedContribution validateContribution(ToolBinding binding) {
    AgentToolDefinition frozenDefinition = binding.definition();
    String toolName = frozenDefinition.descriptor().name();
    ToolContribution contribution = toolCatalog.findTool(toolName).orElse(null);
    if (contribution == null) {
      return new ResolvedContribution(
          null,
          new ToolInvocationError(
              TOOL_NOT_FOUND_KIND,
              ToolErrorGuidance.message(
                  "Frozen tool definition " + toolName + " is not registered",
                  ExecutionFact.NOT_EXECUTED,
                  DEFINITION_MISMATCH_NEXT_ACTION)));
    }
    if (!contribution.definition().equals(frozenDefinition)) {
      return new ResolvedContribution(
          null,
          new ToolInvocationError(
              TOOL_DEFINITION_MISMATCH_KIND,
              ToolErrorGuidance.message(
                  "Frozen tool definition " + toolName + " does not match its catalog definition",
                  ExecutionFact.NOT_EXECUTED,
                  DEFINITION_MISMATCH_NEXT_ACTION)));
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
              ToolErrorGuidance.message(
                  "Frozen contributor binding "
                      + frozenContributor.contributorId()
                      + "/"
                      + frozenContributor.localName()
                      + " does not match the catalog contribution",
                  ExecutionFact.NOT_EXECUTED,
                  DEFINITION_MISMATCH_NEXT_ACTION)));
    }
    ToolRequirements requirements = contribution.requirements();
    if (binding.environmentSupport() != requirements.environmentSupport()) {
      return new ResolvedContribution(
          null,
          new ToolInvocationError(
              TOOL_DEFINITION_MISMATCH_KIND,
              ToolErrorGuidance.message(
                  "Frozen tool environment requirement does not match catalog requirements: "
                      + toolName,
                  ExecutionFact.NOT_EXECUTED,
                  DEFINITION_MISMATCH_NEXT_ACTION)));
    }
    if (requirements.requiredEnvironmentId() != null
        && binding.environmentId() != null
        && !requirements.requiredEnvironmentId().equals(binding.environmentId())) {
      return new ResolvedContribution(
          null,
          new ToolInvocationError(
              TOOL_DEFINITION_MISMATCH_KIND,
              ToolErrorGuidance.message(
                  "Frozen tool bound environment "
                      + binding.environmentId()
                      + " does not match catalog required environment: "
                      + requirements.requiredEnvironmentId(),
                  ExecutionFact.NOT_EXECUTED,
                  DEFINITION_MISMATCH_NEXT_ACTION)));
    }
    if (!stateAccessesMatch(frozenContributor, requirements)) {
      return new ResolvedContribution(
          null,
          new ToolInvocationError(
              TOOL_DEFINITION_MISMATCH_KIND,
              ToolErrorGuidance.message(
                  "Tool definition "
                      + toolName
                      + " no longer matches its frozen state access declaration",
                  ExecutionFact.NOT_EXECUTED,
                  DEFINITION_MISMATCH_NEXT_ACTION)));
    }
    return new ResolvedContribution(contribution, null);
  }

  /**
   * 冻结 binding 声明环境需求但没有 Environment 路由身份时，返回稳定的 {@code ENVIRONMENT_NOT_SELECTED}；否则返回 null。
   *
   * <p>这是 branch 未选择 Environment 的确定性结果：错误明确要求为当前 branch 选择 Environment 后重试，绝不伪装成权限、定义或 transport
   * 失败。
   */
  private static ToolInvocationError environmentNotSelected(ToolBinding binding) {
    if (binding.environmentSupport() != EnvironmentSupport.REQUIRED
        || binding.environmentId() != null) {
      return null;
    }
    return new ToolInvocationError(
        ENVIRONMENT_NOT_SELECTED_KIND,
        ToolErrorGuidance.message(
            "Tool "
                + binding.descriptor().name()
                + " requires an Environment but this branch has none selected",
            ExecutionFact.NOT_EXECUTED,
            "Select an Environment for the branch, then call the tool again"));
  }

  private static boolean stateAccessesMatch(
      ContributorBinding binding, ToolRequirements requirements) {
    if (binding.stateAccesses().size() != requirements.stateAccesses().size()) {
      return false;
    }
    for (int i = 0; i < binding.stateAccesses().size(); i++) {
      ContributorStateAccess frozen = binding.stateAccesses().get(i);
      StateDeclaration current = requirements.stateAccesses().get(i);
      if (!frozen.customType().equals(current.customType())
          || !frozen.mode().name().equals(current.mode().name())) {
        return false;
      }
    }
    return true;
  }

  /** 只读校验冻结工具绑定，并返回当前权限策略的判定。 */
  @Override
  public PreflightResult preflight(ToolInvocationRequest request) {
    Objects.requireNonNull(request, "request");
    ResolvedContribution resolved = validateContribution(request.binding());
    if (resolved.error() != null) {
      return new ToolGateway.Deny(resolved.error());
    }
    // 未选择 Environment 的 branch 仍声明并允许调用环境工具；此时必须在权限判定之前给出可操作的确定性拒绝，
    // 既不弹审批也不消耗权限策略。
    ToolInvocationError environmentNotSelected = environmentNotSelected(request.binding());
    if (environmentNotSelected != null) {
      return new ToolGateway.Deny(environmentNotSelected);
    }
    ToolSettings settings = toolSettingsProvider.get();
    PermissionEvaluator.Evaluation evaluation =
        permissionEvaluator.evaluate(
            new PermissionEvaluationContext(
                resolved.contribution().definition().descriptor().name(),
                request.call().argumentsJson(),
                settings));
    PermissionAction action = evaluation.action();
    return switch (action) {
      case ALLOW -> new ToolGateway.Allow();
      case ASK -> new ToolGateway.Ask("Permission rules require approval");
      case DENY -> new ToolGateway.Deny(
          new ToolInvocationError(PERMISSION_DENIED_KIND, PERMISSION_DENIED_MESSAGE));
    };
  }

  /** 获取并发租约并准备异步执行；实际工具调用等待返回句柄被激活。 */
  @Override
  public StartResult start(Execution execution, Listener listener) {
    Objects.requireNonNull(execution, "execution");
    Objects.requireNonNull(listener, "listener");
    Optional<ConcurrencyAdmission.Lease> acquired = admission.tryAcquire();
    if (acquired.isEmpty()) {
      return new ToolGateway.RetryLater(retryDelay.get());
    }
    ConcurrencyAdmission.Lease lease = acquired.orElseThrow();
    try {
      ResolvedContribution resolved = validateContribution(execution.request().binding());
      if (resolved.error() != null) {
        lease.close();
        return new ToolGateway.Rejected(resolved.error());
      }
      ToolContribution contribution = resolved.contribution();
      Tool tool = contribution.tool();
      ToolDescriptor currentDescriptor = tool == null ? null : tool.descriptor();
      if (!Objects.equals(
          currentDescriptor, execution.request().binding().definition().descriptor())) {
        lease.close();
        return new ToolGateway.Rejected(
            new ToolInvocationError(
                TOOL_DEFINITION_MISMATCH_KIND,
                ToolErrorGuidance.message(
                    "Registered tool descriptor does not match frozen descriptor: "
                        + contribution.definition().descriptor().name(),
                    ExecutionFact.NOT_EXECUTED,
                    DEFINITION_MISMATCH_NEXT_ACTION)));
      }
      // preflight 已拦截的形态在此保留同样的 fail-safe：事务外仍有窄窗口，绝不能退化为普通 UNAVAILABLE。
      ToolInvocationError environmentNotSelected =
          environmentNotSelected(execution.request().binding());
      if (environmentNotSelected != null) {
        lease.close();
        return new ToolGateway.Rejected(environmentNotSelected);
      }

      BranchView branch =
          contributorBranchViewLoader.load(
              execution.assistantEntryId(), contribution.id().contributorId().value());
      BoundEnvironment boundEnvironment =
          execution.request().binding().environmentId() != null
              ? new PlatformBoundEnvironment(
                  execution.request().binding().environmentId(),
                  capabilityTransport,
                  execution.invocationId().toString(),
                  execution.request().call().id())
              : null;
      ToolExecutionContext context =
          new ToolExecutionContext(
              execution.invocationId(),
              execution.threadId(),
              clock.instant(),
              branch,
              Optional.ofNullable(boundEnvironment));
      // 超时在执行前只解析一次：解析结果原样进入请求，下游不再回落默认值或施加任何上限。
      ToolExecutionRequest toolRequest;
      try {
        toolRequest =
            new ToolExecutionRequest(
                currentDescriptor,
                execution.request().call(),
                tool.resolveTimeout(execution.request().call()),
                context);
      } catch (IllegalArgumentException invalidRequest) {
        // arguments 级超时非法（例如非正数）或请求无法按 descriptor 构造：确定性拒绝，绝不重试也绝不执行。
        lease.close();
        return new ToolGateway.Rejected(
            new ToolInvocationError(
                INVALID_REQUEST_KIND,
                ToolErrorGuidance.message(
                    failureMessage(invalidRequest, "Tool request is invalid"),
                    ExecutionFact.NOT_EXECUTED,
                    "Correct the tool arguments, then call the tool again")));
      }

      GatedToolExecutionListener bridge =
          new GatedToolExecutionListener(
              listener,
              finalizer,
              currentDescriptor.name(),
              toolRequest.call().id(),
              lease,
              harnessCatalog,
              contribution,
              execution.request().binding());
      GatewayHandle handle = new GatewayHandle(bridge, lease);
      StartResult result =
          submitLocalExecution(
              execution, bridge, handle, () -> runTool(tool, toolRequest, bridge, handle));
      if (!(result instanceof ToolGateway.Started)) {
        lease.close();
      }
      return result;
    } catch (RuntimeException | Error failure) {
      lease.close();
      throw failure;
    }
  }

  /** 调用贡献者 Tool，将同步异常或空句柄转为错误回调，并把有效句柄的取消动作绑定到 Gateway。 */
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
      bridge.onError(
          new IllegalStateException(
              "tool returned a null execution handle; outcome cannot be confirmed"));
      return;
    }
    handle.attach(toolHandle::cancel);
  }

  /** 提交等待激活门控的本地任务，将线程池拒绝映射为重试，其他提交异常映射为结果不确定。 */
  private StartResult submitLocalExecution(
      Execution execution,
      GatedToolExecutionListener bridge,
      GatewayHandle handle,
      Runnable runner) {
    try {
      executor.execute(
          () -> {
            if (!bridge.awaitRelease()) {
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
      bridge.cancel();
      log.warn(
          "tool gateway executor rejected local execution for invocation {}",
          execution.invocationId());
      return new ToolGateway.RetryLater(retryDelay.get());
    } catch (RuntimeException ambiguous) {
      bridge.cancel();
      log.warn(
          "tool gateway executor submission failed for invocation {}",
          execution.invocationId(),
          ambiguous);
      return new ToolGateway.Indeterminate(
          new ToolInvocationError(
              EXECUTION_FAILED_KIND,
              ToolErrorGuidance.message(
                  "Tool execution submission failed",
                  ExecutionFact.UNCERTAIN,
                  VERIFY_EFFECT_NEXT_ACTION)));
    }
    return new ToolGateway.Started(handle);
  }

  /** Platform 的 {@link BoundEnvironment} 适配实现，桥接底层的 {@link EnvironmentCapabilityTransport}。 */
  private static final class PlatformBoundEnvironment implements BoundEnvironment {
    private final EnvironmentId environmentId;
    private final EnvironmentCapabilityTransport capabilityTransport;
    private final String invocationId;
    private final String modelCallId;

    private PlatformBoundEnvironment(
        EnvironmentId environmentId,
        EnvironmentCapabilityTransport capabilityTransport,
        String invocationId,
        String modelCallId) {
      this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
      this.capabilityTransport = Objects.requireNonNull(capabilityTransport, "capabilityTransport");
      this.invocationId = Objects.requireNonNull(invocationId, "invocationId");
      this.modelCallId = Objects.requireNonNull(modelCallId, "modelCallId");
    }

    @Override
    public EnvironmentId environmentId() {
      return environmentId;
    }

    @Override
    public ToolExecutionHandle execute(
        EnvironmentCapabilityDescriptor capability,
        ToolExecutionRequest request,
        ToolExecutionListener listener) {
      Objects.requireNonNull(capability, "capability");
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(listener, "listener");
      EnvironmentCapabilityExecutionListener capabilityListener =
          new EnvironmentCapabilityListenerAdapter(listener, invocationId, modelCallId);
      try {
        EnvironmentCapabilityCall capabilityCall =
            new EnvironmentCapabilityCall(invocationId, request.call().argumentsJson());
        EnvironmentCapabilityExecutionRequest capabilityRequest =
            new EnvironmentCapabilityExecutionRequest(
                capability, capabilityCall, request.timeout());
        EnvironmentCapabilityExecutionHandle transportHandle =
            capabilityTransport.invoke(environmentId, capabilityRequest, capabilityListener);
        if (transportHandle == null) {
          listener.onError(
              new IllegalStateException(
                  "remote capability returned a null execution handle; outcome cannot be confirmed"));
          return CompletedToolExecutionHandle.INSTANCE;
        }
        return new ToolExecutionHandle() {
          @Override
          public void cancel() {
            transportHandle.cancel();
          }

          @Override
          public boolean isCancelled() {
            return transportHandle.isCancelled();
          }
        };
      } catch (EnvironmentCapabilityBusyException busy) {
        listener.onError(busy);
        return CompletedToolExecutionHandle.INSTANCE;
      } catch (EnvironmentCapabilityUnavailableException unavailable) {
        listener.onError(unavailable);
        return CompletedToolExecutionHandle.INSTANCE;
      } catch (EnvironmentCapabilitySendUncertainException uncertain) {
        listener.onError(uncertain);
        return CompletedToolExecutionHandle.INSTANCE;
      } catch (EnvironmentCapabilityFailedException failed) {
        listener.onError(failed);
        return CompletedToolExecutionHandle.INSTANCE;
      } catch (EnvironmentCapabilityCancelledException cancelled) {
        listener.onError(cancelled);
        return CompletedToolExecutionHandle.INSTANCE;
      } catch (RuntimeException error) {
        listener.onError(error);
        return CompletedToolExecutionHandle.INSTANCE;
      }
    }
  }

  private static final class EnvironmentCapabilityListenerAdapter
      implements EnvironmentCapabilityExecutionListener {
    private final ToolExecutionListener listener;
    private final String expectedCapabilityCallId;
    private final String modelCallId;

    private EnvironmentCapabilityListenerAdapter(
        ToolExecutionListener listener, String expectedCapabilityCallId, String modelCallId) {
      this.listener = Objects.requireNonNull(listener, "listener");
      this.expectedCapabilityCallId =
          Objects.requireNonNull(expectedCapabilityCallId, "expectedCapabilityCallId");
      this.modelCallId = Objects.requireNonNull(modelCallId, "modelCallId");
    }

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {
      listener.onPartial(toToolResult(partial));
    }

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      listener.onComplete(toToolResult(result));
    }

    @Override
    public void onError(Throwable error) {
      listener.onError(error);
    }

    private ToolResult toToolResult(EnvironmentCapabilityResult result) {
      if (result == null) {
        return null;
      }
      String toolCallId = result.callId();
      if (expectedCapabilityCallId.equals(toolCallId)) {
        toolCallId = modelCallId;
      } else if (modelCallId.equals(toolCallId)) {
        toolCallId = expectedCapabilityCallId;
      }
      return new ToolResult(toolCallId, result.contents(), result.error(), result.detailsJson());
    }
  }

  private static String failureMessage(Throwable error, String fallback) {
    String message = safeMessage(error);
    return message == null ? fallback : message;
  }

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

  private interface CancellableHandle {
    void cancel();
  }

  static final class GatewayHandle implements ToolGateway.Handle {
    private final AtomicBoolean activated = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<CancellableHandle> cancellableHandle = new AtomicReference<>();
    private final GatedToolExecutionListener bridge;
    private final ConcurrencyAdmission.Lease lease;

    GatewayHandle(GatedToolExecutionListener bridge, ConcurrencyAdmission.Lease lease) {
      this.bridge = Objects.requireNonNull(bridge, "bridge");
      this.lease = Objects.requireNonNull(lease, "lease");
    }

    ToolExecutionListener bridge() {
      return bridge;
    }

    void attach(Runnable cancellation) {
      Objects.requireNonNull(cancellation, "cancellation");
      CancellableHandle handle = cancellation::run;
      cancellableHandle.set(handle);
      if (cancelled.get()) {
        handle.cancel();
      }
    }

    @Override
    public void activate() {
      if (activated.compareAndSet(false, true) && !cancelled.get()) {
        try {
          bridge.activate();
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
          bridge.cancel();
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

  /** 在激活前缓冲回调，激活后串行派发，并将一次终态作为租约释放边界。 */
  private static final class GatedToolExecutionListener implements ToolExecutionListener {
    private final ToolGateway.Listener listener;
    private final ToolResultFinalizer finalizer;
    private final String toolName;
    private final String expectedCallId;
    private final ConcurrencyAdmission.Lease lease;
    private final HarnessCatalog harnessCatalog;
    private final ToolContribution contribution;
    private final ToolBinding binding;
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
        ToolResultFinalizer finalizer,
        String toolName,
        String expectedCallId,
        ConcurrencyAdmission.Lease lease,
        HarnessCatalog harnessCatalog,
        ToolContribution contribution,
        ToolBinding binding) {
      this.listener = Objects.requireNonNull(listener, "listener");
      this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
      this.toolName = Objects.requireNonNull(toolName, "toolName");
      this.expectedCallId = Objects.requireNonNull(expectedCallId, "expectedCallId");
      this.lease = Objects.requireNonNull(lease, "lease");
      this.harnessCatalog = Objects.requireNonNull(harnessCatalog, "harnessCatalog");
      this.contribution = Objects.requireNonNull(contribution, "contribution");
      this.binding = Objects.requireNonNull(binding, "binding");
    }

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

    void cancel() {
      synchronized (monitor) {
        cancelled = true;
        queue.clear();
        monitor.notifyAll();
      }
      lease.close();
    }

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
    public void onComplete(ToolOutcome outcome) {
      deliver(new Signal(SignalKind.COMPLETE, null, outcome, null));
    }

    @Override
    public void onComplete(ToolResult result) {
      if (result == null) {
        deliver(new Signal(SignalKind.COMPLETE, null, null, null));
        return;
      }
      onComplete(ToolOutcome.withoutEffects(result));
    }

    @Override
    public void onError(Throwable error) {
      deliver(new Signal(SignalKind.ERROR, null, null, error));
    }

    private void deliver(Signal signal) {
      boolean dispatch = false;
      synchronized (monitor) {
        if (terminal || cancelled || overflowed) {
          return;
        }
        if (queue.size() >= MAX_BUFFERED_SIGNALS) {
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
          terminalSignal =
              unknown(
                  new IllegalStateException(
                      "tool callback buffer exceeded " + MAX_BUFFERED_SIGNALS + " signals"),
                  "the tool callback buffer overflowed");
        } else {
          try {
            terminalSignal = process(signal);
          } catch (RuntimeException failure) {
            terminalSignal = unknown(failure, "tool callback dispatch failed");
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

    private boolean process(Signal signal) {
      return switch (signal.kind) {
        case PARTIAL -> processPartial(signal.partial);
        case COMPLETE -> processComplete(signal.outcome);
        case ERROR -> processError(signal.error);
      };
    }

    private boolean processPartial(ToolResult partial) {
      if (partial == null) {
        return fail(invalidPartial("partial must not be null"));
      }
      if (!partial.toolCallId().equals(expectedCallId)) {
        return fail(invalidPartial("partial toolCallId does not match the request call"));
      }
      for (ResultContent content : partial.contents()) {
        if (content instanceof BinaryResultContent || content instanceof ResourceResultContent) {
          return fail(invalidPartial("partial must not carry binary or resource content"));
        }
      }
      if (ToolResultJsonCodec.exceedsEncodedUtf8Bytes(
          partial, ToolResultSizeLimits.MAX_PARTIAL_RESULT_UTF8_BYTES)) {
        return fail(
            invalidPartial(
                "partial must not exceed "
                    + ToolResultSizeLimits.MAX_PARTIAL_RESULT_UTF8_BYTES
                    + " bytes of canonical tool result JSON"));
      }
      return deliverPartialOrUnknown(() -> listener.onPartial(partial));
    }

    /** 工具已开始执行后产出非法 partial：不是派发前验证拒绝，只能声明结果未交付且不可确认，不能声称未执行。 */
    private static ToolInvocationError invalidPartial(String whatFailed) {
      return new ToolInvocationError(
          INVALID_PARTIAL_KIND,
          ToolErrorGuidance.message(
              whatFailed, ExecutionFact.UNCERTAIN, DETERMINISTIC_REJECTION_NEXT_ACTION));
    }

    private static ToolInvocationError invalidResult(String whatFailed) {
      return new ToolInvocationError(
          ToolResultFinalizer.INVALID_RESULT_KIND,
          ToolErrorGuidance.message(
              whatFailed, ExecutionFact.UNCERTAIN, DETERMINISTIC_REJECTION_NEXT_ACTION));
    }

    private boolean processComplete(ToolOutcome outcome) {
      if (outcome == null || outcome.result() == null) {
        return fail(invalidResult("terminal result must not be null"));
      }
      ToolResult result = outcome.result();
      if (!result.toolCallId().equals(expectedCallId)) {
        return fail(invalidResult("terminal result toolCallId does not match the request call"));
      }
      ToolEffectBatch effects;
      try {
        effects = mapEffects(outcome);
      } catch (RuntimeException invalid) {
        return fail(
            new ToolInvocationError(
                CONTRIBUTOR_CONTRACT_VIOLATION_KIND,
                ToolErrorGuidance.message(
                    failureMessage(invalid, "Tool outcome returned invalid custom entries"),
                    ExecutionFact.UNCERTAIN,
                    DETERMINISTIC_REJECTION_NEXT_ACTION)));
      }
      ToolResultFinalizer.Outcome finalized;
      try {
        finalized = finalizer.finalizeResult(toolName, result);
      } catch (RuntimeException failure) {
        return unknown(failure, "tool result finalization failed");
      }
      return switch (finalized) {
        case ToolResultFinalizer.Outcome.Success success -> {
          deliverTerminal(() -> listener.onSucceeded(new ToolSuccess(success.result(), effects)));
          yield true;
        }
        case ToolResultFinalizer.Outcome.Failed failed -> fail(failed.error());
        case ToolResultFinalizer.Outcome.StoreFailed storeFailed -> {
          deliverTerminal(() -> listener.onUnknown(storeFailed.error()));
          yield true;
        }
      };
    }

    /** 仅接受已注册且由当前工具声明 WRITE 权限的自定义条目。 */
    private ToolEffectBatch mapEffects(ToolOutcome outcome) {
      if (outcome.customEntries().isEmpty()) {
        return ToolEffectBatch.EMPTY;
      }
      if (outcome.result().error()) {
        throw new IllegalArgumentException("an error ToolResult must not carry effects");
      }
      String contributorId = contribution.id().contributorId().value();
      List<CustomEntryPayload> payloads = new ArrayList<>(outcome.customEntries().size());
      for (AppendCustomEntry entry : outcome.customEntries()) {
        if (harnessCatalog
            .findCustomEntryType(contribution.id().contributorId(), entry.customType())
            .isEmpty()) {
          throw new IllegalArgumentException(
              "tool custom entry targets an unregistered custom entry type: " + entry.customType());
        }
        boolean declaredWrite = false;
        for (ContributorStateAccess access : binding.contributor().stateAccesses()) {
          if (access.customType().equals(entry.customType())
              && access.mode() == ContributorStateAccessMode.WRITE) {
            declaredWrite = true;
            break;
          }
        }
        if (!declaredWrite) {
          throw new IllegalArgumentException(
              "tool custom entry targets state without a declared WRITE access: "
                  + entry.customType());
        }
        payloads.add(
            new CustomEntryPayload(
                contributorId, entry.customType(), entry.schemaVersion(), entry.dataJson()));
      }
      return new ToolEffectBatch(payloads);
    }

    /** 将执行异常映射为终态；远程发送不确定性必须保持为 {@code unknown}。 */
    private boolean processError(Throwable error) {
      if (error == null) {
        return unknown(null, "the tool reported a null error");
      }
      if (error instanceof EnvironmentCapabilityCancelledException cancelled) {
        // 取消可能发生在副作用之后：只能声明结果未确认，绝不能声称未执行。
        deliverTerminal(
            () ->
                listener.onCancelled(
                    new ToolInvocationError(
                        CANCELLED_KIND,
                        ToolErrorGuidance.message(
                            failureMessage(cancelled, "Tool execution was cancelled"),
                            ExecutionFact.UNCERTAIN,
                            VERIFY_EFFECT_NEXT_ACTION))));
        return true;
      }
      if (error instanceof EnvironmentCapabilityFailedException failed) {
        deliverTerminal(
            () ->
                listener.onFailed(
                    new ToolGateway.Failure(
                        new ToolInvocationError(
                            EXECUTION_FAILED_KIND,
                            ToolErrorGuidance.message(
                                failureMessage(failed, "Tool execution failed"),
                                ExecutionFact.FAILED,
                                "Inspect the failure and adjust the request before calling the tool"
                                    + " again")),
                        false)));
        return true;
      }
      if (error instanceof EnvironmentCapabilitySendUncertainException uncertain) {
        deliverTerminal(
            () ->
                listener.onUnknown(
                    new ToolInvocationError(
                        REMOTE_UNCERTAIN_KIND,
                        ToolErrorGuidance.message(
                            failureMessage(uncertain, "Remote tool outcome is uncertain"),
                            ExecutionFact.UNCERTAIN,
                            VERIFY_EFFECT_NEXT_ACTION))));
        return true;
      }
      if (error instanceof EnvironmentCapabilityUnavailableException unavailable) {
        // transport 契约：unavailable 时调用肯定未执行。
        deliverTerminal(
            () ->
                listener.onFailed(
                    new ToolGateway.Failure(
                        new ToolInvocationError(
                            UNAVAILABLE_KIND,
                            ToolErrorGuidance.message(
                                failureMessage(unavailable, "Tool is unavailable"),
                                ExecutionFact.NOT_EXECUTED,
                                "Wait for the Environment to become available, then call the tool"
                                    + " again")),
                        true)));
        return true;
      }
      if (error instanceof EnvironmentCapabilityBusyException busy) {
        deliverTerminal(
            () ->
                listener.onFailed(
                    new ToolGateway.Failure(
                        new ToolInvocationError(
                            UNAVAILABLE_KIND,
                            ToolErrorGuidance.message(
                                failureMessage(busy, "Tool is busy"),
                                ExecutionFact.NOT_EXECUTED,
                                "Wait for the Environment to finish its current work, then call"
                                    + " the tool again")),
                        true)));
        return true;
      }
      return unknown(error, "the tool failed for an unclassified reason");
    }

    private boolean fail(ToolInvocationError error) {
      deliverTerminal(() -> listener.onFailed(new ToolGateway.Failure(error, false)));
      return true;
    }

    private boolean deliverPartialOrUnknown(Runnable callback) {
      try {
        callback.run();
        return false;
      } catch (RuntimeException failure) {
        return unknown(failure, "the listener failed to process a tool callback");
      }
    }

    private void deliverTerminal(Runnable callback) {
      try {
        callback.run();
      } catch (RuntimeException failure) {
        try {
          log.warn(
              "tool gateway listener threw while accepting a terminal callback for {}: {}; "
                  + "no further terminal will be issued",
              listener.getClass().getSimpleName(),
              safeMessage(failure));
        } catch (RuntimeException ignored) {
        }
      } finally {
        lease.close();
      }
    }

    private boolean unknown(Throwable failure, String whatFailed) {
      if (failure != null) {
        // 底层原始 message 只进日志，绝不进入模型可见错误文本。
        log.warn("tool gateway unknown outcome for tool {}: {}", toolName, safeMessage(failure));
      }
      ToolInvocationError error =
          new ToolInvocationError(
              EXECUTION_FAILED_KIND,
              ToolErrorGuidance.message(
                  whatFailed, ExecutionFact.UNCERTAIN, VERIFY_EFFECT_NEXT_ACTION));
      deliverTerminal(() -> listener.onUnknown(error));
      return true;
    }
  }

  private enum SignalKind {
    /** 局部中间执行结果信号。 */
    PARTIAL,

    /** 工具通过完成回调提交终态结果的信号。 */
    COMPLETE,

    /** 工具通过异常回调报告失败、取消或不确定结果的信号。 */
    ERROR
  }

  private record Signal(
      SignalKind kind, ToolResult partial, ToolOutcome outcome, Throwable error) {}
}
