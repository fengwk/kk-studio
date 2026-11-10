package fun.fengwk.kkstudio.platform.catalog.tool;

import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.platform.catalog.dto.ToolProviderDTO;

import java.util.Objects;

/**
 * 工具目录选择面上的一条工具：贡献本体与它的提供者。
 *
 * <p>provider 在目录冻结时按贡献来源判定并随之固定，选择面消费方（配置 UI、查询服务）不需要再解释 contributor 身份或访问插件快照。调用规划面（{@code
 * findTool}/{@code require}）仍直接使用 {@link ToolContribution}。
 *
 * @param contribution 可选择的工具贡献
 * @param provider 该贡献的提供者（BUILTIN/APP/PLUGIN 取 contributor 身份与显示名，MCP 取服务名）
 */
public record SelectableTool(ToolContribution contribution, ToolProviderDTO provider) {

  public SelectableTool {
    Objects.requireNonNull(contribution, "contribution");
    Objects.requireNonNull(provider, "provider");
  }

  /** 模型可见工具名。 */
  public String name() {
    return contribution.definition().descriptor().name();
  }
}
