package fun.fengwk.kkstudio.harness.runtime.tool;

/** 可信编译期 beforeToolCall 扩展；按 Extension Host 提供的顺序执行。 */
public interface BeforeToolCallInterceptor {
  BeforeToolCallResult intercept(BeforeToolCallContext context);
}
