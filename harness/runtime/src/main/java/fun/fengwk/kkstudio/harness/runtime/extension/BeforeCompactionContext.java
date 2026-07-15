package fun.fengwk.kkstudio.harness.runtime.extension;

import fun.fengwk.kkstudio.harness.runtime.context.SessionContext;
import java.util.Objects;

/** 压缩前可修改的当前 Session 上下文。 */
public record BeforeCompactionContext(long sessionId, SessionContext context) {

  public BeforeCompactionContext {
    if (sessionId <= 0) {
      throw new IllegalArgumentException("sessionId must be positive");
    }
    context = Objects.requireNonNull(context, "context");
  }
}
