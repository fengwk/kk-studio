package fun.fengwk.kkstudio.harness.environment.capability;

import java.util.Set;

/** 固定 Environment Capability 的原子 canonical 身份。 */
public final class EnvironmentCapabilityIds {

  public static final EnvironmentCapabilityId FS_READ = new EnvironmentCapabilityId("fs.read");
  public static final EnvironmentCapabilityId FS_WRITE = new EnvironmentCapabilityId("fs.write");
  public static final EnvironmentCapabilityId FS_APPLY_EDIT =
      new EnvironmentCapabilityId("fs.apply-edit");
  public static final EnvironmentCapabilityId PROCESS_EXEC =
      new EnvironmentCapabilityId("process.exec");
  public static final EnvironmentCapabilityId FS_SEARCH = new EnvironmentCapabilityId("fs.search");
  public static final EnvironmentCapabilityId FS_FIND = new EnvironmentCapabilityId("fs.find");
  public static final EnvironmentCapabilityId LSP_GOTO_DEFINITION =
      new EnvironmentCapabilityId("lsp.goto-definition");
  public static final EnvironmentCapabilityId LSP_WORKSPACE_SYMBOLS =
      new EnvironmentCapabilityId("lsp.workspace-symbols");
  public static final EnvironmentCapabilityId LSP_JAVA_DECOMPILE =
      new EnvironmentCapabilityId("lsp.java-decompile");
  public static final EnvironmentCapabilityId SKILL_LOAD =
      new EnvironmentCapabilityId("skill.load");
  public static final EnvironmentCapabilityId SKILL_SOURCE_REFRESH =
      new EnvironmentCapabilityId("skill.source.refresh");
  public static final EnvironmentCapabilityId SKILL_SOURCE_INSTALL =
      new EnvironmentCapabilityId("skill.source.install");
  public static final EnvironmentCapabilityId SKILL_SOURCE_UPDATE =
      new EnvironmentCapabilityId("skill.source.update");

  /** 仅供管理执行器使用的能力：复用 INVOKE/CANCEL/结果通道，但绝不进入模型 Tool 目录。 */
  public static final Set<EnvironmentCapabilityId> MANAGEMENT_ONLY =
      Set.of(SKILL_SOURCE_REFRESH, SKILL_SOURCE_INSTALL, SKILL_SOURCE_UPDATE);

  private EnvironmentCapabilityIds() {}
}
