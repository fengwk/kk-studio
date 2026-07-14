package fun.fengwk.kkstudio.harness.runtime.tool;

/** 可信编译期 beforeToolCall 扩展；priority 小者先执行，相同 priority 保持注册顺序。 */
public interface BeforeToolCallInterceptor {
  default int priority() {
    return 0;
  }

  BeforeToolCallResult intercept(BeforeToolCallContext context);
}
