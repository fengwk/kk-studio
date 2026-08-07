/**
 * 受信任的 build-time 插件 API：不可变 PluginCatalog、scoped PluginRegistrar 贡献（Plugin → ContributionId →
 * typed contribution）、BranchView 分支视图（按 (pluginId, customType) 查询）、声明式 PluginIntent（包装最终 Entry
 * payload）与严格 classpath prompt 模板。本包不依赖 Spring，不暴露 HarnessStore / gateway / transaction /
 * lock；插件只能通过 registrar 贡献能力，通过 BranchView 读取不可变分支状态，通过 intent 声明希望 Harness 执行的 状态变更。
 */
package fun.fengwk.kkstudio.harness.plugin;
