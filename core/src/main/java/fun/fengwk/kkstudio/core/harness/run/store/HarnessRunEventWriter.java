package fun.fengwk.kkstudio.core.harness.run.store;

import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunEventMapper;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunEventDO;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.harness.runtime.run.RunEvent;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunIdGenerator;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Single durable writer for harness_run_event sequence allocation and insertion.
 *
 * <p>Callers must already hold locks in {@code Run -> Session -> Root} order before {@link
 * #appendLocked(HarnessRunDO, List, Instant)}. Paths that have not locked yet should use {@link
 * #lockAndAppend(long, List, Instant)} or {@link #lockSessionAndRoot(long)} after locking the Run.
 */
@Component
public class HarnessRunEventWriter {
  private final HarnessRunMapper runMapper;
  private final HarnessRunEventMapper eventMapper;
  private final HarnessSessionMapper sessionMapper;
  private final RunIdGenerator idGenerator;

  public HarnessRunEventWriter(
      HarnessRunMapper runMapper,
      HarnessRunEventMapper eventMapper,
      HarnessSessionMapper sessionMapper,
      RunIdGenerator idGenerator) {
    this.runMapper = Objects.requireNonNull(runMapper, "runMapper");
    this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
  }

  /**
   * Locks {@code Run -> Session -> Root}, then allocates sequences/ids and inserts events.
   * Snowflake event ids are generated only after the Root lock is held so a lower id cannot commit
   * after a higher id visible under the same root.
   */
  public List<RunEvent> lockAndAppend(long runId, List<RunEventDraft> drafts, Instant now) {
    HarnessRunDO run = runMapper.findForUpdate(runId);
    if (run == null) {
      throw new IllegalArgumentException("unknown run: " + runId);
    }
    lockSessionAndRoot(run.getSessionId());
    return appendLocked(run, drafts, now);
  }

  /**
   * Locks Session then Root for a Run that is already locked. Lower-order locks must only be taken
   * after this returns.
   */
  public HarnessSessionDO lockSessionAndRoot(long sessionId) {
    HarnessSessionDO session = sessionMapper.findForUpdate(sessionId);
    if (session == null) {
      throw new IllegalStateException("unknown session: " + sessionId);
    }
    lockRoot(session);
    return session;
  }

  /** Locks the root session when the given session is not itself the root. */
  public HarnessSessionDO lockRoot(HarnessSessionDO session) {
    Objects.requireNonNull(session, "session");
    if (session.getRootSessionId() == null) {
      throw new IllegalStateException("session root is missing: " + session.getId());
    }
    if (Objects.equals(session.getRootSessionId(), session.getId())) {
      if (session.getParentSessionId() != null) {
        throw new IllegalStateException("invalid root session: " + session.getId());
      }
      return session;
    }
    HarnessSessionDO root = sessionMapper.findForUpdate(session.getRootSessionId());
    if (root == null
        || root.getParentSessionId() != null
        || !Objects.equals(root.getRootSessionId(), root.getId())) {
      throw new IllegalStateException("invalid root session for session: " + session.getId());
    }
    return root;
  }

  /**
   * Allocates sequences and inserts events. The Run row must already be locked for update and
   * Session/Root locks must already be held.
   */
  public List<RunEvent> appendLocked(HarnessRunDO run, List<RunEventDraft> drafts, Instant now) {
    Objects.requireNonNull(run, "run");
    Objects.requireNonNull(now, "now");
    List<RunEventDraft> events = List.copyOf(Objects.requireNonNull(drafts, "drafts"));
    if (events.isEmpty()) {
      throw new IllegalArgumentException("drafts must not be empty");
    }
    long expectedSequence = run.getEventSequence();
    long finalSequence = Math.addExact(expectedSequence, events.size());
    LocalDateTime timestamp = utc(now);
    if (runMapper.updateEventSequence(run.getId(), expectedSequence, finalSequence, timestamp)
        != 1) {
      throw new ConcurrentModificationException(
          "cannot allocate run event sequences: " + run.getId());
    }
    List<RunEvent> written = new ArrayList<>(events.size());
    long sequence = expectedSequence;
    for (RunEventDraft draft : events) {
      RunEvent event =
          new RunEvent(
              idGenerator.newRunEventId(),
              run.getId(),
              ++sequence,
              draft.type(),
              draft.payloadJson(),
              now);
      if (eventMapper.insert(toDO(event, timestamp)) != 1) {
        throw new ConcurrentModificationException("cannot append run event: " + run.getId());
      }
      written.add(event);
    }
    run.setEventSequence(finalSequence);
    return List.copyOf(written);
  }

  public RunEvent appendLocked(
      HarnessRunDO run, RunEventType type, String payloadJson, Instant now) {
    return appendLocked(run, List.of(new RunEventDraft(type, payloadJson)), now).get(0);
  }

  private static HarnessRunEventDO toDO(RunEvent event, LocalDateTime timestamp) {
    HarnessRunEventDO target = new HarnessRunEventDO();
    target.setId(event.id());
    target.setRunId(event.runId());
    target.setSequence(event.sequence());
    target.setEventType(event.type().value());
    target.setPayloadJson(event.payloadJson());
    target.setCreateTime(timestamp);
    return target;
  }

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }
}
