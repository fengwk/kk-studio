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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Database lease port for one Environment's durable Tool invocations. */
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

  /** Claims one due invocation only when it belongs to the specified Environment name. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public Optional<ClaimedToolInvocation> claimDue(
      String environmentName, String leaseOwner, Instant now, Duration leaseDuration) {
    requireRequest(environmentName, leaseOwner, now, leaseDuration);
    LocalDateTime timestamp = utc(now);
    LocalDateTime leaseUntil = utc(now.plus(leaseDuration));
    for (int retry = 0; retry < MAX_CLAIM_CONTENTION_RETRIES; retry++) {
      ToolInvocationDO candidate =
          invocationMapper.findEnvironmentClaimCandidate(environmentName, timestamp);
      if (candidate == null) {
        return Optional.empty();
      }
      boolean recovered = ToolInvocationStatus.RUNNING.name().equals(candidate.getStatus());
      if (invocationMapper.claimEnvironment(
              candidate.getId(), environmentName, leaseOwner, timestamp, leaseUntil)
          != 1) {
        continue;
      }
      ToolInvocation invocation = invocationStore.find(candidate.getId()).orElseThrow();
      return Optional.of(new ClaimedToolInvocation(invocation, recovered));
    }
    return Optional.empty();
  }

  /**
   * Lists due ENVIRONMENT candidates across all names so the gateway can fail offline targets
   * immediately without waiting for a READY daemon.
   */
  public List<ToolInvocation> listDueEnvironmentCandidates(Instant now, int limit) {
    Objects.requireNonNull(now, "now");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    return invocationMapper.findDueEnvironmentCandidates(utc(now), limit).stream()
        .map(invocationStore::toInvocation)
        .toList();
  }

  /** Extends an Environment lease while its bound Daemon connection remains usable. */
  public boolean heartbeat(ClaimedToolInvocation claimed, Instant now, Duration leaseDuration) {
    Objects.requireNonNull(claimed, "claimed");
    ToolInvocation invocation = claimed.invocation();
    if (invocation.environmentName() == null) {
      throw new IllegalArgumentException("Environment invocation requires environmentName");
    }
    requireRequest(invocation.environmentName(), invocation.leaseOwner(), now, leaseDuration);
    return invocationMapper.heartbeatEnvironment(
            invocation.id(),
            invocation.environmentName(),
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
      String environmentName, String leaseOwner, Instant now, Duration leaseDuration) {
    if (environmentName == null || environmentName.isBlank()) {
      throw new IllegalArgumentException("environmentName must not be blank");
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
