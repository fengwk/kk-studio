package fun.fengwk.kkstudio.core.harness.tool.store;

import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolPermissionDecision;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** ToolInvocation 查询与持久快照转换；状态写入由对应事务服务协调。 */
@Repository
public class MysqlToolInvocationStore {
  private final ToolInvocationMapper mapper;

  public MysqlToolInvocationStore(ToolInvocationMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  public Optional<ToolInvocation> find(long id) {
    return Optional.ofNullable(mapper.find(id)).map(this::toInvocation);
  }

  public List<ToolInvocation> listByRun(long runId) {
    return mapper.listByRun(runId).stream().map(this::toInvocation).toList();
  }

  public ToolInvocation toInvocation(ToolInvocationDO source) {
    return new ToolInvocation(
        source.getId(),
        source.getRunId(),
        source.getAssistantEntryId(),
        source.getOrdinal(),
        source.getToolCallId(),
        source.getToolName(),
        source.getToolVersion(),
        ToolTargetType.valueOf(source.getTargetType()),
        source.getEnvironmentId(),
        source.getArgumentsJson(),
        ToolInvocationStatus.valueOf(source.getStatus()),
        PermissionAction.valueOf(source.getPermissionAction()),
        source.getPermissionDecision() == null
            ? null
            : ToolPermissionDecision.valueOf(source.getPermissionDecision()),
        ToolSideEffect.valueOf(source.getSideEffect()),
        instant(source.getDeadlineAt()),
        source.getLeaseOwner(),
        instant(source.getLeaseUntil()),
        instant(source.getCancelRequestedAt()),
        source.getResultJson(),
        source.getErrorMessage(),
        instant(source.getCreateTime()),
        instant(source.getStartedAt()),
        instant(source.getFinishedAt()),
        instant(source.getUpdateTime()));
  }

  private static Instant instant(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
}
