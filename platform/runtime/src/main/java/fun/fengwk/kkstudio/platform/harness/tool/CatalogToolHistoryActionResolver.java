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
 * 基于冻结目录 epoch 的 {@link ToolHistoryActionResolver}：按 binding 冻结的 epoch 取得同 epoch 租用并严格取回其 Tool，
 * 再调用可选渲染器。
 *
 * <p>这里只用 binding 自己的 {@code catalogEpoch} 取租用，绝不按当前目录重新解释旧参数；epoch、完整定义、贡献身份、requirements 或插件 来源与
 * binding 不一致时返回 empty，让 Runtime 回退到中性描述。没有渲染器的工具直接返回 empty。
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
    try (RuntimeToolCatalogSource.Lease lease = toolCatalogSource.acquire(binding.catalogEpoch())) {
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
