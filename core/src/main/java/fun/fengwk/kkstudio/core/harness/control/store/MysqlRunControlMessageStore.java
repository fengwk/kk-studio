package fun.fengwk.kkstudio.core.harness.control.store;

import fun.fengwk.kkstudio.core.harness.control.store.mapper.HarnessRunControlMessageMapper;
import fun.fengwk.kkstudio.core.harness.control.store.model.HarnessRunControlMessageDO;
import fun.fengwk.kkstudio.harness.runtime.control.ControlConsumptionMode;
import fun.fengwk.kkstudio.harness.runtime.control.ControlMessageCodec;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlKind;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlMessage;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlMessageStore;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** MySQL/H2 持久化控制消息；严格遵循 port 的最小契约，所有 CAS 以 {@code status='PENDING'} 为前置条件。 */
@Repository
public class MysqlRunControlMessageStore implements RunControlMessageStore {

  private final HarnessRunControlMessageMapper mapper;
  private final ControlMessageCodec messageCodec = new ControlMessageCodec();

  public MysqlRunControlMessageStore(HarnessRunControlMessageMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public int insert(RunControlMessage message) {
    Objects.requireNonNull(message, "message");
    // 仅接受新建 PENDING 的 control 入库；终态行属于 CAS 推进的范畴，不能借 insert 复用。
    if (message.status() != RunControlStatus.PENDING) {
      throw new IllegalArgumentException(
          "control insert only accepts PENDING but was " + message.status());
    }
    return mapper.insert(toDO(message, messageCodec));
  }

  @Override
  public Optional<RunControlMessage> find(long controlId) {
    return Optional.ofNullable(mapper.find(controlId)).map(this::toMessage);
  }

  @Override
  public List<RunControlMessage> listPendingByRun(long originalRunId, RunControlKind kind) {
    Objects.requireNonNull(kind, "kind");
    return mapper.listPendingByRun(originalRunId, kind.name()).stream().map(this::toMessage).toList();
  }

  @Override
  public List<RunControlMessage> listPendingBySession(long sessionId) {
    return mapper.listPendingBySession(sessionId).stream().map(this::toMessage).toList();
  }

  @Override
  public boolean markConsumed(long controlId, long consumedRunId, long consumedEntryId, Instant now) {
    Objects.requireNonNull(now, "now");
    return mapper.markConsumed(controlId, consumedRunId, consumedEntryId, utc(now)) == 1;
  }

  @Override
  public boolean markPromoted(long controlId, long consumedRunId, long consumedEntryId, Instant now) {
    Objects.requireNonNull(now, "now");
    return mapper.markPromoted(controlId, consumedRunId, consumedEntryId, utc(now)) == 1;
  }

  @Override
  public boolean markCleared(long controlId, Instant now) {
    Objects.requireNonNull(now, "now");
    return mapper.markCleared(controlId, utc(now)) == 1;
  }

  @Override
  public int clearPendingByRun(long originalRunId, Instant now) {
    Objects.requireNonNull(now, "now");
    return mapper.clearPendingByRun(originalRunId, utc(now));
  }

  static HarnessRunControlMessageDO toDO(RunControlMessage message, ControlMessageCodec codec) {
    HarnessRunControlMessageDO target = new HarnessRunControlMessageDO();
    target.setId(message.id());
    target.setSessionId(message.sessionId());
    target.setRunId(message.originalRunId());
    target.setControlKind(message.kind().name());
    target.setConsumptionMode(message.consumptionMode().name());
    target.setMessageJson(codec.encode(message.message()));
    target.setStatus(message.status().name());
    target.setConsumedRunId(message.consumedRunId());
    target.setConsumedEntryId(message.consumedEntryId());
    target.setCreateTime(utc(message.createdAt()));
    target.setConsumedAt(message.consumedAt() == null ? null : utc(message.consumedAt()));
    target.setUpdateTime(utc(message.consumedAt() == null ? message.createdAt() : message.consumedAt()));
    return target;
  }

  private RunControlMessage toMessage(HarnessRunControlMessageDO source) {
    AgentMessage message = messageCodec.decode(source.getMessageJson());
    return new RunControlMessage(
        source.getId(),
        source.getSessionId(),
        source.getRunId(),
        RunControlKind.valueOf(source.getControlKind()),
        ControlConsumptionMode.valueOf(source.getConsumptionMode()),
        message,
        RunControlStatus.valueOf(source.getStatus()),
        source.getConsumedRunId(),
        source.getConsumedEntryId(),
        instant(source.getCreateTime()),
        instant(source.getConsumedAt()));
  }

  private static LocalDateTime utc(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant instant(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
}
