package fun.fengwk.kkstudio.harness.runtime.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

/** Branch settings 的 immutable snapshot、canonical 名称校验与「不保存目录状态」契约。 */
class BranchSettingsTest {

  private static final ModelSelection MODEL =
      new ModelSelection("anthropic", "claude-sonnet", "default");

  @Test
  void preservesAgentNameModelAndEnvironmentName() {
    BranchSettings settings = new BranchSettings("coding", MODEL, null);

    assertEquals("coding", settings.agentName());
    assertEquals("claude-sonnet", settings.model().modelName());
    assertNull(settings.environmentName());
    assertEquals("local", new BranchSettings("coding", MODEL, "local").environmentName());
  }

  /** 目录不再属于 branch 历史：record 组件固定为 agentName/model/environmentName，任何 workspace 字段都不允许回归。 */
  @Test
  void exposesNoWorkspaceState() {
    assertEquals(
        List.of("agentName", "model", "environmentName"),
        Arrays.stream(BranchSettings.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList());
  }

  @Test
  void replacesAgentNameAndModelViaWithMethods() {
    BranchSettings base = new BranchSettings("coding", MODEL, "local");

    BranchSettings rebound = base.withAgentName("reviewer");
    BranchSettings remodelled =
        rebound.withModel(new ModelSelection("anthropic", "claude-opus", "default"));

    assertEquals("reviewer", rebound.agentName());
    assertEquals(base.model(), rebound.model());
    // withModel 只换 model：agentName 保持上游 withAgentName 的结果，不回退为 base。
    assertEquals("reviewer", remodelled.agentName());
    assertEquals("claude-opus", remodelled.model().modelName());
    // withAgentName / withModel 都不得隐式改变环境选择。
    assertEquals("local", rebound.environmentName());
    assertEquals("local", remodelled.environmentName());
  }

  /** 测试意图：withEnvironmentName 是环境选择的唯一变更入口，并且必须允许由非 null 解除为 null。 */
  @Test
  void replacesEnvironmentNameIncludingClearingToNull() {
    BranchSettings base = new BranchSettings("coding", MODEL, "local");

    assertEquals("shared", base.withEnvironmentName("shared").environmentName());
    assertNull(base.withEnvironmentName(null).environmentName());
    // 其他字段不受影响。
    assertEquals("coding", base.withEnvironmentName("shared").agentName());
    assertEquals(MODEL, base.withEnvironmentName("shared").model());
  }

  @Test
  void rejectsNullModelBlankAndOversizedNames() {
    assertThrows(IllegalArgumentException.class, () -> new BranchSettings(" ", MODEL, null));
    assertThrows(
        IllegalArgumentException.class, () -> new BranchSettings("c".repeat(129), MODEL, null));
    assertThrows(NullPointerException.class, () -> new BranchSettings("coding", null, null));
  }

  /** 测试意图：environmentName 的 canonical 约束必须与 Environment 资源名一致（非 blank、无首尾空白、不含 '/'、≤64）。 */
  @Test
  void rejectsNonCanonicalEnvironmentNamesButAllowsNull() {
    assertNull(new BranchSettings("coding", MODEL, null).environmentName());
    assertThrows(IllegalArgumentException.class, () -> new BranchSettings("coding", MODEL, " "));
    assertThrows(IllegalArgumentException.class, () -> new BranchSettings("coding", MODEL, " env"));
    assertThrows(IllegalArgumentException.class, () -> new BranchSettings("coding", MODEL, "env "));
    assertThrows(IllegalArgumentException.class, () -> new BranchSettings("coding", MODEL, "a/b"));
    assertThrows(
        IllegalArgumentException.class, () -> new BranchSettings("coding", MODEL, "e".repeat(65)));
    assertEquals(
        "e".repeat(64), new BranchSettings("coding", MODEL, "e".repeat(64)).environmentName());
  }
}
