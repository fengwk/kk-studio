/**
 * Builtin 的模型调用持久化事实：{@link fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation} 至多一行当前生成，
 * 保存冻结请求、状态、attempt、流式 checkpoint、终态结果、失败 attempt 审计与模型组进度。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.builtin.model.ModelRequestSpec} 冻结一次调用不可变的请求契约，{@link
 * fun.fengwk.kkstudio.harness.builtin.model.ModelRequestSpecJsonCodec} 是它的严格确定性 JSON codec；{@link
 * fun.fengwk.kkstudio.harness.builtin.model.ModelRequestMaterializer} 每次 attempt 从不可变 EntryPath
 * 纯内存重建供应商中立 请求。模型组的冻结成员与进度见 {@link fun.fengwk.kkstudio.harness.builtin.model.ModelGroupExecution}
 * 与 {@link fun.fengwk.kkstudio.harness.builtin.model.ModelGroupProgress}。完整历史、Provider
 * tools、凭证与端点留在各自事实源。
 */
package fun.fengwk.kkstudio.harness.builtin.model;
