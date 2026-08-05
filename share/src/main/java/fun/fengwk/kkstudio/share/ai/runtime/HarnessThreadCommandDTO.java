package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.time.Instant;

/**
 * Durable Thread mailbox command projection.
 *
 * <p>{@code state} is derived from durable terminal markers (QUEUED/APPLIED/CANCELLED); {@code
 * payloadJson} is the canonical JSON of the typed command payload.
 */
@Data
public class HarnessThreadCommandDTO {
  private String commandId;
  private String threadId;
  private String sequence;
  private String type;
  private String state;
  private String clientCommandId;
  private String payloadJson;
  private String consumedTurnStartEntryId;
  private Instant cancelledAt;
  private Instant createTime;
}
