package fun.fengwk.kkstudio.harness.runtime.model.worker;

/**
 * Provider-neutral 的异步 Model 执行 SPI。
 *
 * <p>实现必须启动或委托外部 I/O 后立即返回 handle，不能等待完整流或 terminal callback。阻塞 Provider SDK adapter 应在 Java
 * virtual thread 中实现这一点；Runtime 只通过 callback 与 durable transaction port 推进状态。Transport timeout 必须受
 * {@link ModelExecutionRequest#deadlineAt()} 约束，不能越过 durable total deadline。
 */
@FunctionalInterface
public interface ModelExecutor {

  ModelExecutionHandle execute(ModelExecutionRequest request, ModelExecutionListener listener);
}
