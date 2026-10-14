package fun.fengwk.kkstudio.platform.configsync;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncKind;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncRef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于一次快照构建配置引用依赖图并计算传递闭包。
 *
 * <p>有向边完全来自现有业务事实：Model → Provider；Agent → Model / Skill Package / Subagent / MCP Service（经 MCP
 * 工具名 归属）；Settings → 备用 Model 与提示词 Agent。闭包做去重并对互相引用的 Agent 终止。Environment 是独立条目，没有反向扩展。
 */
@Component
public final class ConfigSyncGraph {

  private static final String RESOURCE = "config_sync";

  private final AgentDefinitionConfigCodec agentConfigCodec;

  public ConfigSyncGraph(AgentDefinitionConfigCodec agentConfigCodec) {
    this.agentConfigCodec = agentConfigCodec;
  }

  /** 为一次快照预构建依赖图与存在集合，供 inventory 与 export 复用，避免按条目重复构图。 */
  public Graph graph(ConfigSyncSnapshot snapshot) {
    Map<String, String> toolServer = new LinkedHashMap<>();
    for (McpTool tool : snapshot.mcpTools()) {
      toolServer.put(tool.getName(), tool.getServerName());
    }

    Map<ConfigSyncRef, List<ConfigSyncRef>> edges = new LinkedHashMap<>();
    for (AgentProvider provider : snapshot.providers()) {
      edges.put(providerRef(provider), List.of());
    }
    for (AgentModel model : snapshot.models()) {
      edges.put(
          ConfigSyncRefs.model(model.getProviderName(), model.getName()),
          List.of(ConfigSyncRefs.ref(ConfigSyncKind.PROVIDERS, model.getProviderName())));
    }
    for (AgentDefinition agent : snapshot.agents()) {
      edges.put(agentRef(agent), agentDependencies(agent, toolServer));
    }
    for (SkillPackage pkg : snapshot.skillPackages()) {
      edges.put(skillRef(pkg), List.of());
    }
    for (Environment env : snapshot.environments()) {
      edges.put(environmentRef(env), List.of());
    }
    for (McpServer server : snapshot.mcpServers()) {
      edges.put(mcpRef(server), List.of());
    }
    edges.put(
        ConfigSyncRefs.ref(ConfigSyncKind.SETTINGS, "settings"),
        settingsDependencies(snapshot.settings().settings()));

    List<ConfigSyncRef> allRefs = allRefs(snapshot);
    return new Graph(edges, new LinkedHashSet<>(allRefs), allRefs);
  }

  /** 全部现存条目的稳定顺序。 */
  public List<ConfigSyncRef> allRefs(ConfigSyncSnapshot snapshot) {
    List<ConfigSyncRef> refs = new ArrayList<>();
    snapshot.providers().forEach(provider -> refs.add(providerRef(provider)));
    snapshot
        .models()
        .forEach(model -> refs.add(ConfigSyncRefs.model(model.getProviderName(), model.getName())));
    snapshot.agents().forEach(agent -> refs.add(agentRef(agent)));
    snapshot.skillPackages().forEach(pkg -> refs.add(skillRef(pkg)));
    snapshot.environments().forEach(env -> refs.add(environmentRef(env)));
    snapshot.mcpServers().forEach(server -> refs.add(mcpRef(server)));
    refs.add(ConfigSyncRefs.ref(ConfigSyncKind.SETTINGS, "settings"));
    return refs;
  }

  /**
   * 计算某条目在给定图中的传递依赖闭包（不含自身），返回顺序为 BFS 发现顺序，已去重并终止循环。
   *
   * <p>只返回在快照中真实存在的引用；缺失引用（例如 Settings 指向不存在的 Agent）不会出现在 inventory 结果。
   */
  public List<ConfigSyncRef> closure(Graph graph, ConfigSyncRef root) {
    Set<ConfigSyncRef> visited = new LinkedHashSet<>();
    visited.add(root);
    List<ConfigSyncRef> result = new ArrayList<>();
    ArrayDeque<ConfigSyncRef> queue = new ArrayDeque<>();
    queue.add(root);
    while (!queue.isEmpty()) {
      ConfigSyncRef current = queue.poll();
      for (ConfigSyncRef dependency : graph.edges().getOrDefault(current, List.of())) {
        if (!visited.add(dependency)) {
          continue;
        }
        if (graph.present().contains(dependency)) {
          result.add(dependency);
        }
        queue.add(dependency);
      }
    }
    return List.copyOf(result);
  }

  /**
   * 把导出选择补齐为闭包（含选择本身），保持稳定顺序。
   *
   * <p>空选择、不存在的引用或无法满足的依赖都作为安全校验失败抛出，绝不输出不完整或不可导入的包。未选择到的既有依赖不在此校验范围。
   */
  public List<ConfigSyncRef> expand(Graph graph, List<ConfigSyncRef> selected) {
    if (selected == null || selected.isEmpty()) {
      throw new AiValidationException(RESOURCE, "export requires at least one config item");
    }
    Set<ConfigSyncRef> ordered = new LinkedHashSet<>();
    for (ConfigSyncRef ref : selected) {
      if (ref == null || !graph.present().contains(ref)) {
        throw new AiValidationException(RESOURCE, "export references an unknown config item");
      }
      ordered.add(ref);
      ordered.addAll(strictClosure(graph, ref));
    }
    return List.copyOf(ordered);
  }

  /** 严格闭包：任一边指向不存在条目即视为依赖缺失并拒绝导出。 */
  private List<ConfigSyncRef> strictClosure(Graph graph, ConfigSyncRef root) {
    Set<ConfigSyncRef> visited = new LinkedHashSet<>();
    visited.add(root);
    List<ConfigSyncRef> result = new ArrayList<>();
    ArrayDeque<ConfigSyncRef> queue = new ArrayDeque<>();
    queue.add(root);
    while (!queue.isEmpty()) {
      ConfigSyncRef current = queue.poll();
      for (ConfigSyncRef dependency : graph.edges().getOrDefault(current, List.of())) {
        if (!graph.present().contains(dependency)) {
          throw new AiValidationException(
              RESOURCE, "export item has a missing dependency: " + dependency.getName());
        }
        if (!visited.add(dependency)) {
          continue;
        }
        result.add(dependency);
        queue.add(dependency);
      }
    }
    return List.copyOf(result);
  }

  private List<ConfigSyncRef> agentDependencies(
      AgentDefinition agent, Map<String, String> toolServer) {
    Set<ConfigSyncRef> deps = new LinkedHashSet<>();
    deps.add(ConfigSyncRefs.model(agent.getModelProviderName(), agent.getModelName()));
    AgentDefinitionConfigDTO config = agentConfigCodec.decode(agent.getConfigJson());
    if (config.getSkills() != null) {
      for (SkillRefDTO skill : config.getSkills()) {
        deps.add(ConfigSyncRefs.ref(ConfigSyncKind.SKILL_PACKAGES, skill.getPackageName()));
      }
    }
    if (config.getSubagents() != null) {
      for (String subagent : config.getSubagents()) {
        deps.add(ConfigSyncRefs.ref(ConfigSyncKind.AGENTS, subagent));
      }
    }
    if (config.getTools() != null) {
      for (String tool : config.getTools()) {
        String server = toolServer.get(tool);
        if (server != null) {
          deps.add(ConfigSyncRefs.ref(ConfigSyncKind.MCP_SERVERS, server));
        }
      }
    }
    return List.copyOf(deps);
  }

  private List<ConfigSyncRef> settingsDependencies(SystemSettings settings) {
    Set<ConfigSyncRef> deps = new LinkedHashSet<>();
    String promptAgent = settings.integrations().minimaxH3().promptAgentName();
    if (promptAgent != null) {
      deps.add(ConfigSyncRefs.ref(ConfigSyncKind.AGENTS, promptAgent));
    }
    return List.copyOf(deps);
  }

  private static ConfigSyncRef providerRef(AgentProvider provider) {
    return ConfigSyncRefs.ref(ConfigSyncKind.PROVIDERS, provider.getName());
  }

  private static ConfigSyncRef agentRef(AgentDefinition agent) {
    return ConfigSyncRefs.ref(ConfigSyncKind.AGENTS, agent.getName());
  }

  private static ConfigSyncRef skillRef(SkillPackage pkg) {
    return ConfigSyncRefs.ref(ConfigSyncKind.SKILL_PACKAGES, pkg.getPackageName());
  }

  private static ConfigSyncRef environmentRef(Environment env) {
    return ConfigSyncRefs.ref(ConfigSyncKind.ENVIRONMENTS, env.getName());
  }

  private static ConfigSyncRef mcpRef(McpServer server) {
    return ConfigSyncRefs.ref(ConfigSyncKind.MCP_SERVERS, server.getName());
  }

  /** 一次快照的依赖图：边、存在集合与稳定顺序的全部条目。 */
  public record Graph(
      Map<ConfigSyncRef, List<ConfigSyncRef>> edges,
      Set<ConfigSyncRef> present,
      List<ConfigSyncRef> allRefs) {

    public Graph {
      edges = Map.copyOf(edges);
      present = Set.copyOf(present);
      allRefs = List.copyOf(allRefs);
    }
  }
}
