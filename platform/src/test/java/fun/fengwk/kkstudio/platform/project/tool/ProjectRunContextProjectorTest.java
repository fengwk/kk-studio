package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContextFragment;
import fun.fengwk.kkstudio.harness.contributor.api.CustomStateSnapshot;
import fun.fengwk.kkstudio.harness.contributor.api.GoalSnapshot;
import fun.fengwk.kkstudio.project.turn.ProjectRunScope;
import fun.fengwk.kkstudio.project.turn.ProjectRunScopeJsonCodec;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ProjectRunContextProjector} 只把本 branch 冻结的 {@code project/run} state 投影为 Issue
 * 上下文：没有冻结状态时为空， 版本不符或快照损坏时确定性失败关闭，绝不静默降级成"没有 Issue 上下文"。
 */
class ProjectRunContextProjectorTest {

  private static final ProjectRunScope SCOPE =
      new ProjectRunScope(
          UUID.fromString("11111111-1111-1111-1111-111111111111"),
          UUID.fromString("22222222-2222-2222-2222-222222222222"),
          UUID.fromString("33333333-3333-3333-3333-333333333333"),
          UUID.fromString("44444444-4444-4444-4444-444444444444"),
          7L,
          "修复登录",
          "登录偶发失败",
          "DESIGN",
          "设计",
          "完成可交付方案",
          List.of("REVIEW"),
          "worker");

  private final ProjectRunContextProjector projector = new ProjectRunContextProjector();
  private final ProjectRunScopeJsonCodec codec = new ProjectRunScopeJsonCodec();

  /** 没有冻结 run 快照的普通 Thread 投影为空。 */
  @Test
  void projectsNothingWithoutFrozenScope() {
    assertEquals(List.of(), projector.project(view(Optional.empty())));
  }

  /** 冻结快照按本 Contributor 的 custom state 渲染为唯一 Issue 上下文片段。 */
  @Test
  void projectsFrozenScopeAsSingleFragment() {
    List<ContextFragment> fragments =
        projector.project(
            view(
                Optional.of(
                    new CustomStateSnapshot(ProjectRunScope.SCHEMA_VERSION, codec.encode(SCOPE)))));

    assertEquals(1, fragments.size());
    String text = fragments.get(0).text();
    assertTrue(text.contains("# Issue Agent Context"), text);
    assertTrue(text.contains("run_id: " + SCOPE.runId()), text);
    assertTrue(text.contains("stage: DESIGN (设计)"), text);
    assertTrue(text.contains("issue_transition"), text);
  }

  /** 不兼容 schema 版本确定性失败关闭。 */
  @Test
  void failsClosedOnUnsupportedSchemaVersion() {
    assertThrows(
        IllegalStateException.class,
        () ->
            projector.project(
                view(
                    Optional.of(
                        new CustomStateSnapshot(
                            ProjectRunScope.SCHEMA_VERSION + 1, codec.encode(SCOPE))))));
  }

  /** 损坏快照确定性失败关闭，而不是投影为空。 */
  @Test
  void failsClosedOnCorruptSnapshot() {
    assertThrows(
        IllegalStateException.class,
        () ->
            projector.project(
                view(
                    Optional.of(
                        new CustomStateSnapshot(ProjectRunScope.SCHEMA_VERSION, "{oops}")))));
  }

  private static BranchView view(Optional<CustomStateSnapshot> latest) {
    return new BranchView() {
      @Override
      public List<CustomStateSnapshot> customEntries(String customType) {
        return latest.isPresent() ? List.of(latest.get()) : List.of();
      }

      @Override
      public Optional<CustomStateSnapshot> latestCustomEntry(String customType) {
        return latest;
      }

      @Override
      public Optional<GoalSnapshot> goal() {
        return Optional.empty();
      }
    };
  }
}
