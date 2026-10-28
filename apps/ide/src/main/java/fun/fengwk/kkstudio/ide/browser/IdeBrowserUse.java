package fun.fengwk.kkstudio.ide.browser;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 一个浏览器 document 在单条事件连接上确认的完整 IDE 使用列表。
 *
 * <p>列表至多 64 项，按 {@code environmentId,viewId} 规范化排序；{@code viewId} 在列表内唯一。revision 是连接内单调递增的正整数， 同一
 * revision 的相同内容幂等，不同内容拒绝。没有 epoch 的项只是打开意图，不产生续租证明。
 */
public record IdeBrowserUse(UUID documentId, long revision, List<IdeBrowserUseView> views) {

  public static final int MAX_VIEWS = 64;

  public IdeBrowserUse {
    documentId = Objects.requireNonNull(documentId, "documentId");
    if (revision <= 0) {
      throw new IllegalArgumentException("revision must be positive");
    }
    Objects.requireNonNull(views, "views");
    if (views.size() > MAX_VIEWS) {
      throw new IllegalArgumentException("views must contain at most " + MAX_VIEWS + " entries");
    }
    List<IdeBrowserUseView> canonical = new ArrayList<>(views.size());
    Set<UUID> viewIds = new HashSet<>();
    for (IdeBrowserUseView view : views) {
      Objects.requireNonNull(view, "view");
      if (!viewIds.add(view.viewId())) {
        throw new IllegalArgumentException("views must not repeat a viewId");
      }
      canonical.add(view);
    }
    canonical.sort(
        Comparator.comparing((IdeBrowserUseView view) -> view.environmentId().toString())
            .thenComparing(view -> view.viewId().toString()));
    views = List.copyOf(canonical);
  }

  /** 解析 wire 的 documentId/revision；列表仍由已校验的 typed view 组成。 */
  public static IdeBrowserUse parse(
      String documentId, String revision, List<IdeBrowserUseView> views) {
    return new IdeBrowserUse(
        ConnectionIdentity.parseCanonicalUuid(documentId, "documentId"),
        IdeBrowserUseView.parsePositiveLong(revision, "revision"),
        views);
  }

  /** 查找文档中与给定打开/恢复意图完全相同的项（不要求已绑定 epoch）。 */
  public Optional<IdeBrowserUseView> findExactIntent(
      EnvironmentId environmentId, UUID viewId, UUID claimId) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(claimId, "claimId");
    return views.stream()
        .filter(
            view ->
                view.environmentId().equals(environmentId)
                    && view.viewId().equals(viewId)
                    && view.claimId().equals(claimId))
        .findFirst();
  }

  /** 只保留已带 binding epoch 的持有项；没有任何可续租项时返回空。 */
  public Optional<IdeBrowserUse> activeBoundProofs() {
    List<IdeBrowserUseView> bound = views.stream().filter(IdeBrowserUseView::bound).toList();
    return bound.isEmpty()
        ? Optional.empty()
        : Optional.of(new IdeBrowserUse(documentId, revision, bound));
  }
}
