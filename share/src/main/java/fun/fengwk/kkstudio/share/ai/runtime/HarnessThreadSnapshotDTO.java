package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

import java.util.List;

/**
 * Coherent Thread 快照投影。
 *
 * <p>所有字段来自同一数据库快照；{@code revision} 是 durable invalidation cursor。{@code modelInvocation} 为当前 Turn
 * 的活跃模型调用（无则 null），{@code toolInvocations} 为其工具兄弟； 列表默认为不可变空列表。
 */
@Data
public class HarnessThreadSnapshotDTO {
  private String revision;
  private HarnessThreadDTO thread;
  private List<HarnessSessionEntryDTO> entries = List.of();
  private List<HarnessThreadCommandDTO> queuedCommands = List.of();
  private ModelInvocationDTO modelInvocation;
  private List<ToolInvocationDTO> toolInvocations = List.of();
}
