package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.util.List;

/**
 * Coherent PostgreSQL Thread projection used by the chat runtime.
 *
 * <p>All fields are read from one repeatable-read database snapshot. {@code revision} is the
 * durable invalidation cursor; realtime transport events are not part of this object.
 */
@Data
public class HarnessThreadSnapshotDTO {
  private String revision;
  private HarnessThreadDTO thread;
  private List<HarnessSessionEntryDTO> entries;
  private List<HarnessThreadInputDTO> inputs;
  private List<ModelInvocationDTO> modelInvocations;
  private List<ToolInvocationDTO> toolInvocations;
  private List<InteractionDTO> openInteractions;
  private ModelUsageSummaryDTO usage;
}
