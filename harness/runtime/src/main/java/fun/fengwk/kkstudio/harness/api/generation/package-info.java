/**
 * Thread 当前 generation 的公开投影：生命周期状态、已完成输出与失败诊断。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.api.generation.GenerationProjection} 是 ThreadSnapshot 中
 * generation 部分的形状，{@link fun.fengwk.kkstudio.harness.api.generation.FailureProjection} 与 {@link
 * fun.fengwk.kkstudio.harness.api.generation.GenerationStatus} 是它的嵌套值类型。只暴露稳定身份、状态、已完成的
 * 文本/思考片段与结构化错误，不暴露 Driver 私有调用实体、Provider 请求响应或流式检查点。
 *
 * <p>消费方（web、platform）由此包读取 generation 快照，无需依赖 {@code spi.driver}。输出单元与错误值仍取自 {@code
 * api.session.AgentMessageContent} 与 {@code api.history.AssistantError}。
 */
package fun.fengwk.kkstudio.harness.api.generation;
