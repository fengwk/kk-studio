package fun.fengwk.kkstudio.core.harness.run.store;

import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunEventMapper;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunEventDO;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.RunEvent;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventStore;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.run.RunStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** MySQL/H2 Durable Run queue 与线性 Run Event Journal。 */
@Repository
public class MysqlHarnessRunStore implements RunStore, RunEventStore {
  private static final int MAX_CLAIM_CONTENTION_RETRIES = 64;

  private final HarnessRunMapper runMapper;
  private final HarnessRunEventMapper eventMapper;
  private final RunIdGenerator idGenerator;

  public MysqlHarnessRunStore(
      HarnessRunMapper runMapper, HarnessRunEventMapper eventMapper, RunIdGenerator idGenerator) {
    this.runMapper = Objects.requireNonNull(runMapper, "runMapper");
    this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
  }

  @Override
  public Optional<AgentRun> find(long runId) {
    return Optional.ofNullable(runMapper.find(runId)).map(this::toRun);
  }

  @Override
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public Optional<AgentRun> claimDue(String leaseOwner, Instant now, Duration leaseDuration) {
    if (leaseOwner == null || leaseOwner.isBlank()) {
      throw new IllegalArgumentException("leaseOwner must not be blank");
    }
    LocalDateTime claimedAt = utc(now);
    LocalDateTime leaseUntil = utc(now.plus(leaseDuration));
    // 每次 mapper 调用均在独立 autocommit 语句中执行，CAS 失败后会重新读取最新候选；禁止用
    // MySQL REPEATABLE READ 的长事务快照反复选择已经被其他 worker claim 的旧行。竞争重试有界，
    // 数据库异常则立即向上抛出，避免故障期间 busy loop。
    for (int retry = 0; retry < MAX_CLAIM_CONTENTION_RETRIES; retry++) {
      HarnessRunDO candidate = runMapper.findClaimCandidate(claimedAt);
      if (candidate == null) {
        return Optional.empty();
      }
      if (runMapper.claim(candidate.getId(), leaseOwner, claimedAt, leaseUntil) == 1) {
        return Optional.of(toRun(runMapper.find(candidate.getId())));
      }
    }
    return Optional.empty();
  }

  @Override
  public boolean heartbeat(
      long runId, String leaseOwner, int attempt, Instant now, Duration leaseDuration) {
    return runMapper.heartbeat(runId, leaseOwner, attempt, utc(now), utc(now.plus(leaseDuration)))
        == 1;
  }

  @Override
  public boolean requestCancel(long runId, Instant requestedAt) {
    return runMapper.requestCancel(runId, utc(requestedAt)) == 1;
  }

  @Override
  @Transactional
  public RunEvent append(long runId, RunEventType type, String payloadJson, Instant createdAt) {
    HarnessRunDO run = runMapper.findForUpdate(runId);
    if (run == null) {
      throw new IllegalArgumentException("unknown run: " + runId);
    }
    long sequence = run.getEventSequence() + 1;
    if (runMapper.updateEventSequence(runId, run.getEventSequence(), sequence, utc(createdAt))
        != 1) {
      throw new IllegalStateException("cannot allocate run event sequence: " + runId);
    }
    RunEvent event =
        new RunEvent(idGenerator.newRunEventId(), runId, sequence, type, payloadJson, createdAt);
    if (eventMapper.insert(toDO(event)) != 1) {
      throw new IllegalStateException("cannot append run event: " + runId);
    }
    return event;
  }

  @Override
  public List<RunEvent> listAfter(long runId, long afterSequence, int limit) {
    if (afterSequence < 0 || limit <= 0) {
      throw new IllegalArgumentException("cursor must be non-negative and limit must be positive");
    }
    return eventMapper.listAfter(runId, afterSequence, limit).stream().map(this::toEvent).toList();
  }

  private AgentRun toRun(HarnessRunDO source) {
    return new AgentRun(
        source.getId(),
        source.getSessionId(),
        source.getTriggerEntryId(),
        RunStatus.valueOf(source.getStatus()),
        source.getTurnIndex(),
        source.getAttempt(),
        source.getEventSequence(),
        source.getLeaseOwner(),
        instant(source.getLeaseUntil()),
        instant(source.getNextAttemptAt()),
        instant(source.getCancelRequestedAt()),
        instant(source.getCreateTime()),
        instant(source.getStartedAt()),
        instant(source.getFinishedAt()),
        instant(source.getUpdateTime()));
  }

  private HarnessRunEventDO toDO(RunEvent event) {
    HarnessRunEventDO target = new HarnessRunEventDO();
    target.setId(event.id());
    target.setRunId(event.runId());
    target.setSequence(event.sequence());
    target.setEventType(event.type().value());
    target.setPayloadJson(event.payloadJson());
    target.setCreateTime(utc(event.createdAt()));
    return target;
  }

  private RunEvent toEvent(HarnessRunEventDO source) {
    return new RunEvent(
        source.getId(),
        source.getRunId(),
        source.getSequence(),
        RunEventType.fromValue(source.getEventType()),
        source.getPayloadJson(),
        instant(source.getCreateTime()));
  }

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static Instant instant(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
}
