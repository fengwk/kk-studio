package fun.fengwk.kkstudio.harness.runtime.run;

import fun.fengwk.kkstudio.harness.runtime.context.SessionContext;
import fun.fengwk.kkstudio.harness.runtime.extension.BeforeCompactionContext;
import fun.fengwk.kkstudio.harness.runtime.extension.BeforeCompactionInterceptor;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 按 Host 提供顺序变换压缩上下文，再调用一次实际压缩服务。 */
public final class InterceptingCompactionService implements CompactionService {
  private final CompactionService delegate;
  private final List<BeforeCompactionInterceptor> interceptors;

  public InterceptingCompactionService(
      CompactionService delegate, List<BeforeCompactionInterceptor> interceptors) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.interceptors = List.copyOf(Objects.requireNonNull(interceptors, "interceptors"));
  }

  @Override
  public Optional<CompactionEntryPayload> compact(long sessionId, SessionContext context) {
    if (sessionId <= 0) {
      throw new IllegalArgumentException("sessionId must be positive");
    }
    SessionContext current = Objects.requireNonNull(context, "context");
    for (BeforeCompactionInterceptor interceptor : interceptors) {
      current =
          Objects.requireNonNull(
              interceptor.intercept(new BeforeCompactionContext(sessionId, current)),
              "before compaction interceptor result");
    }
    return Objects.requireNonNull(
        delegate.compact(sessionId, current), "compaction delegate result");
  }
}
