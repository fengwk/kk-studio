package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.util.List;

/**
 * Atomic Thread mailbox enqueue request.
 *
 * <p>{@code expectedHeadEntryId} and {@code expectedNextCommandSequence} are the exact CAS cursors
 * read from the latest {@link HarnessThreadDTO}; {@code threadId} comes from the path. There is
 * deliberately no batch identity.
 */
@Data
public class HarnessThreadCommandBatchDTO {
  private String expectedHeadEntryId;
  private String expectedNextCommandSequence;
  private List<HarnessThreadCommandCreateDTO> commands = List.of();
}
