package fun.fengwk.kkstudio.core.harness.thread.store;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadInputMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadInputDO;
import fun.fengwk.kkstudio.harness.runtime.thread.AgentThread;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEvent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStore;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** MySQL/H2 Thread / Input / Event 端口实现。 */
@Repository
public class MysqlHarnessThreadStore implements ThreadStore, ThreadInputStore, ThreadEventStore {
  private final HarnessThreadMapper threadMapper;
  private final HarnessThreadInputMapper inputMapper;
  private final HarnessThreadEventMapper eventMapper;
  private final ThreadIdGenerator idGenerator;

  public MysqlHarnessThreadStore(
      HarnessThreadMapper threadMapper,
      HarnessThreadInputMapper inputMapper,
      HarnessThreadEventMapper eventMapper,
      ThreadIdGenerator idGenerator) {
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.inputMapper = Objects.requireNonNull(inputMapper, "inputMapper");
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
  public boolean updateYolo(
      long threadId, String processorToken, boolean yoloEnabled, Instant now) {
    return threadMapper.updateYolo(threadId, processorToken, yoloEnabled, utc(now)) == 1;
  }

  @Override
  public boolean updateAgent(
      long threadId,
      String processorToken,
      Long agentDefinitionId,
      String runtimeConfigJson,
      Instant now) {
    return threadMapper.updateAgent(
            threadId, processorToken, agentDefinitionId, runtimeConfigJson, utc(now))
        == 1;
  }

  @Override
  @Transactional
  public long allocateInputSequence(long threadId, Instant now) {
    // FOR UPDATE 与 ThreadTransactions.releaseIfIdle 串行同一 thread 行。
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
  public List<ThreadInput> listPending(long threadId) {
    return inputMapper.listPending(threadId).stream().map(this::toInput).toList();
  }

  @Override
  public Optional<ThreadInput> findNextPending(long threadId) {
    return Optional.ofNullable(inputMapper.findNextPending(threadId)).map(this::toInput);
  }

  @Override
  public boolean markApplied(long inputId, long appliedEntryId, Instant appliedAt) {
    return inputMapper.markApplied(inputId, appliedEntryId, utc(appliedAt)) == 1;
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
        row.getAgentDefinitionId(),
        row.getRuntimeConfigJson(),
        Boolean.TRUE.equals(row.getYoloEnabled()),
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
    row.setAgentDefinitionId(thread.agentDefinitionId());
    row.setRuntimeConfigJson(thread.runtimeConfigJson());
    row.setYoloEnabled(thread.yoloEnabled());
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
        row.getAppliedEntryId(),
        row.getAppliedAt() == null ? null : row.getAppliedAt().toInstant(ZoneOffset.UTC),
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
    row.setAppliedEntryId(input.appliedEntryId());
    row.setAppliedAt(input.appliedAt() == null ? null : utc(input.appliedAt()));
    row.setCreateTime(utc(input.createdAt()));
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
