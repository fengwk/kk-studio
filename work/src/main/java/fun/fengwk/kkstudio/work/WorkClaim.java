package fun.fengwk.kkstudio.work;

import java.time.Instant;
import java.util.UUID;

/** 一次成功 claim 的围栏凭据：后续续租、完成与重排都必须原样回传。 */
public record WorkClaim(UUID targetId, long wakeVersion, String leaseToken, Instant leaseUntil) {}
