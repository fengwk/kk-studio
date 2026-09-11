package fun.fengwk.kkstudio.harness.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 验证 14 个稳定 AgentToolId 的常量值。 */
class BuiltinToolIdsTest {

  @Test
  void exactBuiltinToolIdsValues() {
    assertEquals("base.read", BuiltinToolIds.READ.value());
    assertEquals("base.write", BuiltinToolIds.WRITE.value());
    assertEquals("base.edit", BuiltinToolIds.EDIT.value());
    assertEquals("base.bash", BuiltinToolIds.BASH.value());
    assertEquals("base.grep", BuiltinToolIds.GREP.value());
    assertEquals("base.find", BuiltinToolIds.FIND.value());
    assertEquals("base.lsp-goto-definition", BuiltinToolIds.LSP_GOTO_DEFINITION.value());
    assertEquals("base.lsp-workspace-symbols", BuiltinToolIds.LSP_WORKSPACE_SYMBOLS.value());
    assertEquals("base.lsp-java-decompile", BuiltinToolIds.LSP_JAVA_DECOMPILE.value());
    assertEquals("base.load-skill", BuiltinToolIds.LOAD_SKILL.value());
    assertEquals("base.task", BuiltinToolIds.TASK.value());
    assertEquals("base.goal.create", BuiltinToolIds.GOAL_CREATE.value());
    assertEquals("base.goal.get", BuiltinToolIds.GOAL_GET.value());
    assertEquals("base.goal.update", BuiltinToolIds.GOAL_UPDATE.value());
  }
}
