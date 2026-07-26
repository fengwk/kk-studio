package fun.fengwk.kkstudio.core.harness.query;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.harness.interaction.store.model.InteractionDO;
import fun.fengwk.kkstudio.core.harness.model.worker.ModelInvocationDO;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.InteractionDTO;
import fun.fengwk.kkstudio.share.model.ModelInvocationDTO;
import fun.fengwk.kkstudio.share.model.RootActivityDTO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** final schema query rows → share DTO 投影。 */
@Component
public class HarnessQueryDtoConverter {

  public HarnessSessionDTO toSession(HarnessQueryRow row) {
    if (row == null) {
      return null;
    }
    HarnessSessionDTO dto = new HarnessSessionDTO();
    dto.setSessionId(HarnessIds.format(row.getId()));
    dto.setTitle(row.getTitle());
    dto.setParentSessionId(formatNullable(row.getParentSessionId()));
    dto.setParentInvocationId(formatNullable(row.getParentInvocationId()));
    // final schema 不存储 root_session_id/depth；roots 为自身 id。
    if (row.getParentSessionId() == null) {
      dto.setRootSessionId(HarnessIds.format(row.getId()));
      dto.setDepth(0);
    }
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
    dto.setSessionId(HarnessIds.format(row.getSessionId()));
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
    dto.setStatus(DerivedThreadStatus.derive(row, now));
    dto.setInputSequence(row.getInputSequence());
    // RUNTIME_CONFIG 派生字段不在 final thread row；保持 null 而非假数据。
    dto.setActiveAgentDefinitionId(null);
    dto.setActiveAgentName(null);
    dto.setModelId(null);
    dto.setVariant(null);
    dto.setYoloEnabled(null);
    dto.setProcessing(
        DerivedThreadStatus.isProcessing(row.getProcessorToken(), row.getProcessorUntil(), now));
    dto.setCreateTime(toUtcLocal(row.getCreatedAt()));
    dto.setUpdateTime(toUtcLocal(row.getUpdatedAt()));
    return dto;
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
    dto.setSessionId(HarnessIds.format(row.getSessionId()));
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
    return dto;
  }

  public RootActivityDTO toRootActivity(long rootSessionId, HarnessQueryRow thread, Instant now) {
    String status = DerivedThreadStatus.derive(thread, now);
    RootActivityDTO dto = new RootActivityDTO();
    dto.setRootSessionId(HarnessIds.format(rootSessionId));
    dto.setSessionId(HarnessIds.format(thread.getSessionId()));
    dto.setThreadId(HarnessIds.format(thread.getId()));
    // Root activity 使用 thread id 作为稳定查询 cursor；Redis realtime 使用独立 stream id。
    dto.setEventId(HarnessIds.format(thread.getId()));
    dto.setEventType(status);
    String title = thread.getSessionTitle() == null ? "" : thread.getSessionTitle();
    dto.setPayloadJson(
        "{\"status\":\""
            + status
            + "\",\"title\":"
            + quoteJson(title)
            + ",\"updatedAt\":\""
            + (thread.getUpdatedAt() == null ? "" : thread.getUpdatedAt().toInstant())
            + "\"}");
    dto.setCreateTime(
        toUtcLocal(thread.getUpdatedAt() != null ? thread.getUpdatedAt() : thread.getCreatedAt()));
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

  private static String quoteJson(String value) {
    StringBuilder out = new StringBuilder(value.length() + 2);
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        out.append('\\').append(c);
      } else if (c < 0x20) {
        out.append(String.format("\\u%04x", (int) c));
      } else {
        out.append(c);
      }
    }
    out.append('"');
    return out.toString();
  }
}
