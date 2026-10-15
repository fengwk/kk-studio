package fun.fengwk.kkstudio.platform.catalog.tool;

import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolPluginSource;

/**
 * Platform 侧运行时工具目录的 epoch 租用端口：每个 {@link Lease} 固定同一份运行时目录视图。
 *
 * <p>执行与恢复都先取得某个 epoch 的租用，再从该租用严格取回冻结的 {@link ToolContribution}；绝不按当前同名工具静默换执行语义。 {@link
 * #acquire(long)} 只按持久 epoch 重建历史目录，绝不改变已发布的当前目录。租用负责保护其中 Plugin 实例的资源生命周期。
 */
public interface RuntimeToolCatalogSource {

  /** 取得当前已发布目录 epoch 的租用。 */
  Lease acquireCurrent();

  /** 取得指定已持久目录 epoch 的租用；该 epoch 不可用时拒绝。 */
  Lease acquire(long catalogEpoch);

  interface Lease extends AutoCloseable {

    /** 本次租用固定的目录 epoch。 */
    long epoch();

    /** 本次租用固定的完整 Harness 目录（含内部工具）；与 epoch 同源。 */
    HarnessCatalog harnessCatalog();

    /** 本次租用的只读运行时工具目录视图；与 epoch 同 snapshot 的 Harness 工具与本次一次性读取的 MCP 可选工具。 */
    RuntimeToolCatalog catalog();

    /** 按冻结贡献身份取该 epoch 的插件来源；内置与 MCP 贡献没有插件来源，返回 null。 */
    ToolPluginSource pluginSource(ContributionId contributionId);

    /**
     * 严格验证冻结 binding 属于本次租用：epoch、完整定义、贡献身份、requirements 与插件来源都必须一致，再返回冻结贡献。
     *
     * <p>任何一项不匹配都拒绝，绝不退回当前同名工具。
     */
    ToolContribution require(ToolBinding binding);

    @Override
    void close();
  }
}
