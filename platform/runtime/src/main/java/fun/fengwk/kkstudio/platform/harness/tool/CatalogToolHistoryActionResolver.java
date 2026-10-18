package fun.fengwk.kkstudio.platform.harness.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.port.ToolHistoryActionResolver;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.catalog.tool.ToolBindingMismatchException;

import java.util.Objects;
import java.util.Optional;

/**
 * 基于当前目录的 {@link ToolHistoryActionResolver}：按 binding 严格取回其 Tool，再调用可选渲染器。
 *
 * <p>工具已不存在或定义与 binding 不一致时返回 empty，让 Runtime 回退到中性描述；没有渲染器的工具同样返回 empty。
 */
@Slf4j
@Component
public class CatalogToolHistoryActionResolver implements ToolHistoryActionResolver {

  private final RuntimeToolCatalogSource toolCatalogSource;

  public CatalogToolHistoryActionResolver(RuntimeToolCatalogSource toolCatalogSource) {
    this.toolCatalogSource = Objects.requireNonNull(toolCatalogSource, "toolCatalogSource");
  }

  @Override
  public Optional<String> resolve(ToolBinding binding, ToolCall call) {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(call, "call");
    try (RuntimeToolCatalogSource.Lease lease = toolCatalogSource.acquire()) {
      if (!call.toolName().equals(binding.descriptor().name())) {
        return Optional.empty();
      }
      ToolContribution contribution = lease.require(binding);
      Optional<ToolHistoryRenderer> renderer = contribution.tool().historyRenderer();
      if (renderer.isEmpty()) {
        return Optional.empty();
      }
      return renderer.get().render(new ToolHistoryRenderRequest(call, binding.environmentName()));
    } catch (ToolBindingMismatchException mismatch) {
      return Optional.empty();
    } catch (RuntimeException failure) {
      log.warn("cannot render tool history action for {}: {}", call.toolName(), failure.toString());
      return Optional.empty();
    }
  }
}
