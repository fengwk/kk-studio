package fun.fengwk.kkstudio.core.harness.tool.worker;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/** Database lease port for one Environment's durable remote invocations. */
@Repository
public class DatabaseEnvironmentToolInvocationWorkerStore {

  private static final int MAX_CLAIM_CONTENTION_RETRIES = 64;

  private final ToolInvocationMapper invocationMapper;
  private final MysqlToolInvocationStore invocationStore;

  public DatabaseEnvironmentToolInvocationWorkerStore(
      ToolInvocationMapper invocationMapper, MysqlToolInvocationStore invocationStore) {
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.invocationStore = Objects.requireNonNull(invocationStore, "invocationStore");
  }

  /** Claims one due invocation only when it belongs to the specified Environment. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public Optional<ClaimedToolInvocation> claimDue(
      long environmentId, String leaseOwner, Instant now, Duration leaseDuration) {
    requireRequest(environmentId, leaseOwner, now, leaseDuration);
    LocalDateTime timestamp = utc(now);
    LocalDateTime leaseUntil = utc(now.plus(leaseDuration));
    for (int retry = 0; retry < MAX_CLAIM_CONTENTION_RETRIES; retry++) {
      ToolInvocationDO candidate =
          invocationMapper.findEnvironmentClaimCandidate(environmentId, timestamp);
      if (candidate == null) {
        return Optional.empty();
      }
      boolean recovered = ToolInvocationStatus.RUNNING.name().equals(candidate.getStatus());
      if (invocationMapper.claimEnvironment(
              candidate.getId(), environmentId, leaseOwner, timestamp, leaseUntil)
          != 1) {
        continue;
      }
      ToolInvocation invocation = invocationStore.find(candidate.getId()).orElseThrow();
      return Optional.of(new ClaimedToolInvocation(invocation, recovered));
    }
    return Optional.empty();
  }

  /** Extends a remote lease while its bound daemon connection remains usable. */
  public boolean heartbeat(ClaimedToolInvocation claimed, Instant now, Duration leaseDuration) {
    Objects.requireNonNull(claimed, "claimed");
    ToolInvocation invocation = claimed.invocation();
    if (invocation.environmentId() == null) {
      throw new IllegalArgumentException("Environment invocation requires environmentId");
    }
    requireRequest(invocation.environmentId(), invocation.leaseOwner(), now, leaseDuration);
    return invocationMapper.heartbeatEnvironment(
            invocation.id(),
            invocation.environmentId(),
            invocation.leaseOwner(),
            utc(now),
            utc(now.plus(leaseDuration)))
        == 1;
  }

  public Optional<ToolInvocation> find(long invocationId) {
    if (invocationId <= 0) {
      throw new IllegalArgumentException("invocationId must be positive");
    }
    return invocationStore.find(invocationId);
  }

  private static void requireRequest(
      long environmentId, String leaseOwner, Instant now, Duration leaseDuration) {
    if (environmentId <= 0) {
      throw new IllegalArgumentException("environmentId must be positive");
    }
    if (leaseOwner == null || leaseOwner.isBlank()) {
      throw new IllegalArgumentException("leaseOwner must not be blank");
    }
    Objects.requireNonNull(now, "now");
    if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("leaseDuration must be positive");
    }
  }

  private static LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
