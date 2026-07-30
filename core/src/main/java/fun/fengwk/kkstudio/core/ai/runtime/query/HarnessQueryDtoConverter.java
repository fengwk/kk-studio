package fun.fengwk.kkstudio.core.ai.runtime.query;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.runtime.interaction.store.model.InteractionDO;
import fun.fengwk.kkstudio.core.ai.runtime.model.worker.ModelInvocationDO;
import fun.fengwk.kkstudio.core.ai.runtime.session.support.HarnessIds;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.ai.runtime.InteractionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ModelInvocationDTO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** final schema query rows → share DTO 投影。 */
@Component
public class HarnessQueryDtoConverter {

  private static final RuntimeConfigJsonCodec RUNTIME_CONFIG_CODEC = new RuntimeConfigJsonCodec();

  public HarnessSessionDTO toSession(HarnessQueryRow row) {
    if (row == null) {
      return null;
    }
    HarnessSessionDTO dto = new HarnessSessionDTO();
    dto.setSessionId(HarnessIds.format(row.getId()));
    dto.setTitle(row.getTitle());
    dto.setCreateTime(toUtcLocal(row.getCreatedAt()));
    dto.setUpdateTime(toUtcLocal(row.getUpdatedAt()));
    return dto;
  }

  public HarnessSessionEntryDTO toEntry(HarnessQueryRow row) {
    if (row == null) {
      return null;
    }
    HarnessSessionEntryDTO dto = new HarnessSessionEntryDTO();
    dto.setEntryId(HarnessIds.format(row.getId()));
    dto.setParentEntryId(formatNullable(row.getParentEntryId()));
    dto.setEntryType(row.getEntryType());
    dto.setPayloadJson(row.getPayloadJson());
    dto.setCreateTime(toUtcLocal(row.getCreatedAt()));
    return dto;
  }

  public HarnessThreadDTO toThread(HarnessQueryRow row, Instant now) {
    if (row == null) {
      return null;
    }
    HarnessThreadDTO dto = new HarnessThreadDTO();
    dto.setThreadId(HarnessIds.format(row.getId()));
    dto.setSessionId(formatNullable(row.getSessionId()));
    dto.setSessionTitle(row.getSessionTitle());
    dto.setHeadEntryId(formatNullable(row.getHeadEntryId()));
    dto.setExecutionEpoch(row.getExecutionEpoch());
    dto.setRevision(row.getRevision() == null ? null : Long.toString(row.getRevision()));
    dto.setStatus(DerivedThreadStatus.derive(row, now));
    dto.setInputSequence(row.getInputSequence());
    projectRuntimeConfig(dto, row.getRuntimeConfigJson());
    dto.setProcessing(
        DerivedThreadStatus.isProcessing(row.getProcessorToken(), row.getProcessorUntil(), now));
    dto.setCreateTime(toUtcLocal(row.getCreatedAt()));
    dto.setUpdateTime(toUtcLocal(row.getUpdatedAt()));
    return dto;
  }

  /**
   * Thread 的运行配置来自 current head 的祖先 RUNTIME_CONFIG Entry，而非 live Agent 定义。
   *
   * <p>因此查询投影稳定反映当前 Thread 的冻结执行配置；UNBOUND Thread 和没有配置祖先的异常历史路径保持 nullable。
   */
  private static void projectRuntimeConfig(HarnessThreadDTO dto, String runtimeConfigJson) {
    if (runtimeConfigJson == null) {
      return;
    }
    RuntimeConfigSnapshot config = RUNTIME_CONFIG_CODEC.decode(runtimeConfigJson);
    dto.setActiveAgentDefinitionId(HarnessIds.format(config.agent().definitionId()));
    dto.setActiveAgentName(config.agent().name());
    dto.setModelId(HarnessIds.format(config.model().descriptor().modelResourceId()));
    dto.setVariant(config.model().variant().id());
    dto.setYoloEnabled(config.yoloEnabled());
  }

  public HarnessThreadInputDTO toInput(HarnessQueryRow row) {
    if (row == null) {
      return null;
    }
    HarnessThreadInputDTO dto = new HarnessThreadInputDTO();
    dto.setInputId(HarnessIds.format(row.getId()));
    dto.setThreadId(HarnessIds.format(row.getThreadId()));
    dto.setSequence(row.getSequence());
    dto.setInputType(row.getInputType());
    dto.setPayloadJson(row.getPayloadJson());
    dto.setClientMessageId(row.getIdempotencyKey());
    dto.setStatus(row.getStatus());
    dto.setResolvedAt(toUtcLocal(row.getAppliedAt()));
    dto.setCreateTime(toUtcLocal(row.getCreatedAt()));
    return dto;
  }

  public InteractionDTO toInteraction(InteractionDO row) {
    if (row == null) {
      return null;
    }
    InteractionDTO dto = new InteractionDTO();
    dto.setId(HarnessIds.format(row.getId()));
    dto.setOwnerKind(row.getOwnerKind());
    dto.setOwnerId(HarnessIds.format(row.getOwnerId()));
    dto.setHandlerType(row.getHandlerType());
    dto.setProjectionJson(row.getRequestJson());
    dto.setStatus(row.getStatus());
    dto.setResponseJson(row.getResponseJson());
    dto.setExpiresAt(toInstant(row.getExpiresAt()));
    dto.setVersion(row.getVersion() == null ? null : Long.toString(row.getVersion()));
    dto.setCreatedAt(toInstant(row.getCreatedAt()));
    dto.setResolvedAt(toInstant(row.getResolvedAt()));
    return dto;
  }

  public ModelInvocationDTO toModelInvocation(ModelInvocationDO row) {
    if (row == null) {
      return null;
    }
    ModelInvocationDTO dto = new ModelInvocationDTO();
    dto.setId(HarnessIds.format(row.getId()));
    dto.setThreadId(HarnessIds.format(row.getThreadId()));
    dto.setSourceHeadEntryId(HarnessIds.format(row.getSourceHeadEntryId()));
    dto.setExecutionEpoch(row.getExecutionEpoch());
    dto.setRequestJson(row.getRequestJson());
    dto.setStatus(row.getStatus());
    dto.setAttempt(row.getAttempt());
    dto.setNextAttemptAt(toInstant(row.getNextAttemptAt()));
    dto.setWorkerUntil(toInstant(row.getWorkerUntil()));
    dto.setDeadlineAt(toInstant(row.getDeadlineAt()));
    dto.setLastActivityAt(toInstant(row.getLastActivityAt()));
    dto.setResultJson(row.getResultJson());
    dto.setErrorJson(row.getErrorJson());
    dto.setAppliedAt(toInstant(row.getAppliedAt()));
    dto.setCreatedAt(toInstant(row.getCreatedAt()));
    dto.setStartedAt(toInstant(row.getStartedAt()));
    dto.setFinishedAt(toInstant(row.getFinishedAt()));
    dto.setSafeStreamSnapshotJson(row.getSafeStreamSnapshotJson());
    return dto;
  }

  private static String formatNullable(Long value) {
    return value == null ? null : HarnessIds.format(value);
  }

  private static LocalDateTime toUtcLocal(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }

  private static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}
