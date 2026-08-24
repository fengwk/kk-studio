package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.infra.function.ClaimedRun;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/** Canvas Function durable work store；所有 ownership 变更都由单条 PostgreSQL 原子语句完成。 */
@Repository
public class CanvasFunctionWorkStore {

  private final CanvasFunctionWorkMapper mapper;
  private final CanvasFunctionRunStateCodecPort stateCodec;

  public CanvasFunctionWorkStore(
      CanvasFunctionWorkMapper mapper, CanvasFunctionRunStateCodecPort stateCodec) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
  }

  public Optional<ClaimedRun> claimNext(Instant now, Duration leaseDuration, String ownerToken) {
    requireToken(ownerToken);
    now = normalize(now);
    Instant leaseUntil = now.plus(requirePositiveMillis(leaseDuration));
    CanvasFunctionRunDO claimed =
        mapper.claimNext(toOffsetDateTime(now), ownerToken, toOffsetDateTime(leaseUntil));
    return Optional.ofNullable(claimed).map(this::toClaimedRun);
  }

  public boolean renew(ClaimedRun claim, Instant now, Duration leaseDuration) {
    Objects.requireNonNull(claim, "claim");
    now = normalize(now);
    Instant leaseUntil = now.plus(requirePositiveMillis(leaseDuration));
    return mapper.renew(
            claim.nodeId(),
            claim.requestId(),
            claim.leaseToken(),
            toOffsetDateTime(now),
            toOffsetDateTime(leaseUntil))
        == 1;
  }

  public boolean reschedule(ClaimedRun claim, Instant now, Duration delay) {
    Objects.requireNonNull(claim, "claim");
    now = normalize(now);
    Instant availableAt = now.plus(requirePositiveMillis(delay));
    return mapper.reschedule(
            claim.nodeId(),
            claim.requestId(),
            claim.leaseToken(),
            toOffsetDateTime(now),
            toOffsetDateTime(availableAt))
        == 1;
  }

  public boolean isOwned(ClaimedRun claim, Instant now) {
    Objects.requireNonNull(claim, "claim");
    return mapper.countOwned(
            claim.nodeId(), claim.requestId(), claim.leaseToken(), toOffsetDateTime(normalize(now)))
        == 1;
  }

  private ClaimedRun toClaimedRun(CanvasFunctionRunDO run) {
    CanvasFunctionRun domain =
        new CanvasFunctionRun(
            run.getNodeId(),
            run.getRequestId(),
            CanvasFunctionRunStatus.valueOf(run.getStatus()),
            run.getAttempt(),
            toInstant(run.getAvailableAt()),
            run.getLeaseToken(),
            toInstant(run.getLeaseUntil()),
            stateCodec.stage(run.getStateJson()),
            run.getStateJson(),
            run.getError(),
            run.getUpdatedAt().toInstant(),
            run.getCreatedAt().toInstant());
    return new ClaimedRun(domain);
  }

  private static Duration requirePositiveMillis(Duration value) {
    Objects.requireNonNull(value, "duration");
    long millis;
    try {
      millis = value.toMillis();
    } catch (ArithmeticException error) {
      throw new IllegalArgumentException("duration is outside millisecond range", error);
    }
    if (millis <= 0 || !value.equals(Duration.ofMillis(millis))) {
      throw new IllegalArgumentException(
          "duration must be a positive whole number of milliseconds");
    }
    return value;
  }

  private static void requireToken(String token) {
    if (token == null || token.isBlank() || token.length() > 128) {
      throw new IllegalArgumentException("ownerToken must contain 1 to 128 characters");
    }
  }

  private static OffsetDateTime toOffsetDateTime(Instant value) {
    return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static Instant normalize(Instant value) {
    return Objects.requireNonNull(value, "now").truncatedTo(ChronoUnit.MILLIS);
  }

  private static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
