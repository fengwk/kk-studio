package fun.fengwk.kkstudio.ide.browser;

import java.util.List;
import java.util.Objects;

/** 事件节点返回的已绑定使用证明快照；只包含带 binding epoch、当前仍可续租的持有项。 */
public record IdeBrowserUseSnapshot(
    String eventNodeId, String connectionId, String documentId, String revision, List<View> views) {

  public IdeBrowserUseSnapshot {
    eventNodeId = Objects.requireNonNull(eventNodeId, "eventNodeId");
    connectionId = Objects.requireNonNull(connectionId, "connectionId");
    documentId = Objects.requireNonNull(documentId, "documentId");
    revision = Objects.requireNonNull(revision, "revision");
    views = List.copyOf(Objects.requireNonNull(views, "views"));
  }

  /** 单个已绑定持有项；bindingEpoch 是 canonical 正十进制字符串。 */
  public record View(String environmentId, String viewId, String claimId, String bindingEpoch) {

    public View {
      environmentId = Objects.requireNonNull(environmentId, "environmentId");
      viewId = Objects.requireNonNull(viewId, "viewId");
      claimId = Objects.requireNonNull(claimId, "claimId");
      bindingEpoch = Objects.requireNonNull(bindingEpoch, "bindingEpoch");
    }
  }

  public static IdeBrowserUseSnapshot from(
      ConnectionIdentity identity, IdeBrowserUse activeBoundProofs) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(activeBoundProofs, "activeBoundProofs");
    List<View> viewBodies =
        activeBoundProofs.views().stream()
            .map(
                view ->
                    new View(
                        view.environmentId().toString(),
                        view.viewId().toString(),
                        view.claimId().toString(),
                        Long.toString(view.bindingEpoch())))
            .toList();
    return new IdeBrowserUseSnapshot(
        identity.eventNodeId().toString(),
        identity.connectionId().toString(),
        activeBoundProofs.documentId().toString(),
        Long.toString(activeBoundProofs.revision()),
        viewBodies);
  }

  /** 严格还原 typed usage；wire 字符串非法或列表为空时抛 {@link IllegalArgumentException}。 */
  public IdeBrowserUse toUsage() {
    if (views.isEmpty()) {
      throw new IllegalArgumentException("active browser usage snapshot must not be empty");
    }
    List<IdeBrowserUseView> parsed =
        views.stream()
            .map(
                view ->
                    IdeBrowserUseView.parse(
                        view.environmentId(), view.viewId(), view.claimId(), view.bindingEpoch()))
            .toList();
    return IdeBrowserUse.parse(documentId, revision, parsed);
  }
}
