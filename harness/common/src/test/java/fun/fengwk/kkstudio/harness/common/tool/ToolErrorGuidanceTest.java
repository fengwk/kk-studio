package fun.fengwk.kkstudio.harness.common.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.tool.ToolErrorGuidance.ExecutionFact;

/** {@link ToolErrorGuidance} 三段式文案：事实区分、句子归一化与空值拒绝。 */
class ToolErrorGuidanceTest {

  /** 三种执行事实必须使用各自固定的句子，验证拒绝不得声称已执行，超时/不确定不得声称未执行。 */
  @Test
  void executionFactsStayDistinct() {
    assertEquals("The tool was not executed.", ExecutionFact.NOT_EXECUTED.fact());
    assertTrue(ExecutionFact.FAILED.fact().contains("side effects may have occurred"));
    assertTrue(ExecutionFact.UNCERTAIN.fact().contains("cannot be confirmed"));
  }

  /** 缺句号的片段被补齐；已有句末标点的片段原样保留。 */
  @Test
  void joinsThreePartsAndNormalizesSentenceTerminators() {
    assertEquals(
        "path is not absolute. The tool was not executed. Pass an absolute path.",
        ToolErrorGuidance.message(
            "path is not absolute", ExecutionFact.NOT_EXECUTED, "Pass an absolute path"));
    assertEquals(
        "path is not absolute. The tool was not executed. Pass an absolute path.",
        ToolErrorGuidance.message(
            "path is not absolute.", ExecutionFact.NOT_EXECUTED, "Pass an absolute path."));
  }

  /** 空白片段是调用方错误，必须显式失败而不是产出空洞文案。 */
  @Test
  void rejectsBlankFragments() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolErrorGuidance.message("  ", ExecutionFact.NOT_EXECUTED, "next"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolErrorGuidance.message("what", ExecutionFact.NOT_EXECUTED, " "));
  }

  /** 已带执行事实的文案被识别，避免对同一错误重复包装。 */
  @Test
  void isGuidedDetectsExistingExecutionFacts() {
    assertFalse(ToolErrorGuidance.isGuided(null));
    assertFalse(ToolErrorGuidance.isGuided("path must be absolute"));
    assertTrue(ToolErrorGuidance.isGuided(ExecutionFact.NOT_EXECUTED.fact()));
    assertTrue(
        ToolErrorGuidance.isGuided(ToolErrorGuidance.message("x", ExecutionFact.UNCERTAIN, "y")));
  }
}
