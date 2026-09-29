package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpServer;
import fun.fengwk.kkstudio.platform.catalog.mcp.service.model.McpTool;
import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogQueryService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.harness.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;

import java.util.List;
import java.util.Optional;

/** Agent 引用解析必须在写入前校验全局 Agent、Model 与 Skill 引用。 */
class AgentDefinitionReferenceResolverTest {

  @Test
  void rejectsMissingAgentAndModelReferences() {
    AgentDefinitionReferenceResolver resolver = resolver();

    assertThrows(AiResourceNotFoundException.class, () -> resolver.requireAgent("missing"));
    assertThrows(
        AiResourceNotFoundException.class, () -> resolver.requireModel("provider", "missing"));
  }

  @Test
  void locksUpdatedAgentAndSubagentsInCanonicalOrder() {
    AgentDefinitionRepository definitions = mock(AgentDefinitionRepository.class);
    AgentDefinition target = new AgentDefinition();
    AgentDefinition alpha = new AgentDefinition();
    AgentDefinition omega = new AgentDefinition();
    when(definitions.getByNameForUpdate("alpha")).thenReturn(alpha);
    when(definitions.getByNameForUpdate("middle")).thenReturn(target);
    when(definitions.getByNameForUpdate("omega")).thenReturn(omega);
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            definitions,
            mock(AgentModelRepository.class),
            mock(SkillCatalogQueryService.class),
            mock(McpServerRepository.class),
            mock(RuntimeToolCatalog.class));

    assertSame(
        target,
        resolver.requireAgentAndSubagentsForUpdate("middle", List.of("omega", "alpha", "middle")));

    InOrder ordered = inOrder(definitions);
    ordered.verify(definitions).getByNameForUpdate("alpha");
    ordered.verify(definitions).getByNameForUpdate("middle");
    ordered.verify(definitions).getByNameForUpdate("omega");
  }

  /** 测试意图：Skill 引用校验：查询 package 及其 manifest，任一 Package 或 Skill 缺失都 fail closed。 */
  @Test
  void validatesSkillReferencesAndRejectsMissingOnes() {
    SkillCatalogQueryService skillQueryService = mock(SkillCatalogQueryService.class);
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            mock(AgentDefinitionRepository.class),
            mock(AgentModelRepository.class),
            skillQueryService,
            mock(McpServerRepository.class),
            mock(RuntimeToolCatalog.class));

    SkillPackage pkg = new SkillPackage();
    pkg.setPackageName("tools");
    pkg.setSkills(
        List.of(
            new SkillManifestEntry("dev", "dev desc"), new SkillManifestEntry("ops", "ops desc")));
    when(skillQueryService.getPackage("tools")).thenReturn(pkg);
    when(skillQueryService.lockPackageForShare("tools")).thenReturn(pkg);

    assertDoesNotThrow(
        () ->
            resolver.requireReferencedLifecycles(
                List.of(skillRef("tools", "ops"), skillRef("tools", "dev")), List.of()));

    // 缺少 skill
    assertThrows(
        AiValidationException.class,
        () ->
            resolver.requireReferencedLifecycles(
                List.of(skillRef("tools", "missing"), skillRef("tools", "dev")), List.of()));

    // 缺少 package
    assertThrows(
        AiValidationException.class,
        () ->
            resolver.requireReferencedLifecycles(
                List.of(skillRef("missing-pkg", "dev")), List.of()));
  }

  @Test
  void emptySkillSelectionDoesNotQueryCatalog() {
    SkillCatalogQueryService skillQueryService = mock(SkillCatalogQueryService.class);
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            mock(AgentDefinitionRepository.class),
            mock(AgentModelRepository.class),
            skillQueryService,
            mock(McpServerRepository.class),
            mock(RuntimeToolCatalog.class));

    assertDoesNotThrow(() -> resolver.requireReferencedLifecycles(List.of(), List.of()));
    verify(skillQueryService, never()).getPackage(null);
  }

  /** 测试意图：Skill 与 MCP 共享锁按类别和 canonical name 排序，且发生在任何 Agent 行锁之前；内置工具不锁 server。 */
  @Test
  void locksSkillPackagesBeforeMcpServersInCanonicalOrder() {
    SkillCatalogQueryService skills = mock(SkillCatalogQueryService.class);
    McpServerRepository servers = mock(McpServerRepository.class);
    RuntimeToolCatalog tools = mock(RuntimeToolCatalog.class);
    AgentDefinitionRepository definitions = mock(AgentDefinitionRepository.class);
    SkillPackage zeta = packageWith("zeta", "review");
    SkillPackage alpha = packageWith("alpha", "dev");
    when(skills.getPackage("zeta")).thenReturn(zeta);
    when(skills.getPackage("alpha")).thenReturn(alpha);
    when(skills.lockPackageForShare("alpha")).thenReturn(alpha);
    when(skills.lockPackageForShare("zeta")).thenReturn(zeta);
    ToolContribution builtin = tool("builtin", "read");
    ToolContribution zuluTool = tool("platform.mcp", "search");
    ToolContribution alphaTool = tool("platform.mcp", "fetch");
    when(tools.findTool("builtin_read")).thenReturn(Optional.of(builtin));
    when(tools.findTool("mcp_zulu_search")).thenReturn(Optional.of(zuluTool));
    when(tools.findTool("mcp_alpha_fetch")).thenReturn(Optional.of(alphaTool));
    when(servers.getTool("mcp_zulu_search"))
        .thenReturn(Optional.of(mcpTool("mcp_zulu_search", "zulu")));
    when(servers.getTool("mcp_alpha_fetch"))
        .thenReturn(Optional.of(mcpTool("mcp_alpha_fetch", "alpha")));
    when(servers.lockToolForShare("mcp_zulu_search"))
        .thenReturn(Optional.of(mcpTool("mcp_zulu_search", "zulu")));
    when(servers.lockToolForShare("mcp_alpha_fetch"))
        .thenReturn(Optional.of(mcpTool("mcp_alpha_fetch", "alpha")));
    when(servers.lockForShare("alpha")).thenReturn(Optional.of(mcpServer("alpha")));
    when(servers.lockForShare("zulu")).thenReturn(Optional.of(mcpServer("zulu")));
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            definitions, mock(AgentModelRepository.class), skills, servers, tools);

    assertDoesNotThrow(
        () ->
            resolver.requireReferencedLifecycles(
                List.of(skillRef("zeta", "review"), skillRef("alpha", "dev")),
                List.of("builtin_read", "mcp_zulu_search", "mcp_alpha_fetch")));

    InOrder ordered = inOrder(skills, servers, definitions);
    ordered.verify(skills).lockPackageForShare("alpha");
    ordered.verify(skills).lockPackageForShare("zeta");
    ordered.verify(servers).lockForShare("alpha");
    ordered.verify(servers).lockForShare("zulu");
    ordered.verify(definitions, never()).getByNameForUpdate("agent");
    verify(servers, never()).lockForShare("builtin");
  }

  /** 测试意图：锁前解析出的 MCP server 在共享锁后消失或工具归属变化时，引用必须 fail closed。 */
  @Test
  void rejectsMcpToolRemovedBeforeShareLock() {
    McpServerRepository servers = mock(McpServerRepository.class);
    RuntimeToolCatalog tools = mock(RuntimeToolCatalog.class);
    ToolContribution contribution = tool("platform.mcp", "fetch");
    when(tools.findTool("mcp_alpha_fetch")).thenReturn(Optional.of(contribution));
    when(servers.getTool("mcp_alpha_fetch"))
        .thenReturn(Optional.of(mcpTool("mcp_alpha_fetch", "alpha")));
    when(servers.lockToolForShare("mcp_alpha_fetch")).thenReturn(Optional.empty());
    when(servers.lockForShare("alpha")).thenReturn(Optional.of(mcpServer("alpha")));
    AgentDefinitionReferenceResolver resolver =
        new AgentDefinitionReferenceResolver(
            mock(AgentDefinitionRepository.class),
            mock(AgentModelRepository.class),
            mock(SkillCatalogQueryService.class),
            servers,
            tools);

    assertThrows(
        AiValidationException.class,
        () -> resolver.requireReferencedLifecycles(List.of(), List.of("mcp_alpha_fetch")));
  }

  private static AgentDefinitionReferenceResolver resolver() {
    return new AgentDefinitionReferenceResolver(
        mock(AgentDefinitionRepository.class),
        mock(AgentModelRepository.class),
        mock(SkillCatalogQueryService.class),
        mock(McpServerRepository.class),
        mock(RuntimeToolCatalog.class));
  }

  private static SkillPackage packageWith(String packageName, String skillName) {
    SkillPackage skillPackage = new SkillPackage();
    skillPackage.setPackageName(packageName);
    skillPackage.setSkills(List.of(new SkillManifestEntry(skillName, skillName)));
    return skillPackage;
  }

  private static ToolContribution tool(String contributor, String localName) {
    ToolContribution contribution = mock(ToolContribution.class);
    when(contribution.id())
        .thenReturn(new ContributionId(new ContributorId(contributor), localName));
    return contribution;
  }

  private static McpTool mcpTool(String name, String serverName) {
    McpTool tool = new McpTool();
    tool.setName(name);
    tool.setServerName(serverName);
    return tool;
  }

  private static McpServer mcpServer(String name) {
    McpServer server = new McpServer();
    server.setName(name);
    return server;
  }

  private static SkillRefDTO skillRef(String packageName, String name) {
    SkillRefDTO ref = new SkillRefDTO();
    ref.setPackageName(packageName);
    ref.setName(name);
    return ref;
  }
}
