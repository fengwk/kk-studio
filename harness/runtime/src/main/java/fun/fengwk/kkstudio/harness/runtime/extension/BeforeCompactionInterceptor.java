package fun.fengwk.kkstudio.harness.runtime.extension;

import fun.fengwk.kkstudio.harness.runtime.context.SessionContext;

/** 在压缩开始前返回用于压缩的新上下文。 */
@FunctionalInterface
public interface BeforeCompactionInterceptor {
  SessionContext intercept(BeforeCompactionContext context);
}
