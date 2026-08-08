package fun.fengwk.kkstudio.harness.tool.daemon;

import java.util.List;
import java.util.Objects;

/**
 * Daemon READY 上报的版本化/类型化能力摘要。
 *
 * <p>只包含可安全上报的短字段：skills 与 MCP server 摘要；本地路径、完整 SKILL.md 正文、headers、命令、URL 与完整工具 schema 不进入 READY
 * wire。MCP 工具完整 schema 只通过固定的 {@code mcp_list_tools} 桥接工具按需返回。
 */
public record DaemonCapabilities(
    int version, List<DaemonSkillDescriptor> skills, List<DaemonMcpServerDescriptor> mcpServers) {

  /** READY capabilities 协议版本；与 {@link DaemonCapabilitiesCodec} 共享。 */
  public static final int VERSION = 1;

  public DaemonCapabilities {
    if (version != VERSION) {
      throw new IllegalArgumentException("unsupported capabilities version: " + version);
    }
    skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
    mcpServers = List.copyOf(Objects.requireNonNull(mcpServers, "mcpServers"));
    requireUniqueSkillNames(skills);
    requireUniqueServerNames(mcpServers);
  }

  /** 空能力集，用于未配置 skills/MCP 的 daemon 或 registry 初值。 */
  public static DaemonCapabilities empty() {
    return new DaemonCapabilities(VERSION, List.of(), List.of());
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
