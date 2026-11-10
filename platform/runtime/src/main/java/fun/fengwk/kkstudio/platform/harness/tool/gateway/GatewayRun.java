package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.harness.admission.ConcurrencyAdmission;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 一次已准入的本地工具执行：在调用方线程上阻塞调用 {@link Tool#execute}，并把结果与异常交给共享终态装配。
 *
 * <p>本 Run 独占目录租用与并发租约，二者只由 {@link #close()} 释放。
 */
final class GatewayRun extends AbstractToolRun {

  private final Tool tool;
  private final ToolExecutionRequest request;
  private final HarnessCatalog harnessCatalog;
  private final ToolContribution contribution;
  private final ToolBinding binding;
  private final RuntimeToolCatalogSource.Lease catalogLease;
  private final ConcurrencyAdmission.Lease admissionLease;

  GatewayRun(
      Tool tool,
      ToolExecutionRequest request,
      ToolResultFinalizer finalizer,
      ToolContribution contribution,
      ToolBinding binding,
      RuntimeToolCatalogSource.Lease catalogLease,
      ConcurrencyAdmission.Lease admissionLease) {
    super(finalizer, binding.descriptor().name(), request.call().id());
    this.tool = Objects.requireNonNull(tool, "tool");
    this.request = Objects.requireNonNull(request, "request");
    this.catalogLease = Objects.requireNonNull(catalogLease, "catalogLease");
    this.harnessCatalog = catalogLease.harnessCatalog();
    this.contribution = Objects.requireNonNull(contribution, "contribution");
    this.binding = Objects.requireNonNull(binding, "binding");
    this.admissionLease = Objects.requireNonNull(admissionLease, "admissionLease");
  }

  @Override
  protected ToolGateway.Completion invoke(Consumer<ToolResult> progress) {
    try {
      return complete(tool.execute(request, progress));
    } catch (InterruptedException interrupted) {
      return ToolErrorClassifier.cancelled();
    } catch (RuntimeException error) {
      return ToolErrorClassifier.classify(toolName(), error);
    }
  }

  @Override
  protected void release() {
    AdmissionLeases.release(catalogLease, admissionLease);
  }

  private ToolGateway.Completion complete(ToolOutcome outcome) {
    return completion(outcome == null ? null : outcome.result());
  }
}
