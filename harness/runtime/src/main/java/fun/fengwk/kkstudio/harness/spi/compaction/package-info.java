/**
 * 压缩与请求预算的窄端口。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.spi.compaction.CompactionConfigProvider} 是 Runtime
 * 对部署配置的反向依赖：每个压缩决策点现读 {@link
 * fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig}，不缓存快照、不持有订阅。{@link
 * fun.fengwk.kkstudio.harness.spi.compaction.CompactionModelProvider} 提供可空的显式压缩模型选择；未配置时 Turn
 * 解析沿用对话当前模型。{@link fun.fengwk.kkstudio.harness.spi.compaction.RequestTokenEstimator} 是 Turn
 * 解析对请求输入 token 估算的反向依赖：只接收 {@link fun.fengwk.kkstudio.harness.api.history.EntryPath} 与 system
 * instruction，实现由组合根 指向 {@code runtime.compaction.TokenEstimator}，外部消费方不接触 runtime 实现类型。
 *
 * <p>切分规划、prompt、结果评估与持久化 metadata 属于 {@code
 * fun.fengwk.kkstudio.harness.runtime.compaction}，配置值对象定义在 {@code
 * fun.fengwk.kkstudio.harness.api.compaction}。
 */
package fun.fengwk.kkstudio.harness.spi.compaction;
