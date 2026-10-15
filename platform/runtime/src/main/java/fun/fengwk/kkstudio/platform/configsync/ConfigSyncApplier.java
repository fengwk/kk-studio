package fun.fengwk.kkstudio.platform.configsync;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerService;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.service.AgentModelService;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.SkillCatalogService;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.platform.plugin.service.PluginConfigurationCommitService;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsService;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSectionsDTO;
import fun.fengwk.kkstudio.share.systemsettings.SystemSettingsUpdateDTO;

import java.util.List;
import java.util.Map;

/**
 * 在一个数据库事务内落地 {@link ConfigSyncPlan}。
 *
 * <p>写入顺序遵守外键与引用依赖：Provider → Model → Skill Package → MCP Server → Environment → Agent → Plugin →
 * Settings。新增 Agent 先以空 subagent allowlist 创建，再在同一事务内统一更新为完整列表，使循环引用可解且事务外不可见中间态。任何写入失败都随异常整体回滚，绝不
 * 捕获后继续。
 *
 * <p>插件复用唯一的 {@link PluginConfigurationCommitService}：每次 configure 都在本事务内做 catalog epoch
 * CAS，第一项比较计划 epoch，后续用同事务前一成功提交的新 epoch，同时各自比较该插件计划期的配置修订；任一项冲突或失败都让整份导入回滚，afterCommit 才在本节点应用与通知。
 *
 * <p>计划在写事务外形成，因此事务内必须先按同一份插件声明重新核验依赖的 Agent 仍然存在：否则计划到写入之间被删除的 Agent 不会改变插件目录
 * epoch，导入会提交悬空依赖。核验用行锁读取，锁一直持有到外层事务结束。
 */
@AllArgsConstructor
@Component
public class ConfigSyncApplier {

  private static final String RESOURCE = "config_sync";

  private final AgentProviderService providerService;
  private final AgentModelRepository modelRepository;
  private final AgentModelService modelService;
  private final SkillCatalogService skillCatalogService;
  private final McpServerService mcpServerService;
  private final AgentDefinitionRepository definitionRepository;
  private final AgentDefinitionService definitionService;
  private final EnvironmentRepository environmentRepository;
  private final EnvironmentService environmentService;
  private final SystemSettingsService systemSettingsService;
  private final PluginConfigurationCommitService pluginCommitService;
  private final PluginAgentDependencies pluginAgentDependencies;

  /** 事务内写入计划中的全部事实。 */
  @Transactional
  public void apply(ConfigSyncPlan plan) {
    applyProviders(plan.providers());
    applyModels(plan.models());
    for (ConfigSyncPlan.SkillImport skill : plan.skillPackages()) {
      skillCatalogService.importPackage(
          skill.packageName(),
          skill.description(),
          skill.repositoryUrl(),
          skill.branch(),
          skill.currentCommit(),
          skill.manifest(),
          skill.token());
    }
    for (ConfigSyncPlan.McpImport mcp : plan.mcpServers()) {
      mcpServerService.importServer(
          mcp.name(),
          mcp.url(),
          mcp.headers(),
          mcp.enabled(),
          mcp.timeoutMillis(),
          mcp.discoveredTools());
    }
    applyEnvironments(plan.environments());
    applyAgents(plan.agents());
    applyPlugins(plan);
    if (plan.settings() != null) {
      applySettings(plan.settings());
    }
  }

  private void applyProviders(List<ConfigSyncParser.ProviderSpec> providers) {
    for (ConfigSyncParser.ProviderSpec spec : providers) {
      // 导入是恢复文件业务事实：使用显式导入命令，凭据为 null 表示清空而非沿用旧值。
      providerService.importProvider(spec.name(), spec.properties());
    }
  }

  private void applyModels(List<ConfigSyncParser.ModelSpec> models) {
    for (ConfigSyncParser.ModelSpec spec : models) {
      AgentModel existing =
          modelRepository.getByProviderNameAndName(spec.providerName(), spec.name());
      if (existing == null) {
        AgentModelCreateDTO create = new AgentModelCreateDTO();
        copyModel(spec.properties(), create);
        create.setProviderName(spec.providerName());
        modelService.createModel(create);
      } else {
        AgentModelUpdateDTO update = new AgentModelUpdateDTO();
        copyModel(spec.properties(), update);
        update.setExpectedVersion(CatalogVersions.format(existing.getVersion()));
        modelService.updateModel(spec.providerName(), spec.name(), update);
      }
    }
  }

  private void applyEnvironments(List<ConfigSyncParser.EnvironmentSpec> environments) {
    for (ConfigSyncParser.EnvironmentSpec spec : environments) {
      Environment existing = environmentRepository.getByName(spec.name());
      if (existing == null) {
        environmentService.importEnvironment(
            spec.name(), spec.registrationToken(), spec.installConfig());
      } else {
        environmentService.updateImportedEnvironment(
            EnvironmentId.of(existing.getId()),
            spec.registrationToken(),
            spec.installConfig(),
            CatalogVersions.format(existing.getVersion()));
      }
    }
  }

  private void applyAgents(List<ConfigSyncParser.AgentSpec> agents) {
    // 阶段一：先以空 subagent allowlist 创建新 Agent，使同事务内的循环引用有目标行可引用。
    for (ConfigSyncParser.AgentSpec spec : agents) {
      if (definitionRepository.getByName(spec.name()) == null) {
        AgentDefinitionCreateDTO create = buildAgentCreate(spec);
        definitionService.createAgent(create);
      }
    }
    // 阶段二：把所有 Agent 更新为完整配置，此时全部被引用的 Agent 已存在。
    for (ConfigSyncParser.AgentSpec spec : agents) {
      AgentDefinition current = definitionRepository.getByName(spec.name());
      AgentDefinitionUpdateDTO update = buildAgentUpdate(spec);
      update.setExpectedVersion(CatalogVersions.format(current.getVersion()));
      definitionService.updateAgent(spec.name(), update);
    }
  }

  /**
   * 插件按计划顺序串行写入唯一提交入口：第一项比较计划 epoch，后续用同事务前一成功的新 epoch，各自仍比较计划期配置修订。
   *
   * <p>导入只承载普通值，秘密补丁恒为空（全部 KEEP），因此绝不复制或恢复任何凭据；普通配置变化可能合法推进认证代际，但秘密载荷保持原状。
   */
  private void applyPlugins(ConfigSyncPlan plan) {
    // 计划到写入之间删除的 Agent 不会改变插件目录 epoch，CAS 无法发现它；先按同一声明批量预锁，再开始任何插件写入。
    pluginAgentDependencies.requireDeclaredAgents(
        plan.plugins().stream()
            .map(
                plugin ->
                    new PluginAgentDependencies.Declaration(
                        plugin.pluginId(), plugin.expectedRevision(), plugin.valuesJson()))
            .toList());
    long epoch = plan.pluginCatalogEpoch();
    for (ConfigSyncPlan.PluginImport plugin : plan.plugins()) {
      PluginCatalogRecord committed =
          pluginCommitService.configure(
              plugin.pluginId(), epoch, plugin.expectedRevision(), plugin.valuesJson(), Map.of());
      epoch = committed.epoch();
    }
  }

  private void applySettings(ConfigSyncPlan.SettingsUpdate update) {
    SystemSettingsSectionsDTO sections = update.sections();
    SystemSettingsUpdateDTO dto = new SystemSettingsUpdateDTO();
    dto.setTool(sections.getTool());
    dto.setAiRuntime(sections.getAiRuntime());
    dto.setEnvironment(sections.getEnvironment());
    dto.setNetwork(sections.getNetwork());
    dto.setStorageMedia(sections.getStorageMedia());
    dto.setAdvanced(sections.getAdvanced());
    // 使用计划期快照版本做 CAS：计划到 apply 之间 settings 若被并发修改会正常冲突并随事务整体回滚。
    dto.setExpectedVersion(update.expectedVersion());
    systemSettingsService.update(dto);
  }

  private static void copyModel(
      AgentModelEditablePropertiesDTO source, AgentModelEditablePropertiesDTO target) {
    target.setName(source.getName());
    target.setModelId(source.getModelId());
    target.setDescription(source.getDescription());
    target.setConfig(source.getConfig());
  }

  private static AgentDefinitionCreateDTO buildAgentCreate(ConfigSyncParser.AgentSpec spec) {
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    copyAgent(spec.properties(), create);
    create.setName(spec.name());
    create.setConfig(withSubagents(spec.properties().getConfig(), List.of()));
    return create;
  }

  private static AgentDefinitionUpdateDTO buildAgentUpdate(ConfigSyncParser.AgentSpec spec) {
    AgentDefinitionUpdateDTO update = new AgentDefinitionUpdateDTO();
    copyAgent(spec.properties(), update);
    update.setConfig(spec.properties().getConfig());
    return update;
  }

  private static void copyAgent(
      AgentDefinitionEditablePropertiesDTO source, AgentDefinitionEditablePropertiesDTO target) {
    target.setDescription(source.getDescription());
    target.setSystemPrompt(source.getSystemPrompt());
    target.setModel(source.getModel());
    target.setVariant(source.getVariant());
    target.setConfig(source.getConfig());
  }

  private static AgentDefinitionConfigDTO withSubagents(
      AgentDefinitionConfigDTO config, List<String> subagents) {
    AgentDefinitionConfigDTO copy = new AgentDefinitionConfigDTO();
    copy.setTools(config.getTools());
    copy.setSkills(config.getSkills());
    copy.setSubagents(subagents);
    copy.setInheritParentEnvironment(config.getInheritParentEnvironment());
    return copy;
  }
}
