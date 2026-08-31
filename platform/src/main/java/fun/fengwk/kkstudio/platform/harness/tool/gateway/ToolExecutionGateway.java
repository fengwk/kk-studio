package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.AppendCustomEntry;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
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
import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
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
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionPromptPreview;
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
import fun.fengwk.kkstudio.platform.harness.ExecutorSafety;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 生产 {@link ToolGateway}：基于单一 Contributor {@link Tool} SPI 的权限 preflight 与 admission 路由。
 *
 * <p>{@link #preflight} 是纯判定：先由 catalog 按冻结 definition id 恢复 ToolContribution/AgentToolId， 校验
 * definition、contributor provenance 与 requirements 不变；再用 {@link PermissionEvaluator} + 部署 {@link
 * ToolSettings} + 配置的 workdir/environmentRoot 评估冻结的 call/binding；缺失 contribution 或 definition
 * 漂移直接生成确定性 Deny，绝不进入 evaluator，也不改写 binding/arguments。YOLO 短路由由 Runtime 决定，本类不感知 YOLO 也不查询
 * HarnessStore。
 *
 * <p>{@link #start} 采用单一执行路径：查找 {@link ToolContribution}，全量校验冻结 definition + contributor provenance
 * + requirements，构建作用域受限的 {@link BranchView} 与可选 {@link BoundEnvironment}，构造 {@link
 * ToolExecutionContext}， 并通过两阶段门控回调桥提交 {@link Tool#execute} 执行。
 */
@Slf4j
public final class ToolExecutionGateway implements ToolGateway {

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

  /** 回调桥缓冲队列的保守上限：gate 打开前的同步回调绝不能无界缓冲。 */
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
  private final Supplier<Duration> retryDelay;
  private final Clock clock;
  private final ConcurrencyAdmission admission;

  public ToolExecutionGateway(
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
      Supplier<Duration> retryDelay,
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
    this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.admission = Objects.requireNonNull(admission, "admission");
    ExecutorSafety.requireSafeAsyncExecutor(executor, "tool gateway");
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
    ToolRequirements requirements = contribution.requirements();
    if (binding.environmentRequired() != requirements.environmentRequired()) {
      return new ResolvedContribution(
          null,
          new ToolInvocationError(
              TOOL_DEFINITION_MISMATCH_KIND,
              "Frozen tool environment requirement does not match catalog requirements: "
                  + frozenDefinition.id()));
    }
    if (!stateAccessesMatch(frozenContributor, requirements)) {
      return new ResolvedContribution(
          null,
          new ToolInvocationError(
              TOOL_DEFINITION_MISMATCH_KIND,
              "Tool definition "
                  + frozenDefinition.id()
                  + " no longer matches its frozen state access declaration."));
    }
    return new ResolvedContribution(contribution, null);
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

  private Path permissionWorkdir(ToolInvocationRequest request) {
    if (request.binding().environmentRequired() && request.binding().environment() != null) {
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
                "Registered tool descriptor does not match frozen descriptor: "
                    + contribution.definition().id()));
      }
      if (execution.request().binding().environmentRequired()
          && execution.request().binding().environment() == null) {
        lease.close();
        return new ToolGateway.Rejected(
            new ToolInvocationError(
                UNAVAILABLE_KIND,
                "Environment tool "
                    + contribution.definition().id()
                    + " has no environment binding (the branch has no selected environment)."));
      }

      BranchView branch =
          contributorBranchViewLoader.load(
              execution.assistantEntryId(), contribution.id().contributorId().value());
      BoundEnvironment boundEnvironment =
          execution.request().binding().environmentRequired()
              ? new PlatformBoundEnvironment(
                  execution.request().binding().environment(),
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
      Path executionWorkdir = permissionWorkdir(execution.request());
      ToolExecutionRequest toolRequest =
          new ToolExecutionRequest(
              currentDescriptor,
              execution.request().call(),
              Duration.ZERO,
              context,
              executionWorkdir);

      GatedToolExecutionListener bridge =
          new GatedToolExecutionListener(
              listener,
              externalizer,
              currentDescriptor.name(),
              toolRequest.call().id(),
              lease,
              catalog,
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
              failureMessage(
                  ambiguous, "Tool execution submission failed; outcome cannot be confirmed.")));
    }
    return new ToolGateway.Started(handle);
  }

  /** Platform 的 {@link BoundEnvironment} 适配实现，桥接底层的 {@link EnvironmentCapabilityTransport}。 */
  private static final class PlatformBoundEnvironment implements BoundEnvironment {
    private final EnvironmentBinding binding;
    private final EnvironmentCapabilityTransport capabilityTransport;
    private final String invocationId;
    private final String modelCallId;

    private PlatformBoundEnvironment(
        EnvironmentBinding binding,
        EnvironmentCapabilityTransport capabilityTransport,
        String invocationId,
        String modelCallId) {
      this.binding = Objects.requireNonNull(binding, "binding");
      this.capabilityTransport = Objects.requireNonNull(capabilityTransport, "capabilityTransport");
      this.invocationId = Objects.requireNonNull(invocationId, "invocationId");
      this.modelCallId = Objects.requireNonNull(modelCallId, "modelCallId");
    }

    @Override
    public EnvironmentBinding binding() {
      return binding;
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
                capability, capabilityCall, request.timeout(), null);
        EnvironmentCapabilityExecutionHandle transportHandle =
            capabilityTransport.invoke(binding, capabilityRequest, capabilityListener);
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

  /**
   * Tool 回调门控桥：支持单一 {@link ToolExecutionListener}，提供两阶段激活、缓冲保护、 FIFO 串行分发、自定义 effects 所有权与 WRITE
   * 声明校验、Managed Resource 外部化与 terminal-once 回调语义。
   */
  private static final class GatedToolExecutionListener implements ToolExecutionListener {
    private final ToolGateway.Listener listener;
    private final ToolResultExternalizer externalizer;
    private final String toolName;
    private final String expectedCallId;
    private final ConcurrencyAdmission.Lease lease;
    private final HarnessCatalog catalog;
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
        ToolResultExternalizer externalizer,
        String toolName,
        String expectedCallId,
        ConcurrencyAdmission.Lease lease,
        HarnessCatalog catalog,
        ToolContribution contribution,
        ToolBinding binding) {
      this.listener = Objects.requireNonNull(listener, "listener");
      this.externalizer = Objects.requireNonNull(externalizer, "externalizer");
      this.toolName = Objects.requireNonNull(toolName, "toolName");
      this.expectedCallId = Objects.requireNonNull(expectedCallId, "expectedCallId");
      this.lease = Objects.requireNonNull(lease, "lease");
      this.catalog = Objects.requireNonNull(catalog, "catalog");
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
                  "tool callback buffer overflowed; outcome cannot be confirmed");
        } else {
          try {
            terminalSignal = process(signal);
          } catch (RuntimeException failure) {
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

    private boolean process(Signal signal) {
      return switch (signal.kind) {
        case PARTIAL -> processPartial(signal.partial);
        case COMPLETE -> processComplete(signal.outcome);
        case ERROR -> processError(signal.error);
      };
    }

    private boolean processPartial(ToolResult partial) {
      if (partial == null) {
        return fail(new ToolInvocationError(INVALID_PARTIAL_KIND, "partial must not be null"));
      }
      if (!partial.toolCallId().equals(expectedCallId)) {
        return fail(
            new ToolInvocationError(
                INVALID_PARTIAL_KIND, "partial toolCallId does not match the request call"));
      }
      for (ResultContent content : partial.contents()) {
        if (content instanceof BinaryResultContent || content instanceof ResourceResultContent) {
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
      return deliverPartialOrUnknown(() -> listener.onPartial(partial));
    }

    private boolean processComplete(ToolOutcome outcome) {
      if (outcome == null || outcome.result() == null) {
        return fail(
            new ToolInvocationError(
                ToolResultExternalizer.INVALID_RESULT_KIND, "terminal result must not be null"));
      }
      ToolResult result = outcome.result();
      if (!result.toolCallId().equals(expectedCallId)) {
        return fail(
            new ToolInvocationError(
                ToolResultExternalizer.INVALID_RESULT_KIND,
                "terminal result toolCallId does not match the request call"));
      }
      ToolEffectBatch effects;
      try {
        effects = mapEffects(outcome);
      } catch (RuntimeException invalid) {
        return fail(
            new ToolInvocationError(
                CONTRIBUTOR_CONTRACT_VIOLATION_KIND,
                failureMessage(invalid, "Tool outcome returned invalid custom entries.")));
      }
      ToolResultExternalizer.Outcome externalized;
      try {
        externalized = externalizer.externalize(toolName, result);
      } catch (RuntimeException failure) {
        return unknown(failure, "tool result externalization failed; outcome cannot be confirmed");
      }
      return switch (externalized) {
        case ToolResultExternalizer.Outcome.Success success -> {
          deliverTerminal(() -> listener.onSucceeded(new ToolSuccess(success.result(), effects)));
          yield true;
        }
        case ToolResultExternalizer.Outcome.Invalid invalid -> fail(invalid.error());
        case ToolResultExternalizer.Outcome.StoreFailed storeFailed -> {
          deliverTerminal(() -> listener.onUnknown(storeFailed.error()));
          yield true;
        }
      };
    }

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
        if (catalog
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

    private boolean processError(Throwable error) {
      if (error == null) {
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
        deliverTerminal(
            () ->
                listener.onFailed(
                    new ToolGateway.Failure(
                        new ToolInvocationError(
                            UNAVAILABLE_KIND, failureMessage(unavailable, "Tool is unavailable.")),
                        true)));
        return true;
      }
      if (error instanceof EnvironmentCapabilityBusyException busy) {
        deliverTerminal(
            () ->
                listener.onFailed(
                    new ToolGateway.Failure(
                        new ToolInvocationError(
                            UNAVAILABLE_KIND, failureMessage(busy, "Tool is busy.")),
                        true)));
        return true;
      }
      return unknown(error, "unclassified tool failure; outcome cannot be confirmed");
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
        return unknown(
            failure, "listener failed to process tool callback; outcome cannot be confirmed");
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

    private boolean unknown(Throwable failure, String reason) {
      String detail = safeMessage(failure);
      ToolInvocationError error =
          new ToolInvocationError(
              EXECUTION_FAILED_KIND, detail == null ? reason : reason + ": " + detail);
      deliverTerminal(() -> listener.onUnknown(error));
      return true;
    }
  }

  private enum SignalKind {
    PARTIAL,
    COMPLETE,
    ERROR
  }

  private record Signal(
      SignalKind kind, ToolResult partial, ToolOutcome outcome, Throwable error) {}
}
