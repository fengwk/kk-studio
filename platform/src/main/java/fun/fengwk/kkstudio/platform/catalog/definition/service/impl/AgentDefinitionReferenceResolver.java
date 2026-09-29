package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogQueryService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.harness.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 解析全局 Agent definition、model、Skill 与 MCP 工具引用。
 *
 * <p>写入事务的锁序固定为 Skill package {@code FOR SHARE}、MCP server {@code FOR SHARE}、Agent {@code FOR
 * UPDATE}。 引用集合先完整收集再按 canonical name 升序加锁，锁后重读存在性，不边锁边遍历。
 */
@AllArgsConstructor
@Component
final class AgentDefinitionReferenceResolver {

  private static final String DEFINITION_RESOURCE = "agent_definition";
  private static final String MODEL_RESOURCE = "agent_model";
  private static final String SKILL_RESOURCE = "skill";
  private static final String TOOL_RESOURCE = "agent_tool";
  private static final ContributorId MCP_CONTRIBUTOR = McpToolCatalog.CONTRIBUTOR_ID;

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentModelRepository agentModelRepository;
  private final SkillCatalogQueryService skillCatalogQueryService;
  private final McpServerRepository mcpServerRepository;
  private final RuntimeToolCatalog toolCatalog;

  AgentDefinition requireAgent(String name) {
    AgentDefinition definition = agentDefinitionRepository.getByName(name);
    if (definition == null) {
      throw new AiResourceNotFoundException(DEFINITION_RESOURCE);
    }
    return definition;
  }

  AgentDefinition requireAgentForUpdate(String name) {
    AgentDefinition definition = agentDefinitionRepository.getByNameForUpdate(name);
    if (definition == null) {
      throw new AiResourceNotFoundException(DEFINITION_RESOURCE);
    }
    return definition;
  }

  AgentModel requireModel(String providerName, String modelName) {
    AgentModel model = agentModelRepository.getByProviderNameAndName(providerName, modelName);
    if (model == null) {
      throw new AiResourceNotFoundException(MODEL_RESOURCE);
    }
    return model;
  }

  AgentModel requireModelForUpdate(String providerName, String modelName) {
    AgentModel model =
        agentModelRepository.getByProviderNameAndNameForUpdate(providerName, modelName);
    if (model == null) {
      throw new AiResourceNotFoundException(MODEL_RESOURCE);
    }
    return model;
  }

  /**
   * 在任何 Agent 行锁之前，按 canonical name 升序共享锁定被引用的 Skill package 与 MCP server，并重读存在性。
   *
   * <p>MCP 归属只认 {@code platform.mcp} 贡献身份。锁前解析出的 tool→server 映射在共享锁之后必须仍然相同且工具行存在； builtin/plugin
   * 工具没有 MCP 行，不取 server 锁。
   */
  void requireReferencedLifecycles(List<SkillRefDTO> skills, List<String> toolNames) {
    Map<String, String> skillNames = skillNames(skills);
    Map<String, String> mcpServers = mcpServers(toolNames);
    for (String packageName : new TreeSet<>(skillNames.keySet())) {
      SkillPackage locked = skillCatalogQueryService.lockPackageForShare(packageName);
      String skillName = skillNames.get(packageName);
      if (locked == null || locked.findSkill(skillName) == null) {
        throw new AiValidationException(
            SKILL_RESOURCE, "unknown agent skills: " + packageName + "/" + skillName);
      }
    }
    for (String serverName : new TreeSet<>(mcpServers.keySet())) {
      String toolName = mcpServers.get(serverName);
      McpServer locked =
          mcpServerRepository
              .lockForShare(serverName)
              .orElseThrow(
                  () ->
                      new AiValidationException(TOOL_RESOURCE, "unknown agent tool: " + toolName));
      McpTool tool =
          mcpServerRepository
              .lockToolForShare(toolName)
              .filter(candidate -> serverName.equals(candidate.getServerName()))
              .orElse(null);
      if (tool == null || !serverName.equals(locked.getName())) {
        throw new AiValidationException(TOOL_RESOURCE, "unknown agent tool: " + toolName);
      }
    }
  }

  private Map<String, String> skillNames(List<SkillRefDTO> skills) {
    Map<String, String> names = new LinkedHashMap<>();
    if (skills == null || skills.isEmpty()) {
      return names;
    }
    List<String> missing = new ArrayList<>();
    for (SkillRefDTO ref : skills) {
      String identity = ref == null ? "null" : ref.getPackageName() + "/" + ref.getName();
      SkillPackage pkg =
          ref == null ? null : skillCatalogQueryService.getPackage(ref.getPackageName());
      if (pkg == null || pkg.findSkill(ref.getName()) == null) {
        if (!missing.contains(identity)) {
          missing.add(identity);
        }
        continue;
      }
      names.putIfAbsent(ref.getPackageName(), ref.getName());
    }
    if (!missing.isEmpty()) {
      missing.sort(String::compareTo);
      throw new AiValidationException(
          SKILL_RESOURCE, "unknown agent skills: " + String.join(", ", missing));
    }
    return names;
  }

  private Map<String, String> mcpServers(List<String> toolNames) {
    Map<String, String> servers = new LinkedHashMap<>();
    if (toolNames == null || toolNames.isEmpty()) {
      return servers;
    }
    for (String toolName : toolNames) {
      ToolContribution contribution = toolCatalog.findTool(toolName).orElse(null);
      if (contribution == null || !MCP_CONTRIBUTOR.equals(contribution.id().contributorId())) {
        continue;
      }
      McpTool tool = mcpServerRepository.getTool(toolName).orElse(null);
      if (tool == null || tool.getServerName() == null) {
        throw new AiValidationException(TOOL_RESOURCE, "unknown agent tool: " + toolName);
      }
      String previous = servers.putIfAbsent(tool.getServerName(), toolName);
      if (previous != null && !previous.equals(toolName)) {
        servers.put(tool.getServerName(), previous.compareTo(toolName) <= 0 ? previous : toolName);
      }
    }
    return servers;
  }

  /**
   * 创建 Agent 时锁定并校验 task allowlist 中的既有 Agent。
   *
   * <p>自身引用无需预先存在：目标行将在同一事务中插入；其它名称仍必须存在并加锁。
   */
  void requireSubagentsForCreate(String agentName, List<String> names) {
    for (String name : names.stream().sorted().toList()) {
      if (!name.equals(agentName)) {
        requireAgentForUpdate(name);
      }
    }
  }

  /**
   * 以统一名称顺序锁定待更新 Agent 与其新 allowlist，避免交叉引用更新形成反向行锁顺序。
   *
   * @return 已锁定的待更新 Agent
   */
  AgentDefinition requireAgentAndSubagentsForUpdate(String agentName, List<String> subagentNames) {
    TreeSet<String> names = new TreeSet<>(subagentNames);
    names.add(agentName);
    AgentDefinition target = null;
    for (String name : names) {
      AgentDefinition definition = requireAgentForUpdate(name);
      if (name.equals(agentName)) {
        target = definition;
      }
    }
    return target;
  }

  void ensureNotReferencedAsSubagent(String name) {
    if (agentDefinitionRepository.existsReferencingSubagent(name)) {
      throw new AiInUseException(
          DEFINITION_RESOURCE,
          DEFINITION_RESOURCE + " is referenced by another agent subagents allowlist: " + name);
    }
  }
}
