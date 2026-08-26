package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/** Base Tools 的完整稳定 AgentToolId 集合契约测试。 */
class BaseToolIdsTest {

  /** 锁定官方 Base Tool 的完整值集合，并确保没有重复 identity。 */
  @Test
  void exposesCompleteStableValuesWithoutDuplicates() {
    List<AgentToolId> ids =
        List.of(
            BaseToolIds.READ,
            BaseToolIds.WRITE,
            BaseToolIds.EDIT,
            BaseToolIds.APPLY_PATCH,
            BaseToolIds.BASH,
            BaseToolIds.GREP,
            BaseToolIds.FIND,
            BaseToolIds.LSP_GOTO_DEFINITION,
            BaseToolIds.LSP_WORKSPACE_SYMBOLS,
            BaseToolIds.LSP_JAVA_DECOMPILE,
            BaseToolIds.MCP_LIST_TOOLS,
            BaseToolIds.MCP_CALL_TOOL,
            BaseToolIds.LOAD_SKILL,
            BaseToolIds.TASK,
            BaseToolIds.GOAL_CREATE,
            BaseToolIds.GOAL_GET,
            BaseToolIds.GOAL_UPDATE);

    assertEquals(
        List.of(
            "base.read",
            "base.write",
            "base.edit",
            "base.apply-patch",
            "base.bash",
            "base.grep",
            "base.find",
            "base.lsp-goto-definition",
            "base.lsp-workspace-symbols",
            "base.lsp-java-decompile",
            "base.mcp-list-tools",
            "base.mcp-call-tool",
            "base.load-skill",
            "base.task",
            "base.goal.create",
            "base.goal.get",
            "base.goal.update"),
        ids.stream().map(AgentToolId::value).toList());
    assertEquals(ids.size(), Set.copyOf(ids).size());
  }
}
