package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.builtin.environment.EnvironmentCapabilityTool;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.tool.ToolErrorGuidance;
import fun.fengwk.kkstudio.harness.common.tool.ToolErrorGuidance.ExecutionFact;
import fun.fengwk.kkstudio.harness.contributor.api.AppendCustomEntry;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCancelledException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityFailedException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
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
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.catalog.tool.ToolBindingMismatchException;
import fun.fengwk.kkstudio.platform.harness.contributor.ContributorBranchViewLoader;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 生产工具执行网关，连接 Harness Runtime、运行时工具目录和 Contributor {@link Tool} SPI。
 *
 * <p>{@link #preflight} 校验冻结绑定、Environment 与权限；需要审批的文件编辑还会只读生成真实文件审查。{@link #admit}
 * 是纯本地检查：申请并发租约、钉住目录租用、装配分支视图与可选环境绑定，不触碰任何外部系统，因此 {@link ToolGateway.Busy} / {@link
 * ToolGateway.Rejected} 肯定没有副作用。
 *
 * <p>{@link #preflight} 与 {@link #admit} 都在当前目录租用中严格取回冻结贡献：工具已移除（例如插件被关闭）或定义、贡献身份、 requirements
 * 变化时以确定性错误告知模型，绝不按新定义执行旧参数。
 *
 * <p>{@link GatewayRun#execute} 在调用方的执行线程上阻塞调用 {@link Tool#execute}：中断映射为取消，工具异常按固定表分类（远程发送不确定性 上报
 * {@code unknown}，不按普通失败处理）。并发租约与目录租用只由 {@link GatewayRun#close()} 释放：Processor 在终态决议提交之后、
 * 执行体返回之后调用它，因此取消绝不代表工作线程已经退出，插件实例不会在仍被访问时被回收。
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
  static final String EDIT_REVIEW_REQUIRED_KIND = "EDIT_REVIEW_REQUIRED";

  private static final String PERMISSION_DENIED_MESSAGE =
      ToolErrorGuidance.message(
          "Tool permission was denied",
          ExecutionFact.NOT_EXECUTED,
          "Request approval or adjust the tool permission policy");

  /** 工具已不可用时的下一步：不要重试，由模型改用其它方式或询问用户。 */
  private static final String TOOL_UNAVAILABLE_NEXT_ACTION =
      "Continue without this tool or ask the user";

  /** 需要编辑评审时的下一步：先携带编辑评审请求审批，再调用工具。 */
  private static final String EDIT_REVIEW_REQUIRED_NEXT_ACTION =
      "Request approval with an edit review before calling the tool again";

  /** 冻结定义与当前 catalog 不一致时的确定性下一步：下一轮按当前目录重新冻结工具定义。 */
  private static final String DEFINITION_MISMATCH_NEXT_ACTION =
      "Call the tool again; the next turn uses its current definition";

  /** 确定性拒绝的下一步：同一调用在修复前会得到同样结果，不构成自动重试建议。 */
  private static final String DETERMINISTIC_REJECTION_NEXT_ACTION =
      "Correct the underlying contract violation before calling the tool again";

  /** 结果不确定时的下一步：先核查副作用再决定，绝不能假设未执行。 */
  private static final String VERIFY_EFFECT_NEXT_ACTION =
      "Check whether the tool already took effect before calling it again";

  private final RuntimeToolCatalogSource toolCatalogSource;
  private final ContributorBranchViewLoader contributorBranchViewLoader;
  private final EnvironmentCapabilityTransport capabilityTransport;
  private final PermissionEvaluator permissionEvaluator;
  private final ToolSettingsProvider toolSettingsProvider;
  private final ToolResultFinalizer finalizer;
  private final Supplier<Duration> retryDelay;
  private final Clock clock;
  private final ConcurrencyAdmission admission;

  public ToolExecutionGateway(
      RuntimeToolCatalogSource toolCatalogSource,
      ContributorBranchViewLoader contributorBranchViewLoader,
      EnvironmentCapabilityTransport capabilityTransport,
      PermissionEvaluator permissionEvaluator,
      ToolSettingsProvider toolSettingsProvider,
      ToolResourceStager resourceStager,
      int resourceMaxBytes,
      Supplier<Duration> retryDelay,
      Clock clock,
      ConcurrencyAdmission admission) {
    this.toolCatalogSource = Objects.requireNonNull(toolCatalogSource, "toolCatalogSource");
    this.contributorBranchViewLoader =
        Objects.requireNonNull(contributorBranchViewLoader, "contributorBranchViewLoader");
    this.capabilityTransport = Objects.requireNonNull(capabilityTransport, "capabilityTransport");
    this.permissionEvaluator = Objects.requireNonNull(permissionEvaluator, "permissionEvaluator");
    this.toolSettingsProvider =
        Objects.requireNonNull(toolSettingsProvider, "toolSettingsProvider");
    this.finalizer =
        new ToolResultFinalizer(
            Objects.requireNonNull(resourceStager, "resourceStager"), resourceMaxBytes);
    this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.admission = Objects.requireNonNull(admission, "admission");
  }

  private record ResolvedContribution(ToolContribution contribution, ToolInvocationError error) {}

  /**
   * 严格从本次租用取回冻结贡献：完整定义、贡献身份与 requirements 都必须一致。
   *
   * <p>任何不一致都映射为确定性拒绝，绝不按当前同名工具重放，也绝不因此产生任何外部副作用。
   */
  private ResolvedContribution requireContribution(
      RuntimeToolCatalogSource.Lease lease, ToolBinding binding) {
    try {
      return new ResolvedContribution(lease.require(binding), null);
    } catch (ToolBindingMismatchException mismatch) {
      String toolName = binding.descriptor().name();
      ToolInvocationError error =
          mismatch.reason() == ToolBindingMismatchException.Reason.NOT_FOUND
              ? new ToolInvocationError(
                  TOOL_NOT_FOUND_KIND,
                  ToolErrorGuidance.message(
                      "Tool "
                          + toolName
                          + " is no longer available; its plugin may have been"
                          + " disabled or removed",
                      ExecutionFact.NOT_EXECUTED,
                      TOOL_UNAVAILABLE_NEXT_ACTION))
              : new ToolInvocationError(
                  TOOL_DEFINITION_MISMATCH_KIND,
                  ToolErrorGuidance.message(
                      "Tool " + toolName + " changed after this call was planned",
                      ExecutionFact.NOT_EXECUTED,
                      DEFINITION_MISMATCH_NEXT_ACTION));
      return new ResolvedContribution(null, error);
    }
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

  /** 只读校验冻结工具绑定，并返回当前权限策略的判定。 */
  @Override
  public PreflightResult preflight(ToolInvocationRequest request) {
    Objects.requireNonNull(request, "request");
    // 校验全程持有一次目录租用：工具定义、权限身份与 state access 都来自同一份快照。
    try (RuntimeToolCatalogSource.Lease lease = toolCatalogSource.acquire()) {
      ResolvedContribution resolved = requireContribution(lease, request.binding());
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
        case ASK -> {
          String reason = "Permission rules require approval";
          yield resolved.contribution().tool() instanceof EnvironmentCapabilityTool environmentTool
                  && EnvironmentCapabilityIds.FS_EDIT.equals(environmentTool.capability().id())
              ? EditReviewPreflight.prepare(capabilityTransport, request, reason)
              : new ToolGateway.Ask(reason);
        }
        case DENY -> new ToolGateway.Deny(
            new ToolInvocationError(PERMISSION_DENIED_KIND, PERMISSION_DENIED_MESSAGE));
      };
    }
  }

  /** 获取并发与目录租约并装配本次执行；实际工具调用发生在返回的 {@link GatewayRun#execute} 内。 */
  @Override
  public Admission admit(Execution execution) {
    Objects.requireNonNull(execution, "execution");
    Optional<ConcurrencyAdmission.Lease> acquired = admission.tryAcquire();
    if (acquired.isEmpty()) {
      return new ToolGateway.Busy(retryDelay.get());
    }
    ConcurrencyAdmission.Lease lease = acquired.orElseThrow();
    RuntimeToolCatalogSource.Lease catalogLease = null;
    try {
      // Admitted 的目录租用必须转移到执行生命周期：它钉住本次调用仍可能访问的 Plugin 实例，绝不在 admit 返回时释放。
      catalogLease = toolCatalogSource.acquire();
      ResolvedContribution resolved =
          requireContribution(catalogLease, execution.request().binding());
      if (resolved.error() != null) {
        return reject(catalogLease, lease, resolved.error());
      }
      ToolContribution contribution = resolved.contribution();
      Tool tool = contribution.tool();
      ToolDescriptor currentDescriptor = tool == null ? null : tool.descriptor();
      if (!Objects.equals(
          currentDescriptor, execution.request().binding().definition().descriptor())) {
        return reject(
            catalogLease,
            lease,
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
        return reject(catalogLease, lease, environmentNotSelected);
      }

      if (tool instanceof EnvironmentCapabilityTool environmentTool
          && EnvironmentCapabilityIds.FS_EDIT.equals(environmentTool.capability().id())
          && execution.approval().required()
          && execution.expectedFile() == null) {
        return reject(
            catalogLease,
            lease,
            new ToolInvocationError(
                EDIT_REVIEW_REQUIRED_KIND,
                ToolErrorGuidance.message(
                    "Required edit approval must include a valid edit review",
                    ExecutionFact.NOT_EXECUTED,
                    EDIT_REVIEW_REQUIRED_NEXT_ACTION)));
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
                  execution.request().call().id(),
                  execution.expectedFile())
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
        return reject(
            catalogLease,
            lease,
            new ToolInvocationError(
                INVALID_REQUEST_KIND,
                ToolErrorGuidance.message(
                    failureMessage(invalidRequest, "Tool request is invalid"),
                    ExecutionFact.NOT_EXECUTED,
                    "Correct the tool arguments, then call the tool again")));
      }
      return new ToolGateway.Admitted(
          new GatewayRun(
              tool,
              toolRequest,
              finalizer,
              currentDescriptor.name(),
              contribution,
              execution.request().binding(),
              catalogLease,
              lease));
    } catch (RuntimeException | Error failure) {
      releaseAfterFailure(catalogLease, lease, failure);
      throw failure;
    }
  }

  /** 释放目录租用与并发租用；即使目录关闭失败，并发配额仍独立释放。 */
  private static void release(
      RuntimeToolCatalogSource.Lease catalogLease, ConcurrencyAdmission.Lease admissionLease) {
    try {
      if (catalogLease != null) {
        catalogLease.close();
      }
    } catch (RuntimeException | Error failure) {
      try {
        admissionLease.close();
      } catch (RuntimeException | Error cleanup) {
        if (cleanup != failure) {
          failure.addSuppressed(cleanup);
        }
      }
      throw failure;
    }
    admissionLease.close();
  }

  /** 已有主异常时，清理失败只附加到原异常，不替换它。 */
  private static void releaseAfterFailure(
      RuntimeToolCatalogSource.Lease catalogLease,
      ConcurrencyAdmission.Lease admissionLease,
      Throwable failure) {
    try {
      release(catalogLease, admissionLease);
    } catch (RuntimeException | Error cleanup) {
      if (cleanup != failure) {
        failure.addSuppressed(cleanup);
      }
    }
  }

  /** 拒绝路径：目录租用与并发租用各释放一次，返回确定性拒绝。 */
  private static ToolGateway.Rejected reject(
      RuntimeToolCatalogSource.Lease catalogLease,
      ConcurrencyAdmission.Lease admissionLease,
      ToolInvocationError error) {
    release(catalogLease, admissionLease);
    return new ToolGateway.Rejected(error);
  }

  /**
   * Platform 的 {@link BoundEnvironment} 适配实现：把异步 {@link EnvironmentCapabilityTransport} 收敛为阻塞调用。
   */
  private static final class PlatformBoundEnvironment implements BoundEnvironment {
    private final EnvironmentId environmentId;
    private final EnvironmentCapabilityTransport capabilityTransport;
    private final String invocationId;
    private final String modelCallId;
    private final ExpectedFile expectedFile;

    private PlatformBoundEnvironment(
        EnvironmentId environmentId,
        EnvironmentCapabilityTransport capabilityTransport,
        String invocationId,
        String modelCallId,
        ExpectedFile expectedFile) {
      this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
      this.capabilityTransport = Objects.requireNonNull(capabilityTransport, "capabilityTransport");
      this.invocationId = Objects.requireNonNull(invocationId, "invocationId");
      this.modelCallId = Objects.requireNonNull(modelCallId, "modelCallId");
      this.expectedFile = expectedFile;
    }

    @Override
    public EnvironmentId environmentId() {
      return environmentId;
    }

    /**
     * 发送并阻塞等待唯一终态。发送前异常（Busy / Unavailable / SendUncertain）原样向上抛出，由 Gateway 按类型分类；调用线程被中断时向远端
     * 请求取消并立即抛出 {@link InterruptedException}，不等待远端确认。
     */
    @Override
    public ToolResult execute(
        EnvironmentCapabilityDescriptor capability,
        ToolExecutionRequest request,
        Consumer<ToolResult> progress)
        throws InterruptedException {
      Objects.requireNonNull(capability, "capability");
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(progress, "progress");
      CompletableFuture<EnvironmentCapabilityResult> terminal = new CompletableFuture<>();
      EnvironmentCapabilityExecutionListener listener =
          new EnvironmentCapabilityExecutionListener() {
            @Override
            public void onPartial(EnvironmentCapabilityResult partial) {
              progress.accept(toToolResult(partial));
            }

            @Override
            public void onComplete(EnvironmentCapabilityResult result) {
              terminal.complete(result);
            }

            @Override
            public void onError(Throwable error) {
              terminal.completeExceptionally(error);
            }
          };
      EnvironmentCapabilityCall capabilityCall =
          new EnvironmentCapabilityCall(invocationId, request.call().argumentsJson());
      EnvironmentCapabilityExecutionRequest capabilityRequest =
          new EnvironmentCapabilityExecutionRequest(
              capability, capabilityCall, request.timeout(), expectedFile);
      EnvironmentCapabilityExecutionHandle transportHandle =
          capabilityTransport.invoke(environmentId, capabilityRequest, listener);
      if (transportHandle == null) {
        throw new IllegalStateException(
            "remote capability returned a null execution handle; outcome cannot be confirmed");
      }
      EnvironmentCapabilityResult result;
      try {
        result = terminal.get();
      } catch (InterruptedException interrupted) {
        transportHandle.cancel();
        throw interrupted;
      } catch (ExecutionException failed) {
        if (failed.getCause() instanceof RuntimeException runtime) {
          throw runtime;
        }
        throw new IllegalStateException(
            "remote capability failed with a non-runtime error; outcome cannot be confirmed",
            failed.getCause());
      }
      if (result == null) {
        throw new IllegalStateException(
            "remote capability completed without a result; outcome cannot be confirmed");
      }
      return toToolResult(result);
    }

    private ToolResult toToolResult(EnvironmentCapabilityResult result) {
      String toolCallId = result.callId();
      if (invocationId.equals(toolCallId)) {
        toolCallId = modelCallId;
      } else if (modelCallId.equals(toolCallId)) {
        toolCallId = invocationId;
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

  /**
   * 一次已准入的阻塞执行：在调用方线程上调用工具并把结果与异常分类为 {@link ToolGateway.Completion}。
   *
   * <p>并发租约与目录租用只由 {@link #close()} 释放；Processor 保证它在终态决议提交之后、{@link #execute} 返回（或抛出）之后才被调用。
   * 工具上报非法 partial 时中断执行线程并以确定性失败收敛：执行已开始，因此只声明结果未交付且不可确认，不能声称未执行。
   */
  static final class GatewayRun implements ToolGateway.Run {
    private final Tool tool;
    private final ToolExecutionRequest request;
    private final ToolResultFinalizer finalizer;
    private final String toolName;
    private final String expectedCallId;
    private final HarnessCatalog harnessCatalog;
    private final ToolContribution contribution;
    private final ToolBinding binding;
    private final RuntimeToolCatalogSource.Lease catalogLease;
    private final ConcurrencyAdmission.Lease admissionLease;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<ToolInvocationError> rejectedPartial = new AtomicReference<>();
    private final Object executionLock = new Object();
    private Thread executingThread;

    GatewayRun(
        Tool tool,
        ToolExecutionRequest request,
        ToolResultFinalizer finalizer,
        String toolName,
        ToolContribution contribution,
        ToolBinding binding,
        RuntimeToolCatalogSource.Lease catalogLease,
        ConcurrencyAdmission.Lease admissionLease) {
      this.tool = Objects.requireNonNull(tool, "tool");
      this.request = Objects.requireNonNull(request, "request");
      this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
      this.toolName = Objects.requireNonNull(toolName, "toolName");
      this.expectedCallId = request.call().id();
      this.catalogLease = Objects.requireNonNull(catalogLease, "catalogLease");
      this.harnessCatalog = catalogLease.harnessCatalog();
      this.contribution = Objects.requireNonNull(contribution, "contribution");
      this.binding = Objects.requireNonNull(binding, "binding");
      this.admissionLease = Objects.requireNonNull(admissionLease, "admissionLease");
    }

    @Override
    public ToolGateway.Completion execute(Consumer<ToolResult> progress) {
      Objects.requireNonNull(progress, "progress");
      synchronized (executionLock) {
        executingThread = Thread.currentThread();
      }
      ToolGateway.Completion completion;
      try {
        completion = invoke(progress);
      } finally {
        synchronized (executionLock) {
          executingThread = null;
        }
      }
      ToolInvocationError invalid = rejectedPartial.get();
      return invalid == null ? completion : new ToolGateway.Failed(invalid, false);
    }

    private ToolGateway.Completion invoke(Consumer<ToolResult> progress) {
      try {
        return complete(tool.execute(request, guard(progress)));
      } catch (InterruptedException interrupted) {
        // 取消可能发生在副作用之后：只能声明结果未确认，绝不能声称未执行。
        return new ToolGateway.Cancelled(
            new ToolInvocationError(
                CANCELLED_KIND,
                ToolErrorGuidance.message(
                    "Tool execution was cancelled",
                    ExecutionFact.UNCERTAIN,
                    VERIFY_EFFECT_NEXT_ACTION)));
      } catch (RuntimeException error) {
        return classify(error);
      }
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        release(catalogLease, admissionLease);
      }
    }

    /** 校验 partial；非法即记录并中断执行线程（只在执行体内部投递），由 {@link #execute} 收敛为确定性失败。 */
    private Consumer<ToolResult> guard(Consumer<ToolResult> progress) {
      return partial -> {
        ToolInvocationError invalid = validatePartial(partial);
        if (invalid == null) {
          progress.accept(partial);
          return;
        }
        if (rejectedPartial.compareAndSet(null, invalid)) {
          synchronized (executionLock) {
            if (executingThread != null) {
              executingThread.interrupt();
            }
          }
        }
      };
    }

    private ToolInvocationError validatePartial(ToolResult partial) {
      if (partial == null) {
        return invalidPartial("partial must not be null");
      }
      if (!partial.toolCallId().equals(expectedCallId)) {
        return invalidPartial("partial toolCallId does not match the request call");
      }
      for (ResultContent content : partial.contents()) {
        if (content instanceof BinaryResultContent || content instanceof ResourceResultContent) {
          return invalidPartial("partial must not carry binary or resource content");
        }
      }
      if (ToolResultJsonCodec.exceedsEncodedUtf8Bytes(
          partial, ToolResultSizeLimits.MAX_PARTIAL_RESULT_UTF8_BYTES)) {
        return invalidPartial(
            "partial must not exceed "
                + ToolResultSizeLimits.MAX_PARTIAL_RESULT_UTF8_BYTES
                + " bytes of canonical tool result JSON");
      }
      return null;
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

    private ToolGateway.Completion complete(ToolOutcome outcome) {
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
        case ToolResultFinalizer.Outcome.Success success -> new ToolGateway.Succeeded(
            new ToolSuccess(success.result(), effects));
        case ToolResultFinalizer.Outcome.Failed failed -> fail(failed.error());
        case ToolResultFinalizer.Outcome.StoreFailed storeFailed -> new ToolGateway.Unknown(
            storeFailed.error());
      };
    }

    /** 仅接受已注册且由当前工具声明 WRITE 权限的自定义条目。 */
    private ToolEffectBatch mapEffects(ToolOutcome outcome) {
      // ToolOutcome 已保证 error 结果不携带 effects。
      if (outcome.customEntries().isEmpty()) {
        return ToolEffectBatch.EMPTY;
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
    private ToolGateway.Completion classify(RuntimeException error) {
      if (error instanceof EnvironmentCapabilityCancelledException cancelled) {
        // 取消可能发生在副作用之后：只能声明结果未确认，绝不能声称未执行。
        return new ToolGateway.Cancelled(
            new ToolInvocationError(
                CANCELLED_KIND,
                ToolErrorGuidance.message(
                    failureMessage(cancelled, "Tool execution was cancelled"),
                    ExecutionFact.UNCERTAIN,
                    VERIFY_EFFECT_NEXT_ACTION)));
      }
      if (error instanceof EnvironmentCapabilityFailedException failed) {
        return new ToolGateway.Failed(
            new ToolInvocationError(
                EXECUTION_FAILED_KIND,
                ToolErrorGuidance.message(
                    failureMessage(failed, "Tool execution failed"),
                    ExecutionFact.FAILED,
                    "Inspect the failure and adjust the request before calling the tool"
                        + " again")),
            false);
      }
      if (error instanceof EnvironmentCapabilitySendUncertainException uncertain) {
        return new ToolGateway.Unknown(
            new ToolInvocationError(
                REMOTE_UNCERTAIN_KIND,
                ToolErrorGuidance.message(
                    failureMessage(uncertain, "Remote tool outcome is uncertain"),
                    ExecutionFact.UNCERTAIN,
                    VERIFY_EFFECT_NEXT_ACTION)));
      }
      if (error instanceof EnvironmentCapabilityUnavailableException unavailable) {
        // transport 契约：unavailable 时调用肯定未执行。
        return new ToolGateway.Failed(
            new ToolInvocationError(
                UNAVAILABLE_KIND,
                ToolErrorGuidance.message(
                    failureMessage(unavailable, "Tool is unavailable"),
                    ExecutionFact.NOT_EXECUTED,
                    "Wait for the Environment to become available, then call the tool" + " again")),
            true);
      }
      if (error instanceof EnvironmentCapabilityBusyException busy) {
        return new ToolGateway.Failed(
            new ToolInvocationError(
                UNAVAILABLE_KIND,
                ToolErrorGuidance.message(
                    failureMessage(busy, "Tool is busy"),
                    ExecutionFact.NOT_EXECUTED,
                    "Wait for the Environment to finish its current work, then call"
                        + " the tool again")),
            true);
      }
      return unknown(error, "the tool failed for an unclassified reason");
    }

    private ToolGateway.Completion fail(ToolInvocationError error) {
      return new ToolGateway.Failed(error, false);
    }

    private ToolGateway.Completion unknown(Throwable failure, String whatFailed) {
      // 底层原始 message 只进日志，绝不进入模型可见错误文本。
      log.warn("tool gateway unknown outcome for tool {}: {}", toolName, safeMessage(failure));
      return new ToolGateway.Unknown(
          new ToolInvocationError(
              EXECUTION_FAILED_KIND,
              ToolErrorGuidance.message(
                  whatFailed, ExecutionFact.UNCERTAIN, VERIFY_EFFECT_NEXT_ACTION)));
    }
  }
}
