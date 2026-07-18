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
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ThreadScopedToolClaimStore;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationWorkerStore;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/**
 * Database claim port for Cloud/Control leases; all compare-and-set predicates live in the mapper.
 */
@Repository
public class DatabaseToolInvocationWorkerStore
    implements ToolInvocationWorkerStore, ThreadScopedToolClaimStore {
  private static final int MAX_CLAIM_CONTENTION_RETRIES = 64;

  private final ToolInvocationMapper invocationMapper;
  private final MysqlToolInvocationStore invocationStore;

  public DatabaseToolInvocationWorkerStore(
      ToolInvocationMapper invocationMapper, MysqlToolInvocationStore invocationStore) {
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.invocationStore = Objects.requireNonNull(invocationStore, "invocationStore");
  }

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public Optional<ClaimedToolInvocation> claimDue(
      String leaseOwner, Instant now, Duration leaseDuration) {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    if (leaseOwner == null
        || leaseOwner.isBlank()
        || leaseDuration.isZero()
        || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("lease owner and duration must be valid");
    }
    LocalDateTime timestamp = utc(now);
    LocalDateTime leaseUntil = utc(now.plus(leaseDuration));
    // Each failed CAS rereads a current candidate in a separate autocommit statement. This avoids
    // retrying a stale REPEATABLE READ snapshot while another worker is claiming due work.
    for (int retry = 0; retry < MAX_CLAIM_CONTENTION_RETRIES; retry++) {
      ToolInvocationDO candidate = invocationMapper.findClaimCandidate(timestamp);
      if (candidate == null) {
        return Optional.empty();
      }
      boolean recovered = ToolInvocationStatus.RUNNING.name().equals(candidate.getStatus());
      if (invocationMapper.claim(candidate.getId(), leaseOwner, timestamp, leaseUntil) != 1) {
        continue;
      }
      ToolInvocation invocation = invocationStore.find(candidate.getId()).orElseThrow();
      return Optional.of(new ClaimedToolInvocation(invocation, recovered));
    }
    return Optional.empty();
  }

  @Override
  public boolean heartbeat(ClaimedToolInvocation claimed, Instant now, Duration leaseDuration) {
    Objects.requireNonNull(claimed, "claimed");
    if (leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("leaseDuration must be positive");
    }
    return invocationMapper.heartbeat(
            claimed.invocation().id(),
            claimed.invocation().leaseOwner(),
            utc(now),
            utc(now.plus(leaseDuration)))
        == 1;
  }

  @Override
  public Optional<ToolInvocation> find(long invocationId) {
    return invocationStore.find(invocationId);
  }

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public Optional<ClaimedToolInvocation> claimDueForThread(
      String leaseOwner, long threadId, Instant now, Duration leaseDuration) {
    Objects.requireNonNull(now, "now");
    Objects.requireNonNull(leaseDuration, "leaseDuration");
    if (leaseOwner == null
        || leaseOwner.isBlank()
        || threadId <= 0
        || leaseDuration.isZero()
        || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("lease owner, threadId and duration must be valid");
    }
    LocalDateTime timestamp = utc(now);
    LocalDateTime leaseUntil = utc(now.plus(leaseDuration));
    for (int retry = 0; retry < MAX_CLAIM_CONTENTION_RETRIES; retry++) {
      ToolInvocationDO candidate =
          invocationMapper.findClaimCandidateForThread(threadId, timestamp);
      if (candidate == null) {
        return Optional.empty();
      }
      boolean recovered = ToolInvocationStatus.RUNNING.name().equals(candidate.getStatus());
      if (invocationMapper.claim(candidate.getId(), leaseOwner, timestamp, leaseUntil) != 1) {
        continue;
      }
      ToolInvocation invocation = invocationStore.find(candidate.getId()).orElseThrow();
      return Optional.of(new ClaimedToolInvocation(invocation, recovered));
    }
    return Optional.empty();
  }

  private static LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
