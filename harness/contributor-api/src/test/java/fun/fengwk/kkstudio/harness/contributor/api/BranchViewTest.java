package fun.fengwk.kkstudio.harness.contributor.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link BranchView} 纯接口契约与自定义状态快照、追加意图、上下文投影等值对象测试。 */
class BranchViewTest {

  /** 验证 BranchView 是纯接口且不暴露任何底层历史路径或实现细节。 */
  @Test
  void branchViewIsPureInterface() {
    assertTrue(BranchView.class.isInterface());
    assertFalse(BranchView.class.isRecord());
  }

  /** 验证 BranchView 纯接口可以通过实现类正确提供 customEntries 和 latestCustomEntry 查询。 */
  @Test
  void branchViewMockProvidesStateSnapshots() {
    CustomStateSnapshot snapshot1 = new CustomStateSnapshot(1, "{\"item\":1}");
    CustomStateSnapshot snapshot2 = new CustomStateSnapshot(2, "{\"item\":2}");

    BranchView view =
        new BranchView() {
          @Override
          public List<CustomStateSnapshot> customEntries(String customType) {
            if ("my-state".equals(customType)) {
              return List.of(snapshot1, snapshot2);
            }
            return List.of();
          }

          @Override
          public Optional<CustomStateSnapshot> latestCustomEntry(String customType) {
            if ("my-state".equals(customType)) {
              return Optional.of(snapshot2);
            }
            return Optional.empty();
          }

          @Override
          public Optional<GoalSnapshot> goal() {
            return Optional.of(new GoalSnapshot(new UUID(0L, 9L), "ship it"));
          }
        };

    assertEquals(List.of(snapshot1, snapshot2), view.customEntries("my-state"));
    assertEquals(Optional.of(snapshot2), view.latestCustomEntry("my-state"));
    assertTrue(view.customEntries("other").isEmpty());
    assertTrue(view.latestCustomEntry("other").isEmpty());
    // 用户 Goal 是独立于 contributor custom state 的只读投影。
    assertEquals(Optional.of(new GoalSnapshot(new UUID(0L, 9L), "ship it")), view.goal());
  }

  /** 验证 CustomStateSnapshot 字段非空与版本号正数约束。 */
  @Test
  void customStateSnapshotValidatesFields() {
    CustomStateSnapshot snapshot = new CustomStateSnapshot(1, "{\"k\":\"v\"}");
    assertEquals(1, snapshot.schemaVersion());
    assertEquals("{\"k\":\"v\"}", snapshot.dataJson());

    assertThrows(IllegalArgumentException.class, () -> new CustomStateSnapshot(0, "{}"));
    assertThrows(IllegalArgumentException.class, () -> new CustomStateSnapshot(-1, "{}"));
    assertThrows(NullPointerException.class, () -> new CustomStateSnapshot(1, null));

    CustomStateSnapshot same = new CustomStateSnapshot(1, "{\"k\":\"v\"}");
    assertEquals(snapshot, same);
    assertEquals(snapshot.hashCode(), same.hashCode());
  }

  /** 验证 AppendCustomEntry 完整字段校验：canonical customType、正数版本号与非空 JSON。 */
  @Test
  void appendCustomEntryValidatesFields() {
    AppendCustomEntry entry = new AppendCustomEntry("goal.state", 1, "{\"progress\":50}");
    assertEquals("goal.state", entry.customType());
    assertEquals(1, entry.schemaVersion());
    assertEquals("{\"progress\":50}", entry.dataJson());

    // customType canonical 校验
    assertThrows(NullPointerException.class, () -> new AppendCustomEntry(null, 1, "{}"));
    assertThrows(IllegalArgumentException.class, () -> new AppendCustomEntry("", 1, "{}"));
    assertThrows(IllegalArgumentException.class, () -> new AppendCustomEntry("Goal", 1, "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> new AppendCustomEntry("goal_state", 1, "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> new AppendCustomEntry("goal..state", 1, "{}"));

    // schemaVersion 校验
    assertThrows(
        IllegalArgumentException.class, () -> new AppendCustomEntry("goal.state", 0, "{}"));
    assertThrows(
        IllegalArgumentException.class, () -> new AppendCustomEntry("goal.state", -1, "{}"));

    // dataJson 校验
    assertThrows(NullPointerException.class, () -> new AppendCustomEntry("goal.state", 1, null));

    AppendCustomEntry same = new AppendCustomEntry("goal.state", 1, "{\"progress\":50}");
    assertEquals(entry, same);
    assertEquals(entry.hashCode(), same.hashCode());
  }

  /** 验证 ContextFragment 文本非空校验与值对象语义。 */
  @Test
  void contextFragmentValidatesText() {
    ContextFragment fragment = new ContextFragment("Summary: done");
    assertEquals("Summary: done", fragment.text());

    assertThrows(NullPointerException.class, () -> new ContextFragment(null));

    ContextFragment same = new ContextFragment("Summary: done");
    assertEquals(fragment, same);
    assertEquals(fragment.hashCode(), same.hashCode());
  }

  /** 验证 ContextProjector 接收 BranchView 并返回 List<ContextFragment> 契约。 */
  @Test
  void contextProjectorProjectsBranchViewToFragments() {
    ContextProjector projector =
        view -> {
          Optional<CustomStateSnapshot> latest = view.latestCustomEntry("state");
          return latest.map(s -> List.of(new ContextFragment(s.dataJson()))).orElse(List.of());
        };

    BranchView viewWithState =
        new BranchView() {
          @Override
          public List<CustomStateSnapshot> customEntries(String customType) {
            return List.of(new CustomStateSnapshot(1, "state-data"));
          }

          @Override
          public Optional<CustomStateSnapshot> latestCustomEntry(String customType) {
            return Optional.of(new CustomStateSnapshot(1, "state-data"));
          }

          @Override
          public Optional<GoalSnapshot> goal() {
            return Optional.empty();
          }
        };

    List<ContextFragment> fragments = projector.project(viewWithState);
    assertEquals(1, fragments.size());
    assertEquals("state-data", fragments.get(0).text());
  }
}
