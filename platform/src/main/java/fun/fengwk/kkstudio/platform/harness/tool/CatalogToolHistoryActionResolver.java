package fun.fengwk.kkstudio.platform.harness.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.port.ToolHistoryActionResolver;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.Objects;
import java.util.Optional;

/**
 * 基于进程内冻结静态 Tool 目录的 {@link ToolHistoryActionResolver}：按 binding 冻结的贡献身份与定义解析 Tool，调用其可选渲染器。
 *
 * <p>这里只通过 {@link HarnessToolCatalogAdapter} 读取不可变目录，不会为默认无渲染器的动态 MCP Tool 访问数据库。贡献身份或完整定义与 binding
 * 不一致时返回 empty，让 Runtime 回退到中性描述，避免用变化后的 Tool 重新解释旧参数。
 */
@Slf4j
@Component
public class CatalogToolHistoryActionResolver implements ToolHistoryActionResolver {

  private final HarnessToolCatalogAdapter staticToolCatalog;

  public CatalogToolHistoryActionResolver(HarnessToolCatalogAdapter staticToolCatalog) {
    this.staticToolCatalog = Objects.requireNonNull(staticToolCatalog, "staticToolCatalog");
  }

  @Override
  public Optional<String> resolve(ToolBinding binding, ToolCall call) {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(call, "call");
    try {
      ToolContribution contribution =
          staticToolCatalog.findTool(contributionId(binding)).orElse(null);
      if (contribution == null
          || !contribution.id().equals(contributionId(binding))
          || !contribution.definition().equals(binding.definition())
          || !call.toolName().equals(binding.descriptor().name())) {
        return Optional.empty();
      }
      Optional<ToolHistoryRenderer> renderer = contribution.tool().historyRenderer();
      if (renderer.isEmpty()) {
        return Optional.empty();
      }
      return renderer.get().render(new ToolHistoryRenderRequest(call, binding.environmentName()));
    } catch (RuntimeException failure) {
      log.warn("cannot render tool history action for {}: {}", call.toolName(), failure.toString());
      return Optional.empty();
    }
  }

  /** 冻结 binding 的贡献身份；该身份与 Gateway 执行前的定义一致性校验使用同一来源。 */
  private static ContributionId contributionId(ToolBinding binding) {
    return new ContributionId(
        new ContributorId(binding.contributor().contributorId()),
        binding.contributor().localName());
  }
}
