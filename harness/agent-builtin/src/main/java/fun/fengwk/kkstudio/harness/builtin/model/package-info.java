/**
 * Builtin 的请求重建：{@link fun.fengwk.kkstudio.harness.builtin.model.ModelRequestMaterializer} 每次
 * attempt 从不可变 EntryPath 与冻结的 {@link
 * fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec} 纯内存重建供应商中立请求。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.builtin.model.ModelHistoryProjection} 负责压缩感知的历史投影：以摘要
 * wrapper 替代切点前历史、移除绑定旧前缀的 thinking、在消息之间插入增量 system 变更。持久化事实（Invocation、冻结请求与模型组）在 {@code
 * port.store} 包。两者都只读输入并返回不可变值，不访问 Store、catalog 或 Environment registry。
 */
package fun.fengwk.kkstudio.harness.builtin.model;
