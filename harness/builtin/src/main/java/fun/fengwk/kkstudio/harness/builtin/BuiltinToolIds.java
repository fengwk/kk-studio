package fun.fengwk.kkstudio.harness.builtin;

import fun.fengwk.kkstudio.harness.tool.AgentToolId;

/** 第一方内置 Tool 的稳定全局 AgentToolId 标识。 */
public final class BuiltinToolIds {

  public static final AgentToolId READ = new AgentToolId("base.read");
  public static final AgentToolId WRITE = new AgentToolId("base.write");
  public static final AgentToolId EDIT = new AgentToolId("base.edit");
  public static final AgentToolId APPLY_PATCH = new AgentToolId("base.apply-patch");
  public static final AgentToolId BASH = new AgentToolId("base.bash");
  public static final AgentToolId GREP = new AgentToolId("base.grep");
  public static final AgentToolId FIND = new AgentToolId("base.find");
  public static final AgentToolId LSP_GOTO_DEFINITION = new AgentToolId("base.lsp-goto-definition");
  public static final AgentToolId LSP_WORKSPACE_SYMBOLS =
      new AgentToolId("base.lsp-workspace-symbols");
  public static final AgentToolId LSP_JAVA_DECOMPILE = new AgentToolId("base.lsp-java-decompile");
  public static final AgentToolId MCP_LIST_TOOLS = new AgentToolId("base.mcp-list-tools");
  public static final AgentToolId MCP_CALL_TOOL = new AgentToolId("base.mcp-call-tool");
  public static final AgentToolId LOAD_SKILL = new AgentToolId("base.load-skill");
  public static final AgentToolId TASK = new AgentToolId("base.task");
  public static final AgentToolId GOAL_CREATE = new AgentToolId("base.goal.create");
  public static final AgentToolId GOAL_GET = new AgentToolId("base.goal.get");
  public static final AgentToolId GOAL_UPDATE = new AgentToolId("base.goal.update");

  private BuiltinToolIds() {}
}
