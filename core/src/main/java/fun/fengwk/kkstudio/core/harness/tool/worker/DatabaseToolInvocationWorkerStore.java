package fun.fengwk.kkstudio.core.harness.tool.worker;

import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationWorkerStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Database claim port for Cloud/Control leases; all compare-and-set predicates live in the mapper.
 */
@Repository
public class DatabaseToolInvocationWorkerStore implements ToolInvocationWorkerStore {
  private final ToolInvocationMapper invocationMapper;
  private final MysqlToolInvocationStore invocationStore;
  private final HarnessSessionMapper sessionMapper;

  public DatabaseToolInvocationWorkerStore(
      ToolInvocationMapper invocationMapper,
      MysqlToolInvocationStore invocationStore,
      HarnessSessionMapper sessionMapper) {
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.invocationStore = Objects.requireNonNull(invocationStore, "invocationStore");
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
  }

  @Override
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
    ToolInvocationDO candidate = invocationMapper.findClaimCandidate(timestamp);
    if (candidate == null) {
      return Optional.empty();
    }
    boolean recovered = ToolInvocationStatus.RUNNING.name().equals(candidate.getStatus());
    if (invocationMapper.claim(
            candidate.getId(), leaseOwner, timestamp, utc(now.plus(leaseDuration)))
        != 1) {
      return Optional.empty();
    }
    ToolInvocation invocation = invocationStore.find(candidate.getId()).orElseThrow();
    HarnessSessionDO session = sessionMapper.find(runSessionId(invocation));
    if (session == null) {
      throw new IllegalStateException("tool invocation run session is missing");
    }
    return Optional.of(new ClaimedToolInvocation(invocation, session.getWorkspaceId(), recovered));
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

  private long runSessionId(ToolInvocation invocation) {
    // assistant entries are session-local; this query is intentionally delegated through the run
    // table.
    // The mapper's findInWorkspace contract cannot infer the workspace before this lookup.
    return invocationMapper.findRunSessionId(invocation.runId());
  }

  private static LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
