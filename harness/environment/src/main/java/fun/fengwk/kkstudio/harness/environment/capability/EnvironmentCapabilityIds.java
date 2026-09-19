package fun.fengwk.kkstudio.harness.environment.capability;

import java.util.Set;

/** 固定 Environment Capability 的原子 canonical 身份。 */
public final class EnvironmentCapabilityIds {

  public static final EnvironmentCapabilityId FS_READ = new EnvironmentCapabilityId("fs.read");
  public static final EnvironmentCapabilityId FS_WRITE = new EnvironmentCapabilityId("fs.write");
  public static final EnvironmentCapabilityId FS_EDIT = new EnvironmentCapabilityId("fs.edit");
  public static final EnvironmentCapabilityId PROCESS_EXEC =
      new EnvironmentCapabilityId("process.exec");
  public static final EnvironmentCapabilityId FS_GREP = new EnvironmentCapabilityId("fs.grep");
  public static final EnvironmentCapabilityId FS_FIND = new EnvironmentCapabilityId("fs.find");
  public static final EnvironmentCapabilityId LSP_GOTO_DEFINITION =
      new EnvironmentCapabilityId("lsp.goto-definition");
  public static final EnvironmentCapabilityId LSP_WORKSPACE_SYMBOLS =
      new EnvironmentCapabilityId("lsp.workspace-symbols");
  public static final EnvironmentCapabilityId LSP_JAVA_DECOMPILE =
      new EnvironmentCapabilityId("lsp.java-decompile");
  public static final EnvironmentCapabilityId MCP_LOCAL_CALL =
      new EnvironmentCapabilityId("mcp.local.call");
  public static final EnvironmentCapabilityId MCP_LOCAL_DISCOVER =
      new EnvironmentCapabilityId("mcp.local.discover");

  /** 仅供管理执行器使用的能力：复用 INVOKE/CANCEL/结果通道，但绝不进入模型 Tool 目录。 */
  public static final Set<EnvironmentCapabilityId> MANAGEMENT_ONLY = Set.of(MCP_LOCAL_DISCOVER);

  private EnvironmentCapabilityIds() {}
}
