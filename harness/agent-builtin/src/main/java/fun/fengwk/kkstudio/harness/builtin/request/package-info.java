/**
 * Builtin 的请求侧投影与校验：{@link fun.fengwk.kkstudio.harness.builtin.request.ProviderMessageProjector}
 * 把语义上下文投影为 Provider 消息，投影只影响本次请求， 从不改写 durable 历史。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.builtin.request.ResolvedRequestValidator} 在提交前按 candidate
 * branch 事实做机械一致性校验；任何不一致都是 Resolver 契约错误。两者都是纯函数，不读写 Store。
 */
package fun.fengwk.kkstudio.harness.builtin.request;
