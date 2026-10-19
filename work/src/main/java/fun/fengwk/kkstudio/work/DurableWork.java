package fun.fengwk.kkstudio.work;

import java.time.Instant;
import java.util.UUID;

/** {@code durable_work} 一行的只读快照；租约字段同时为空或同时存在。 */
public record DurableWork(
    UUID targetId, long wakeVersion, Instant availableAt, String leaseToken, Instant leaseUntil) {}
