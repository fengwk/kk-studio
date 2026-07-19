package fun.fengwk.kkstudio.core.harness.thread.store;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadInputMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadStopMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadInputDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadStopDO;
import fun.fengwk.kkstudio.harness.runtime.thread.AgentThread;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEvent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStop;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStopStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStore;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** MySQL/H2 Thread / Input / Stop / Event 端口实现。 */
@Repository
public class MysqlHarnessThreadStore
    implements ThreadStore, ThreadInputStore, ThreadStopStore, ThreadEventStore {
  private final HarnessThreadMapper threadMapper;
  private final HarnessThreadInputMapper inputMapper;
  private final HarnessThreadStopMapper stopMapper;
  private final HarnessThreadEventMapper eventMapper;
  private final ThreadIdGenerator idGenerator;

  public MysqlHarnessThreadStore(
      HarnessThreadMapper threadMapper,
      HarnessThreadInputMapper inputMapper,
      HarnessThreadStopMapper stopMapper,
      HarnessThreadEventMapper eventMapper,
      ThreadIdGenerator idGenerator) {
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.inputMapper = Objects.requireNonNull(inputMapper, "inputMapper");
    this.stopMapper = Objects.requireNonNull(stopMapper, "stopMapper");
    this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
  }

  @Override
  public Optional<AgentThread> find(long threadId) {
    return Optional.ofNullable(threadMapper.find(threadId)).map(this::toThread);
  }

  @Override
  public List<AgentThread> listBySession(long sessionId) {
    return threadMapper.listBySession(sessionId).stream().map(this::toThread).toList();
  }

  @Override
  public void create(AgentThread thread) {
    threadMapper.insert(toDO(thread));
  }

  @Override
  public Optional<AgentThread> tryAcquire(
      long threadId, String processorToken, Instant now, Duration leaseDuration) {
    LocalDateTime timestamp = utc(now);
    LocalDateTime until = utc(now.plus(leaseDuration));
    if (threadMapper.tryAcquire(threadId, processorToken, until, timestamp, timestamp) != 1) {
      return Optional.empty();
    }
    return find(threadId);
  }

  @Override
  public boolean renew(long threadId, String processorToken, Instant now, Duration leaseDuration) {
    return threadMapper.renew(threadId, processorToken, utc(now.plus(leaseDuration)), utc(now))
        == 1;
  }

  @Override
  public boolean release(long threadId, String processorToken, Instant now) {
    return threadMapper.release(threadId, processorToken, utc(now)) == 1;
  }

  @Override
  public boolean advanceHead(
      long threadId,
      String processorToken,
      long expectedHeadEntryId,
      long newHeadEntryId,
      Instant now) {
    return threadMapper.advanceHead(
            threadId, processorToken, expectedHeadEntryId, newHeadEntryId, utc(now))
        == 1;
  }

  @Override
  public boolean updateStatus(
      long threadId, String processorToken, ThreadStatus status, Instant now) {
    return threadMapper.updateStatus(threadId, processorToken, status.name(), utc(now)) == 1;
  }

  @Override
  public boolean forceStatusAndClearProcessor(long threadId, ThreadStatus status, Instant now) {
    return threadMapper.forceStatusAndClearProcessor(threadId, status.name(), utc(now)) == 1;
  }

  @Override
  @Transactional
  public long allocateInputSequence(long threadId, Instant now) {
    HarnessThreadDO locked = threadMapper.findForUpdate(threadId);
    if (locked == null) {
      throw new IllegalStateException("thread disappeared: " + threadId);
    }
    if (threadMapper.allocateInputSequence(threadId, utc(now)) != 1) {
      throw new IllegalStateException("cannot allocate input sequence for thread " + threadId);
    }
    HarnessThreadDO row = threadMapper.find(threadId);
    if (row == null) {
      throw new IllegalStateException("thread disappeared: " + threadId);
    }
    return row.getInputSequence();
  }

  @Override
  public void insert(ThreadInput input) {
    inputMapper.insert(toDO(input));
  }

  @Override
  public Optional<ThreadInput> findById(long inputId) {
    return Optional.ofNullable(inputMapper.find(inputId)).map(this::toInput);
  }

  @Override
  public Optional<ThreadInput> findByClientMessageId(long threadId, String clientMessageId) {
    return Optional.ofNullable(inputMapper.findByClientMessageId(threadId, clientMessageId))
        .map(this::toInput);
  }

  @Override
  public List<ThreadInput> listByThread(long threadId) {
    return inputMapper.listByThread(threadId).stream().map(this::toInput).toList();
  }

  @Override
  public List<ThreadInput> listQueued(long threadId) {
    return inputMapper.listQueued(threadId).stream().map(this::toInput).toList();
  }

  @Override
  public List<ThreadInput> listQueuedUpTo(long threadId, long cutoffSequence) {
    return inputMapper.listQueuedUpTo(threadId, cutoffSequence).stream()
        .map(this::toInput)
        .toList();
  }

  @Override
  public boolean markApplied(long inputId, long appliedEntryId, Instant resolvedAt) {
    return inputMapper.markApplied(inputId, appliedEntryId, utc(resolvedAt)) == 1;
  }

  @Override
  public boolean markCancelled(long inputId, long stopId, Instant resolvedAt) {
    return inputMapper.markCancelled(inputId, stopId, utc(resolvedAt)) == 1;
  }

  @Override
  public List<ThreadInput> listCancelledByStop(long threadId, long stopId) {
    return inputMapper.listCancelledByStop(threadId, stopId).stream().map(this::toInput).toList();
  }

  @Override
  public void insert(ThreadStop stop) {
    stopMapper.insert(toDO(stop));
  }

  @Override
  public Optional<ThreadStop> findByClientRequestId(long threadId, String clientRequestId) {
    return Optional.ofNullable(stopMapper.findByClientRequestId(threadId, clientRequestId))
        .map(this::toStop);
  }

  @Override
  public ThreadEvent append(
      long threadId, Long subjectEntryId, ThreadEventType type, String payloadJson, Instant now) {
    long id = idGenerator.newThreadEventId();
    HarnessThreadEventDO row = new HarnessThreadEventDO();
    row.setId(id);
    row.setThreadId(threadId);
    row.setSubjectEntryId(subjectEntryId);
    row.setEventType(type.value());
    row.setPayloadJson(payloadJson);
    row.setCreateTime(utc(now));
    eventMapper.insert(row);
    return toEvent(row);
  }

  @Override
  public List<ThreadEvent> listAfter(long threadId, long afterEventId, int limit) {
    return eventMapper.listAfter(threadId, afterEventId, limit).stream()
        .map(this::toEvent)
        .toList();
  }

  private AgentThread toThread(HarnessThreadDO row) {
    return new AgentThread(
        row.getId(),
        row.getSessionId(),
        row.getHeadEntryId(),
        ThreadStatus.fromValue(row.getStatus()),
        row.getInputSequence(),
        row.getProcessorToken(),
        row.getProcessorUntil() == null ? null : row.getProcessorUntil().toInstant(ZoneOffset.UTC),
        row.getVersion(),
        row.getCreateTime().toInstant(ZoneOffset.UTC),
        row.getUpdateTime().toInstant(ZoneOffset.UTC));
  }

  private HarnessThreadDO toDO(AgentThread thread) {
    HarnessThreadDO row = new HarnessThreadDO();
    row.setId(thread.id());
    row.setSessionId(thread.sessionId());
    row.setHeadEntryId(thread.headEntryId());
    row.setStatus(thread.status().name());
    row.setInputSequence(thread.inputSequence());
    row.setProcessorToken(thread.processorToken());
    row.setProcessorUntil(thread.processorUntil() == null ? null : utc(thread.processorUntil()));
    row.setVersion(thread.version());
    row.setCreateTime(utc(thread.createdAt()));
    row.setUpdateTime(utc(thread.updatedAt()));
    return row;
  }

  private ThreadInput toInput(HarnessThreadInputDO row) {
    return new ThreadInput(
        row.getId(),
        row.getThreadId(),
        row.getSequence(),
        ThreadInputType.fromValue(row.getInputType()),
        row.getPayloadJson(),
        row.getClientMessageId(),
        ThreadInputStatus.fromValue(row.getStatus()),
        row.getAppliedEntryId(),
        row.getResolvedAt() == null ? null : row.getResolvedAt().toInstant(ZoneOffset.UTC),
        row.getCancelledByStopId(),
        row.getCreateTime().toInstant(ZoneOffset.UTC));
  }

  private HarnessThreadInputDO toDO(ThreadInput input) {
    HarnessThreadInputDO row = new HarnessThreadInputDO();
    row.setId(input.id());
    row.setThreadId(input.threadId());
    row.setSequence(input.sequence());
    row.setInputType(input.inputType().value());
    row.setPayloadJson(input.payloadJson());
    row.setClientMessageId(input.clientMessageId());
    row.setStatus(input.status().value());
    row.setAppliedEntryId(input.appliedEntryId());
    row.setResolvedAt(input.resolvedAt() == null ? null : utc(input.resolvedAt()));
    row.setCancelledByStopId(input.cancelledByStopId());
    row.setCreateTime(utc(input.createdAt()));
    return row;
  }

  private ThreadStop toStop(HarnessThreadStopDO row) {
    return new ThreadStop(
        row.getId(),
        row.getThreadId(),
        row.getClientRequestId(),
        row.getCreateTime().toInstant(ZoneOffset.UTC));
  }

  private HarnessThreadStopDO toDO(ThreadStop stop) {
    HarnessThreadStopDO row = new HarnessThreadStopDO();
    row.setId(stop.id());
    row.setThreadId(stop.threadId());
    row.setClientRequestId(stop.clientRequestId());
    row.setCreateTime(utc(stop.createdAt()));
    return row;
  }

  private ThreadEvent toEvent(HarnessThreadEventDO row) {
    return new ThreadEvent(
        row.getId(),
        row.getThreadId(),
        row.getSubjectEntryId(),
        ThreadEventType.fromValue(row.getEventType()),
        row.getPayloadJson(),
        row.getCreateTime().toInstant(ZoneOffset.UTC));
  }

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }
}
