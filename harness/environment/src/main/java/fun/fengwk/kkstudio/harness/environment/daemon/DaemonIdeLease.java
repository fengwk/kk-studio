package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * IDE_LEASE payload：一轮批量使用证明，不创建视图、不续租快照查询。
 *
 * <p>最多 64 条绑定；viewId 必须唯一，viewId/documentId 为 canonical UUID，bindingEpoch 与 round 为正十进制 long。
 */
public record DaemonIdeLease(long round, List<Binding> bindings) {

  public static final int MAX_BINDINGS = 64;

  public DaemonIdeLease {
    if (round <= 0) {
      throw new IllegalArgumentException("round must be a positive long");
    }
    bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
    if (bindings.size() > MAX_BINDINGS) {
      throw new IllegalArgumentException("bindings must not exceed " + MAX_BINDINGS);
    }
    Set<UUID> viewIds = new HashSet<>();
    for (Binding binding : bindings) {
      Objects.requireNonNull(binding, "binding");
      if (!viewIds.add(binding.viewId())) {
        throw new IllegalArgumentException("bindings must not repeat a viewId");
      }
    }
  }

  /** 单条视图使用证明。 */
  public record Binding(UUID viewId, UUID documentId, long bindingEpoch) {

    public Binding {
      viewId = Objects.requireNonNull(viewId, "viewId");
      documentId = Objects.requireNonNull(documentId, "documentId");
      if (bindingEpoch <= 0) {
        throw new IllegalArgumentException("bindingEpoch must be a positive long");
      }
    }
  }
}
