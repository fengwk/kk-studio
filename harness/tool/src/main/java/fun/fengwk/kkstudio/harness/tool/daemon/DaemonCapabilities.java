package fun.fengwk.kkstudio.harness.tool.daemon;

import java.util.List;
import java.util.Objects;

/**
 * Daemon READY 上报的版本化/类型化能力摘要。
 *
 * <p>只包含可安全上报的短字段：environment metadata、skills 与 MCP server 摘要；完整 SKILL.md 正文、workdir、headers、命令、URL
 * 与完整工具 schema 不进入 READY wire。MCP 工具完整 schema 只通过固定的 {@code mcp_list_tools} 桥接工具按需返回。
 */
public record DaemonCapabilities(
    int version,
    DaemonEnvironmentInfo environment,
    List<DaemonSkillDescriptor> skills,
    List<DaemonMcpServerDescriptor> mcpServers) {

  /** READY capabilities 协议版本；与 {@link DaemonCapabilitiesCodec} 共享。 */
  public static final int VERSION = 3;

  public DaemonCapabilities {
    if (version != VERSION) {
      throw new IllegalArgumentException("unsupported capabilities version: " + version);
    }
    environment = Objects.requireNonNull(environment, "environment");
    skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
    mcpServers = List.copyOf(Objects.requireNonNull(mcpServers, "mcpServers"));
    requireUniqueSkillNames(skills);
    requireUniqueServerNames(mcpServers);
  }

  private static void requireUniqueSkillNames(List<DaemonSkillDescriptor> skills) {
    long unique = skills.stream().map(DaemonSkillDescriptor::name).distinct().count();
    if (unique != skills.size()) {
      throw new IllegalArgumentException("duplicate READY skill name");
    }
  }

  private static void requireUniqueServerNames(List<DaemonMcpServerDescriptor> mcpServers) {
    long unique = mcpServers.stream().map(DaemonMcpServerDescriptor::name).distinct().count();
    if (unique != mcpServers.size()) {
      throw new IllegalArgumentException("duplicate READY MCP server name");
    }
  }
}
