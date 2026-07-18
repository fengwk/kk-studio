package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.context.SessionContext;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;

import java.util.Optional;

/** Overflow 恢复端口；空结果表示当前上下文已没有可压缩部分。 */
@FunctionalInterface
public interface CompactionService {
  Optional<CompactionEntryPayload> compact(
      long sessionId, long headEntryId, SessionContext context);
}
