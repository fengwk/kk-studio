package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.builtin.environment.EnvironmentCapabilityTool;
import fun.fengwk.kkstudio.harness.contributor.builtin.environment.FileTool;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.harness.spi.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance.ExecutionFact;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.catalog.tool.ToolBindingMismatchException;
import fun.fengwk.kkstudio.platform.harness.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.platform.harness.tool.permission.PermissionAction;
import fun.fengwk.kkstudio.platform.harness.tool.permission.PermissionEvaluationContext;
import fun.fengwk.kkstudio.platform.harness.tool.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.platform.harness.tool.permission.ToolSettings;
import fun.fengwk.kkstudio.platform.harness.tool.permission.ToolSettingsProvider;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 生产工具执行网关的预检与准入，连接 Harness Runtime、运行时工具目录和 Contributor {@link Tool} SPI。
 *
 * <p>{@link #preflight} 校验冻结绑定、权限，并在需要审批的文件编辑上生成真实文件审查。{@link #admit} 是纯本地检查：申请并发租约、钉住目录租用、
 * 装配可选环境绑定，不触碰任何外部系统，也不查询 Harness 存储（分支视图、会话与冻结媒体能力由 Harness 随执行描述传入），因此 {@link ToolGateway.Busy} /
 * {@link ToolGateway.Rejected} 肯定没有副作用。
 *
 * <p>{@link #preflight} 与 {@link #admit} 都在当前目录租用中严格取回冻结贡献：工具不存在或已被停用（例如插件被关闭）或定义、贡献身份、
 * requirements 变化时以确定性错误告知模型，绝不按新定义执行旧参数。只读命名空间的写入在审批与权限评估之前确定性拒绝；YOLO 跳过 preflight 直接从 {@link
 * #admit} 进入执行，因此该拒绝在准入阶段同样生效。
 *
 * <p>远端执行、终态装配与错误分类分别由 {@link RemoteToolExecution}、{@link AbstractToolRun} 与 {@link
 * ToolErrorClassifier} 负责。
 */
public final class ToolExecutionGateway implements ToolGateway {

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

  private final RuntimeToolCatalogSource toolCatalogSource;
  private final PermissionEvaluator permissionEvaluator;
  private final ToolSettingsProvider toolSettingsProvider;
  private final ToolResultFinalizer finalizer;
  private final EnvironmentCapabilityTransport capabilityTransport;
  private final RemoteToolExecution remoteExecution;
  private final Supplier<Duration> retryDelay;
  private final Clock clock;
  private final ConcurrencyAdmission admission;

  public ToolExecutionGateway(
      RuntimeToolCatalogSource toolCatalogSource,
      EnvironmentCapabilityTransport capabilityTransport,
      PermissionEvaluator permissionEvaluator,
      ToolSettingsProvider toolSettingsProvider,
      ToolResourceStager resourceStager,
      int resourceMaxBytes,
      Supplier<Duration> retryDelay,
      Clock clock,
      ConcurrencyAdmission admission) {
    this.toolCatalogSource = Objects.requireNonNull(toolCatalogSource, "toolCatalogSource");
    this.permissionEvaluator = Objects.requireNonNull(permissionEvaluator, "permissionEvaluator");
    this.toolSettingsProvider =
        Objects.requireNonNull(toolSettingsProvider, "toolSettingsProvider");
    this.finalizer =
        new ToolResultFinalizer(
            Objects.requireNonNull(resourceStager, "resourceStager"), resourceMaxBytes);
    this.capabilityTransport = Objects.requireNonNull(capabilityTransport, "capabilityTransport");
    this.remoteExecution = new RemoteToolExecution(capabilityTransport, finalizer);
    this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.admission = Objects.requireNonNull(admission, "admission");
  }

  private record ResolvedContribution(ToolContribution contribution, ToolInvocationError error) {}

  /**
   * 严格从本次租用取回冻结贡献：完整定义、贡献身份与 requirements 都必须一致。
   *
   * <p>任何不一致都映射为确定性拒绝，绝不按当前同名工具重放，也绝不因此产生任何外部副作用。{@link RuntimeToolCatalogSource.Lease#require}
   * 已校验完整定义与贡献身份，因此执行请求中的 descriptor 直接取冻结 binding。
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
                  ToolErrorClassifier.TOOL_NOT_FOUND_KIND,
                  ToolErrorGuidance.message(
                      "Tool "
                          + toolName
                          + " is no longer available; its plugin may have been"
                          + " disabled or removed",
                      ExecutionFact.NOT_EXECUTED,
                      TOOL_UNAVAILABLE_NEXT_ACTION))
              : new ToolInvocationError(
                  ToolErrorClassifier.TOOL_DEFINITION_MISMATCH_KIND,
                  ToolErrorGuidance.message(
                      "Tool " + toolName + " changed after this call was planned",
                      ExecutionFact.NOT_EXECUTED,
                      DEFINITION_MISMATCH_NEXT_ACTION));
      return new ResolvedContribution(null, error);
    }
  }

  /** 编辑身份：环境 edit 能力工具或逐调用路由的 edit 文件工具。 */
  private static boolean isEditTool(Tool tool) {
    if (tool instanceof EnvironmentCapabilityTool environmentTool) {
      return EnvironmentCapabilityIds.FS_EDIT.equals(environmentTool.capability().id());
    }
    return tool instanceof FileTool fileTool && fileTool.operation() == FileTool.Operation.EDIT;
  }

  /** 只读校验冻结工具绑定，并返回当前权限策略的判定。 */
  @Override
  public PreflightResult preflight(ToolInvocationRequest request, ExecutionScope scope) {
    Objects.requireNonNull(request, "request");
    // 校验全程持有一次目录租用：工具定义、权限身份与 state access 都来自同一份快照。
    try (RuntimeToolCatalogSource.Lease lease = toolCatalogSource.acquire()) {
      ResolvedContribution resolved = requireContribution(lease, request.binding());
      if (resolved.error() != null) {
        return new ToolGateway.Deny(resolved.error());
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
          if (isEditTool(resolved.contribution().tool())) {
            yield EditReviewPreflight.prepare(capabilityTransport, request, reason);
          }
          yield new ToolGateway.Ask(reason);
        }
        case DENY -> new ToolGateway.Deny(
            new ToolInvocationError(
                ToolErrorClassifier.PERMISSION_DENIED_KIND, PERMISSION_DENIED_MESSAGE));
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
      ToolBinding binding = execution.request().binding();
      // 编辑的审批必须携带 Environment 返回的真实文件审查。
      if (isEditTool(tool) && execution.approval().required() && execution.expectedFile() == null) {
        return reject(
            catalogLease,
            lease,
            new ToolInvocationError(
                ToolErrorClassifier.EDIT_REVIEW_REQUIRED_KIND,
                ToolErrorGuidance.message(
                    "Required edit approval must include a valid edit review",
                    ExecutionFact.NOT_EXECUTED,
                    EDIT_REVIEW_REQUIRED_NEXT_ACTION)));
      }

      BoundEnvironment boundEnvironment =
          binding.environmentId() != null
              ? remoteExecution.bind(
                  binding.environmentId(),
                  execution.request().call().id(),
                  execution.expectedFile(),
                  execution.remoteExecutions())
              : null;
      ToolGateway.ExecutionScope scope = execution.scope();
      ToolExecutionContext context =
          new ToolExecutionContext(
              execution.invocationId(),
              scope.threadId(),
              scope.sessionId(),
              scope.rootThreadId(),
              scope.rootSessionId(),
              scope.assistantEntryId(),
              clock.instant(),
              scope.mediaCapabilities(),
              Optional.ofNullable(boundEnvironment));
      // 超时在执行前只解析一次：解析结果原样进入请求，下游直接使用该超时值，不施加额外上限。
      ToolExecutionRequest toolRequest;
      try {
        toolRequest =
            new ToolExecutionRequest(
                binding.descriptor(),
                execution.request().call(),
                tool.resolveTimeout(execution.request().call()),
                context);
      } catch (IllegalArgumentException invalidRequest) {
        // arguments 级超时非法（例如非正数）或请求无法按 descriptor 构造：确定性拒绝，绝不重试也绝不执行。
        return reject(
            catalogLease,
            lease,
            new ToolInvocationError(
                ToolErrorClassifier.INVALID_REQUEST_KIND,
                ToolErrorGuidance.message(
                    ToolErrorClassifier.failureMessage(invalidRequest, "Tool request is invalid"),
                    ExecutionFact.NOT_EXECUTED,
                    "Correct the tool arguments, then call the tool again")));
      }
      return new ToolGateway.Admitted(
          new GatewayRun(tool, toolRequest, finalizer, contribution, binding, catalogLease, lease));
    } catch (RuntimeException | Error failure) {
      AdmissionLeases.releaseAfterFailure(catalogLease, lease, failure);
      throw failure;
    }
  }

  @Override
  public Run reattach(ReattachExecution reattach) throws EnvironmentCapabilityUnavailableException {
    Objects.requireNonNull(reattach, "reattach");
    return remoteExecution.reattach(reattach);
  }

  /** 拒绝路径：目录租用与并发租用各释放一次，返回确定性拒绝。 */
  private static ToolGateway.Rejected reject(
      RuntimeToolCatalogSource.Lease catalogLease,
      ConcurrencyAdmission.Lease admissionLease,
      ToolInvocationError error) {
    AdmissionLeases.release(catalogLease, admissionLease);
    return new ToolGateway.Rejected(error);
  }
}
