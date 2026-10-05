package fun.fengwk.kkstudio.platform.project.tool;

import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContextFragment;
import fun.fengwk.kkstudio.harness.contributor.api.ContextProjector;
import fun.fengwk.kkstudio.harness.contributor.api.CustomStateSnapshot;
import fun.fengwk.kkstudio.project.turn.ProjectRunScope;
import fun.fengwk.kkstudio.project.turn.ProjectRunScopeJsonCodec;

import java.util.List;

/**
 * 把本 branch 冻结的 {@code project/run} custom state 投影为模型可见的 Issue Agent 上下文段。
 *
 * <p>上下文只来自 branch 历史中本 contributor 自己的状态：没有冻结的 run 快照（普通 Thread）时投影为空；有不兼容 schema 版本或损坏
 * 快照时确定性失败关闭，绝不静默降级成"没有 Issue 上下文"。运行时不再反查 Thread 属于哪个 Issue。
 */
public final class ProjectRunContextProjector implements ContextProjector {

  private static final ProjectRunScopeJsonCodec CODEC = new ProjectRunScopeJsonCodec();

  @Override
  public List<ContextFragment> project(BranchView view) {
    CustomStateSnapshot snapshot = view.latestCustomEntry(ProjectRunScope.CUSTOM_TYPE).orElse(null);
    if (snapshot == null) {
      return List.of();
    }
    if (snapshot.schemaVersion() != ProjectRunScope.SCHEMA_VERSION) {
      throw new IllegalStateException(
          "unsupported project run scope schema version: " + snapshot.schemaVersion());
    }
    ProjectRunScope scope;
    try {
      scope = CODEC.decode(snapshot.dataJson());
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException("invalid project run scope", error);
    }
    if (!scope.active()) {
      // 已关闭的 Run 不再投影任何 Issue 上下文：branch 上后写的 scope 才代表当前生命周期位置。
      return List.of();
    }
    return List.of(new ContextFragment(scope.contextSection()));
  }
}
