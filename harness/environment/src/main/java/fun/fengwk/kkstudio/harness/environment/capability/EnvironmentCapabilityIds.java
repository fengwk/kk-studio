package fun.fengwk.kkstudio.harness.environment.capability;

/** 固定 Environment Capability 的原子 canonical 身份。 */
public final class EnvironmentCapabilityIds {

  public static final EnvironmentCapabilityId FS_READ = new EnvironmentCapabilityId("fs.read");
  public static final EnvironmentCapabilityId FS_WRITE = new EnvironmentCapabilityId("fs.write");
  public static final EnvironmentCapabilityId FS_APPLY_EDIT =
      new EnvironmentCapabilityId("fs.apply-edit");
  public static final EnvironmentCapabilityId FS_APPLY_PATCH =
      new EnvironmentCapabilityId("fs.apply-patch");
  public static final EnvironmentCapabilityId PROCESS_EXEC =
      new EnvironmentCapabilityId("process.exec");
  public static final EnvironmentCapabilityId FS_SEARCH = new EnvironmentCapabilityId("fs.search");
  public static final EnvironmentCapabilityId FS_FIND = new EnvironmentCapabilityId("fs.find");
  public static final EnvironmentCapabilityId FS_LIST_DIRECTORY =
      new EnvironmentCapabilityId("fs.list-directory");
  public static final EnvironmentCapabilityId LSP_GOTO_DEFINITION =
      new EnvironmentCapabilityId("lsp.goto-definition");
  public static final EnvironmentCapabilityId LSP_WORKSPACE_SYMBOLS =
      new EnvironmentCapabilityId("lsp.workspace-symbols");
  public static final EnvironmentCapabilityId LSP_JAVA_DECOMPILE =
      new EnvironmentCapabilityId("lsp.java-decompile");
  public static final EnvironmentCapabilityId MCP_LIST = new EnvironmentCapabilityId("mcp.list");
  public static final EnvironmentCapabilityId MCP_CALL = new EnvironmentCapabilityId("mcp.call");
  public static final EnvironmentCapabilityId SKILL_LOAD =
      new EnvironmentCapabilityId("skill.load");

  private EnvironmentCapabilityIds() {}
}
