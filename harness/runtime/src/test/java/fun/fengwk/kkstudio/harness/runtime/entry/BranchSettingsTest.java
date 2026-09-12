package fun.fengwk.kkstudio.harness.runtime.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

/** Branch settings 的 immutable snapshot、agentName 校验与「不保存目录状态」契约。 */
class BranchSettingsTest {

  @Test
  void preservesAgentNameAndModel() {
    BranchSettings settings =
        new BranchSettings("coding", new ModelSelection("anthropic", "claude-sonnet", "default"));

    assertEquals("coding", settings.agentName());
    assertEquals("claude-sonnet", settings.model().modelName());
  }

  /** 目录不再属于 branch 历史：record 组件固定为 agentName/model，任何 workspace 字段都不允许回归。 */
  @Test
  void exposesNoWorkspaceState() {
    assertEquals(
        List.of("agentName", "model"),
        Arrays.stream(BranchSettings.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList());
  }

  @Test
  void replacesAgentNameAndModelViaWithMethods() {
    BranchSettings base =
        new BranchSettings("coding", new ModelSelection("anthropic", "claude-sonnet", "default"));

    BranchSettings rebound = base.withAgentName("reviewer");
    BranchSettings remodelled =
        rebound.withModel(new ModelSelection("anthropic", "claude-opus", "default"));

    assertEquals("reviewer", rebound.agentName());
    assertEquals(base.model(), rebound.model());
    // withModel 只换 model：agentName 保持上游 withAgentName 的结果，不回退为 base。
    assertEquals("reviewer", remodelled.agentName());
    assertEquals("claude-opus", remodelled.model().modelName());
  }

  @Test
  void rejectsNullModelBlankAndOversizedNames() {
    ModelSelection model = new ModelSelection("anthropic", "claude-sonnet", "default");
    assertThrows(IllegalArgumentException.class, () -> new BranchSettings(" ", model));
    assertThrows(IllegalArgumentException.class, () -> new BranchSettings("c".repeat(129), model));
    assertThrows(NullPointerException.class, () -> new BranchSettings("coding", null));
  }
}
