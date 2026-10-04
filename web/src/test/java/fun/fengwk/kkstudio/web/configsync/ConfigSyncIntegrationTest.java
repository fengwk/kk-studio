package fun.fengwk.kkstudio.web.configsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import fun.fengwk.kkstudio.platform.configsync.ConfigSyncApplier;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncParser;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncPlan;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncPlanner;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsVersionConflictException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCreateDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;
import fun.fengwk.kkstudio.web.controller.FakeStreamableHttpMcpServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 配置同步 HTTP + 真实 backend 集成测试。
 *
 * <p>真实隔离 PostgreSQL（{@link ConfigSyncTestSupport} 的进程级 disposable 容器）、真实 JGit 本地 {@code file://}
 * 仓库与 loopback Mock MCP，全程不访问外网或付费服务。覆盖七类往返、依赖闭包与循环 Agent、凭据/{@code ${VAR}} 原值、exact commit 恢复、同名
 * upsert、部分 settings、skip、事务回滚与 {@code no-store}。
 */
class ConfigSyncIntegrationTest extends ConfigSyncTestSupport {

  @Autowired private ConfigSyncParser parser;
  @Autowired private ConfigSyncPlanner planner;
  @Autowired private ConfigSyncApplier applier;

  private static final String DEV_SKILL_MD =
      """
      ---
      name: dev
      description: 开发者技能包含开发规范
      ---
      # dev content
      """;

  private static final String REVIEW_SKILL_MD =
      """
      ---
      name: review
      description: 评审技能
      ---
      # review content
      """;

  private FakeStreamableHttpMcpServer mcpServer;
  private HttpServer failingMcpServer;

  @AfterEach
  void closeServers() {
    if (mcpServer != null) {
      mcpServer.close();
      mcpServer = null;
    }
    if (failingMcpServer != null) {
      failingMcpServer.stop(0);
      failingMcpServer = null;
    }
  }

  // ---------------------------------------------------------------- HTTP contract

  /** HTTP 契约：inventory/export/import 成功与错误均 no-store，且 inventory 只含引用、绝不含凭据。 */
  @Test
  void httpEndpointsAreNoStoreAndInventoryNeverExposesSecrets() throws Exception {
    String suffix = unique();
    String provider = "no_store_provider_" + suffix;
    String envName = "no_store_env_" + suffix;
    JsonNode providerDto = createProvider(provider, "no store provider");
    JsonNode envDto = createEnvironment(envName);
    String token = envDto.path("registrationToken").asText();
    String credential = "sk-configsync-" + provider;

    // 直接写入的 credential 在公开 DTO 中不可读；inventory 更不得包含任何配置值。
    assertTrue(token.length() > 0);
    assertFalse(providerDto.has("credential"), "provider public DTO must not expose credential");

    MvcResult inventoryResult =
        mockMvc
            .perform(get("/api/settings/sync"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn();
    JsonNode items = data(inventoryResult).path("items");
    assertTrue(items.isArray() && items.size() > 0, "inventory must list refs");
    for (JsonNode item : items) {
      assertEquals(
          3, item.size(), "inventory item must only carry kind/name/dependencies: " + item);
      assertTrue(item.has("kind") && item.has("name") && item.has("dependencies"));
      for (JsonNode dependency : item.path("dependencies")) {
        assertEquals(2, dependency.size(), "dependency must only carry kind/name: " + dependency);
      }
    }
    String inventoryText = body(inventoryResult);
    assertFalse(inventoryText.contains(token), "inventory must not leak environment token");
    assertFalse(inventoryText.contains(credential), "inventory must not leak provider credential");
    assertFalse(
        inventoryText.contains("credential"), "inventory must not contain credential field");
    assertFalse(inventoryText.contains("registrationToken"));

    // export 成功也 no-store，且按契约导出凭据原值。
    MvcResult exportResult =
        mockMvc
            .perform(
                post("/api/settings/sync/export")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(exportRequest(providerRef(provider)))))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn();
    String yaml = data(exportResult).path("yaml").asText();
    assertTrue(yaml.contains(provider));
    assertTrue(yaml.contains(credential), "export must include the original credential");

    // import 成功 no-store。
    mockMvc
        .perform(
            post("/api/settings/sync/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("yaml", yaml))))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

    // import 错误同样 no-store，且不回显 YAML/凭据。
    String badYaml = "providers: 5\n";
    MvcResult errorResult =
        mockMvc
            .perform(
                post("/api/settings/sync/import")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("yaml", badYaml))))
            .andExpect(status().isBadRequest())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn();
    assertFalse(body(errorResult).contains(credential));

    // JSON 本身尚未解析成功时也不能缓存或回显请求中的凭据。
    MvcResult parseError =
        mockMvc
            .perform(
                post("/api/settings/sync/import")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"yaml\":\"" + credential))
            .andExpect(status().isBadRequest())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn();
    assertFalse(body(parseError).contains(credential));

    // 清理本用例新建资源，避免污染同库后续断言。
    deleteOk(
        "/api/harness/environments/" + envDto.path("id").asText(), envDto.path("version").asText());
    deleteOk(
        "/api/ai/catalog/providers/" + provider, findProvider(provider).path("version").asText());
  }

  // ---------------------------------------------------------------- seven kinds round trip

  /** 七类导出→删除→导入往返：可编辑事实、凭据、循环 Agent、Environment token、MCP 与 exact commit 都恢复。 */
  @Test
  void exportImportRoundTripRecreatesAllSevenKinds() throws Exception {
    String suffix = unique();
    String provider = "rt_provider_" + suffix;
    String model = "rt_model_" + suffix;
    String agentA = "rt_agent_a_" + suffix;
    String agentB = "rt_agent_b_" + suffix;
    String pkg = "rt_pkg_" + suffix;
    String mcpName = "rt_mcp_" + suffix;
    String envName = "rt_env_" + suffix;
    String mcpTool = "mcp_" + mcpName + "_echo";
    String protocolOptions = "{\"temperature\":0.5,\"nested\":{\"x\":1}}";

    GitFixture git = GitFixture.init("rt" + suffix, Map.of("dev/SKILL.md", DEV_SKILL_MD));
    String commit1 = git.firstCommit();

    mcpServer = new FakeStreamableHttpMcpServer();
    mcpServer.addTool("echo", "Echo text", "{}");

    JsonNode providerDto = createProvider(provider, "roundtrip provider");
    JsonNode modelDto = createModel(provider, model, "1.25", protocolOptions);
    JsonNode skillDto = createSkill(git, pkg, "roundtrip package");
    JsonNode mcpDto =
        createMcp(
            mcpName, mcpServer.endpointUrl(), Map.of("Authorization", "Bearer literal-secret"));
    JsonNode discoveredMcp = discover(mcpDto.path("name").asText(), "0");
    JsonNode envDto = createEnvironment(envName);
    String token = envDto.path("registrationToken").asText();

    // 先建 B 再无环 A，最后把 B 的 subagents 指向 A，形成互相引用的 Agent。
    createAgent(
        agentB, provider + "/" + model, List.of(mcpTool), List.of(skillRef(pkg, "dev")), List.of());
    JsonNode agentADto =
        createAgent(
            agentA,
            provider + "/" + model,
            List.of(mcpTool),
            List.of(skillRef(pkg, "dev")),
            List.of(agentB));
    JsonNode agentBPage = findAgent(agentB);
    AgentDefinitionUpdateDTO link = new AgentDefinitionUpdateDTO();
    link.setDescription("agent " + agentB);
    link.setSystemPrompt("prompt " + agentB);
    link.setModel(provider + "/" + model);
    link.setVariant("default");
    link.setConfig(agentConfig(List.of(mcpTool), List.of(skillRef(pkg, "dev")), List.of(agentA)));
    link.setExpectedVersion(agentBPage.path("version").asText());
    putData("/api/ai/catalog/agents/" + agentB, link);

    List<Map<String, String>> selection =
        List.of(
            ref("providers", provider),
            ref("models", provider + "/" + model),
            ref("agents", agentA),
            ref("agents", agentB),
            ref("skillPackages", pkg),
            ref("environments", envName),
            ref("mcpServers", mcpName),
            ref("settings", "settings"));
    String yaml =
        postData("/api/settings/sync/export", exportRequest(selection)).path("yaml").asText();
    // 凭据/registrationToken/MCP header 原值随导出。
    assertTrue(yaml.contains("sk-configsync-" + provider), "provider credential must be exported");
    assertTrue(yaml.contains("Bearer literal-secret"), "MCP credential headers must be exported");
    assertTrue(
        yaml.contains(token), "export must carry the environment registrationToken original value");

    // 删除全部已建资源，证明导入是真创建而不是 upsert 空操作。
    // Agent 互相引用形成循环：先把 A 的 subagents 清空，再依次删除 B、A。
    AgentDefinitionUpdateDTO unlinkA = new AgentDefinitionUpdateDTO();
    unlinkA.setDescription("agent " + agentA);
    unlinkA.setSystemPrompt("prompt " + agentA);
    unlinkA.setModel(provider + "/" + model);
    unlinkA.setVariant("default");
    unlinkA.setConfig(agentConfig(List.of(mcpTool), List.of(skillRef(pkg, "dev")), List.of()));
    unlinkA.setExpectedVersion(agentADto.path("version").asText());
    JsonNode unlinkedA = putData("/api/ai/catalog/agents/" + agentA, unlinkA);
    deleteOk("/api/ai/catalog/agents/" + agentB, findAgent(agentB).path("version").asText());
    deleteOk("/api/ai/catalog/agents/" + agentA, unlinkedA.path("version").asText());
    deleteOk("/api/ai/catalog/models/" + provider + "/" + model, modelDto.path("version").asText());
    deleteOk("/api/ai/catalog/skill-packages/" + pkg, skillDto.path("version").asText());
    deleteOk("/api/ai/mcp-servers/" + mcpName, discoveredMcp.path("version").asText());
    deleteOk(
        "/api/harness/environments/" + envDto.path("id").asText(), envDto.path("version").asText());
    deleteOk("/api/ai/catalog/providers/" + provider, providerDto.path("version").asText());
    assertNull(findAgent(agentA), "agent must be gone before import");

    JsonNode importResult = importYaml(yaml);
    List<String> imported = new ArrayList<>();
    importResult
        .path("imported")
        .forEach(
            node -> imported.add(node.path("kind").asText() + "/" + node.path("name").asText()));
    assertTrue(
        imported.contains("providers/" + provider), "provider must be recreated: " + imported);
    assertTrue(
        imported.contains("models/" + provider + "/" + model),
        "model must be recreated: " + imported);
    assertTrue(imported.contains("agents/" + agentA));
    assertTrue(imported.contains("agents/" + agentB));
    assertTrue(imported.contains("skillPackages/" + pkg));
    assertTrue(imported.contains("environments/" + envName));
    assertTrue(imported.contains("mcpServers/" + mcpName));
    assertTrue(imported.contains("settings/settings"));

    // 模型可编辑事实与 protocolOptionsJson 原样恢复。
    JsonNode reModel = findModel(provider, model);
    assertNotNull(reModel, "model must exist after import");
    assertEquals(
        protocolOptions,
        reModel.path("config").path("variants").get(0).path("protocolOptionsJson").asText());

    // Agent 引用闭包（模型/技能/MCP 工具/循环 subagent）恢复。
    JsonNode reA = findAgent(agentA);
    assertEquals(provider + "/" + model, reA.path("model").asText());
    assertEquals(List.of(mcpTool), listText(reA.path("config").path("tools")));
    assertEquals(List.of("dev"), reA.path("config").path("skills").findValuesAsText("name"));
    assertEquals(pkg, reA.path("config").path("skills").get(0).path("packageName").asText());
    assertEquals(List.of(agentB), listText(reA.path("config").path("subagents")));
    assertEquals(List.of(agentA), listText(findAgent(agentB).path("config").path("subagents")));

    // Environment 与 MCP 恢复：重名保持身份语义，但数据库重建后必须生成新 UUID；token 原值保留。
    JsonNode reEnv = findEnvironment(envName);
    assertNotNull(reEnv, "environment must be recreated after import");
    assertFalse(
        envDto.path("id").asText().equals(reEnv.path("id").asText()),
        "re-created environment must receive a new UUID");
    assertEquals(token, getEnvironmentToken(reEnv.path("id").asText()));
    JsonNode reMcp = getData("/api/ai/mcp-servers/" + mcpName);
    assertEquals("AVAILABLE", reMcp.path("discoveryStatus").asText());
    assertEquals(1, reMcp.path("toolCount").asInt());

    // Skill exact commit 恢复。
    JsonNode reSkill = getData("/api/ai/catalog/skill-packages/" + pkg);
    assertEquals(commit1, reSkill.path("currentCommit").asText());
    assertEquals(1, reSkill.path("skills").size());

    // 再次导出逐字段比对，而不是只证明同名条目存在或 imported 列表非空。
    String restoredYaml =
        postData("/api/settings/sync/export", exportRequest(selection)).path("yaml").asText();
    assertEquals(
        parseYaml(yaml), parseYaml(restoredYaml), "all seven kinds must preserve editable facts");
  }

  // ---------------------------------------------------------------- closure / no reverse

  /**
   * 只选一个 Agent 导出：闭包补齐 Model/Provider/Skill/MCP/循环 Subagent，但不反向带入无关 Agent/Provider/Environment。
   */
  @Test
  void exportSelectionExpandsClosureWithoutReverseExpansion() throws Exception {
    String suffix = unique();
    String provider = "cl_provider_" + suffix;
    String otherProvider = "cl_other_provider_" + suffix;
    String model = "cl_model_" + suffix;
    String agentA = "cl_agent_a_" + suffix;
    String agentB = "cl_agent_b_" + suffix;
    String unrelatedAgent = "cl_agent_unrelated_" + suffix;
    String pkg = "cl_pkg_" + suffix;
    String envName = "cl_env_" + suffix;
    String mcpName = "cl_mcp_" + suffix;
    String mcpTool = "mcp_" + mcpName + "_echo";

    GitFixture git = GitFixture.init("cl" + suffix, Map.of("dev/SKILL.md", DEV_SKILL_MD));
    mcpServer = new FakeStreamableHttpMcpServer();
    mcpServer.addTool("echo", "Echo text", "{}");

    createProvider(provider, "closure provider");
    createProvider(otherProvider, "unrelated provider");
    createModel(provider, model, "1.25", "{}");
    createSkill(git, pkg, "closure package");
    JsonNode mcpDto = createMcp(mcpName, mcpServer.endpointUrl(), Map.of());
    discover(mcpDto.path("name").asText(), "0");
    createEnvironment(envName);
    createAgent(
        agentB, provider + "/" + model, List.of(), List.of(skillRef(pkg, "dev")), List.of());
    createAgent(
        agentA,
        provider + "/" + model,
        List.of(mcpTool),
        List.of(skillRef(pkg, "dev")),
        List.of(agentB));
    createAgent(unrelatedAgent, provider + "/" + model, List.of(), List.of(), List.of());

    String yaml =
        postData("/api/settings/sync/export", exportRequest(agentRef(agentA)))
            .path("yaml")
            .asText();
    assertTrue(
        yaml.contains(agentA) && yaml.contains(agentB), "cyclic subagent closure must be included");
    assertTrue(yaml.contains(pkg) && yaml.contains(mcpName) && yaml.contains(provider));
    assertFalse(yaml.contains(unrelatedAgent), "unrelated agent must not be reverse-expanded");
    assertFalse(yaml.contains(otherProvider), "unrelated provider must not be reverse-expanded");
    assertFalse(yaml.contains(envName), "environment must not be auto-added for an agent");
    assertFalse(
        yaml.contains("registrationToken"), "environment must be absent from an agent-only export");

    // 闭包去重：agentA 只出现一次。
    int occurrences = yaml.split("- name: " + agentA + "\\b", -1).length - 1;
    assertEquals(1, occurrences, "cyclic closure must be de-duplicated");

    // 结构化断言：闭包确实没有 environments 顶层集合。
    Map<String, Object> document = parseYaml(yaml);
    assertFalse(
        document.containsKey("environments"), "agent-only closure must not export environments");
    assertTrue(
        document.containsKey("agents")
            && document.containsKey("models")
            && document.containsKey("providers"));
  }

  // ---------------------------------------------------------------- exact commit

  /** Branch HEAD 前进后导入导出快照：必须恢复到 YAML 记录的 exact commit，而不是最新 HEAD。 */
  @Test
  void skillImportRestoresExactCommitNotLatestHead() throws Exception {
    String suffix = unique();
    String pkg = "ec_pkg_" + suffix;
    GitFixture git = GitFixture.init("ec" + suffix, Map.of("dev/SKILL.md", DEV_SKILL_MD));
    String commit1 = git.firstCommit();
    JsonNode skillDto = createSkill(git, pkg, "exact commit package");
    assertEquals(commit1, skillDto.path("currentCommit").asText());

    String yaml =
        postData("/api/settings/sync/export", exportRequest(ref("skillPackages", pkg)))
            .path("yaml")
            .asText();
    assertTrue(yaml.contains(commit1), "export must record the published exact commit");

    // HEAD 前进到包含第二个 skill 的 commit。
    String commit2 =
        git.commit(Map.of("dev/SKILL.md", DEV_SKILL_MD, "review/SKILL.md", REVIEW_SKILL_MD));
    assertFalse(commit2.equals(commit1));

    deleteOk("/api/ai/catalog/skill-packages/" + pkg, skillDto.path("version").asText());
    JsonNode importResult = importYaml(yaml);
    assertTrue(
        importResult.path("imported").findValuesAsText("name").contains(pkg),
        "skill package must be imported");

    JsonNode reSkill = getData("/api/ai/catalog/skill-packages/" + pkg);
    assertEquals(
        commit1, reSkill.path("currentCommit").asText(), "must restore exact commit, not HEAD");
    assertEquals(1, reSkill.path("skills").size(), "manifest must come from the exact commit");
    assertEquals("dev", reSkill.path("skills").get(0).path("name").asText());
  }

  // ---------------------------------------------------------------- same-name upsert

  /** 同名导入是 upsert：更新同名配置，且不删除文件中未出现的既有配置。 */
  @Test
  void sameNameImportUpsertsAndKeepsUnlistedConfiguration() throws Exception {
    String suffix = unique();
    String name = "up_provider_" + suffix;
    String survivor = "up_survivor_" + suffix;
    JsonNode providerDto = createProvider(name, "original description");
    JsonNode survivorDto = createProvider(survivor, "must survive");
    String yaml =
        postData("/api/settings/sync/export", exportRequest(providerRef(name)))
            .path("yaml")
            .asText();

    AgentProviderCreateDTO update = new AgentProviderCreateDTO();
    update.setName(name);
    update.setDescription("changed later");
    update.setProviderType("openai");
    update.setBaseUrl("https://example.com/v2");
    update.setCredential("sk-updated");
    update.setModelCallTimeoutMillis(123456L);
    update.setModelCallIdleTimeoutMillis(60000L);
    // provider 更新走 PUT，expectedVersion 来自创建响应。
    updateProviderPut(name, providerDto.path("version").asText(), update);
    assertEquals("changed later", findProvider(name).path("description").asText());
    assertTrue(
        postData("/api/settings/sync/export", exportRequest(providerRef(name)))
            .path("yaml")
            .asText()
            .contains("credential: sk-updated"),
        "the ordinary update fixture must actually change the credential");

    importYaml(yaml);
    assertEquals(
        "original description",
        findProvider(name).path("description").asText(),
        "exported value must win");
    assertEquals(
        parseYaml(yaml),
        parseYaml(
            postData("/api/settings/sync/export", exportRequest(providerRef(name)))
                .path("yaml")
                .asText()),
        "same-name import must restore all file facts, including the original credential");
    assertNotNull(findProvider(survivor), "configuration absent from the file must be preserved");
    assertNotNull(survivorDto);
  }

  // ---------------------------------------------------------------- settings

  /** 普通编辑保留缺失凭据，导入则清空或恢复文件中的凭据事实。 */
  @Test
  void sameNameProviderWithoutCredentialClearsTheTargetCredential() throws Exception {
    String name = "clear_credential_" + unique();
    JsonNode created = createProvider(name, "configured target");
    assertTrue(created.path("configured").asBoolean());
    String originalYaml =
        postData("/api/settings/sync/export", exportRequest(providerRef(name)))
            .path("yaml")
            .asText();
    putData(
        "/api/ai/catalog/providers/" + name,
        Map.of(
            "providerType", "openai",
            "baseUrl", "https://example.com/v1",
            "expectedVersion", created.path("version").asText()));
    assertTrue(findProvider(name).path("configured").asBoolean());
    assertTrue(
        postData("/api/settings/sync/export", exportRequest(providerRef(name)))
            .path("yaml")
            .asText()
            .contains("sk-configsync-" + name),
        "ordinary editing without a credential must preserve the existing value");

    importYaml("providers:\n  - name: " + name + "\n    providerType: openai\n");

    assertFalse(findProvider(name).path("configured").asBoolean());
    String exported =
        postData("/api/settings/sync/export", exportRequest(providerRef(name)))
            .path("yaml")
            .asText();
    assertFalse(
        exported.contains("credential:"),
        "an unconfigured provider must not retain the target credential");
    importYaml(originalYaml);
    assertTrue(findProvider(name).path("configured").asBoolean());
    assertEquals(
        parseYaml(originalYaml),
        parseYaml(
            postData("/api/settings/sync/export", exportRequest(providerRef(name)))
                .path("yaml")
                .asText()),
        "importing the original file must restore the credential and other editable facts");
  }

  /** 准备期间出现并发设置写入时，旧计划必须 CAS 冲突，之前写入的 Provider 同事务回滚。 */
  @Test
  void concurrentSettingsChangeRejectsThePreparedAggregateAndRollsBackEarlierWrites()
      throws Exception {
    String name = "settings_cas_" + unique();
    JsonNode before = getData("/api/settings");
    ConfigSyncPlan plan =
        planner.plan(
            parser.parse(
                "providers:\n  - name: "
                    + name
                    + "\n    providerType: openai\nsettings:\n  aiRuntime:\n    retryBaseDelayMillis: 9999\n"));
    boolean concurrentYolo = !before.path("tool").path("defaultYolo").asBoolean();
    importYaml("settings:\n  tool:\n    defaultYolo: " + concurrentYolo + "\n");
    JsonNode concurrent = getData("/api/settings");

    assertThrows(SystemSettingsVersionConflictException.class, () -> applier.apply(plan));

    assertNull(findProvider(name), "writes preceding the settings CAS must be rolled back");
    assertEquals(
        concurrent, getData("/api/settings"), "concurrent values and version must remain intact");
  }

  /** 部分 settings 只合入给出字段、不重置省略字段；未知 section 必须只报告该 section 而不整体丢弃。 */
  @Test
  void partialSettingsMergeAndUnknownSectionIsReported() throws Exception {
    JsonNode before = getData("/api/settings");
    String originalDelay = before.path("aiRuntime").path("retryBaseDelayMillis").asText();
    int originalRetries = before.path("aiRuntime").path("retryMaxRetries").asInt();

    String partial = "settings:\n  aiRuntime:\n    retryBaseDelayMillis: 9999\n";
    JsonNode imported = importYaml(partial);
    assertTrue(
        imported.path("imported").findValuesAsText("name").contains("settings"),
        "partial settings must be imported");
    JsonNode after = getData("/api/settings");
    assertEquals("9999", after.path("aiRuntime").path("retryBaseDelayMillis").asText());
    assertEquals(
        originalRetries,
        after.path("aiRuntime").path("retryMaxRetries").asInt(),
        "omitted field must not reset");

    // 未知 section 与可支持 section 并存：可支持字段仍应合入，未知 section 只作为 skip 报告。
    String mixed =
        "settings:\n  aiRuntime:\n    retryBaseDelayMillis: 8888\n  unknownSection:\n    x: 1\n";
    JsonNode mixedResult = importYaml(mixed);
    assertTrue(
        mixedResult.path("skipped").findValuesAsText("kind").contains("settings"),
        "unknown settings section must be reported");
    JsonNode mixedAfter = getData("/api/settings");
    assertEquals(
        "8888",
        mixedAfter.path("aiRuntime").path("retryBaseDelayMillis").asText(),
        "supported settings section must still merge when another section is unknown");

    // 空 settings 对象是合法的 no-op 合并。
    JsonNode emptyResult = importYaml("settings: {}\n");
    assertEquals(0, emptyResult.path("imported").size(), "no-op does not report an applied item");
    assertEquals(0, emptyResult.path("skipped").size());
    assertEquals(
        mixedAfter, getData("/api/settings"), "no-op must not alter values or CAS version");

    // 恢复原值，避免影响后续断言（本类每个测试重建 schema，此处仍显式恢复）。
    importYaml("settings:\n  aiRuntime:\n    retryBaseDelayMillis: " + originalDelay + "\n");
  }

  // ---------------------------------------------------------------- malformed / errors

  /** 重复键、递归、错误类型与非法有效值必须明确报错，且错误输出绝不包含凭据或 YAML 片段。 */
  @Test
  void malformedAndWrongTypeYamlErrorsDoNotLeakSecrets() throws Exception {
    String secret = "super-secret-credential-xyz";
    String duplicateKeys =
        "providers:\n"
            + "  - name: leak_provider\n"
            + "    providerType: openai\n"
            + "    credential: "
            + secret
            + "\n"
            + "providers: []\n";
    assertBadImportDoesNotLeak(duplicateKeys, secret);

    assertBadImportDoesNotLeak("root: &anchor\n  child: *anchor\n", secret);
    assertBadImportDoesNotLeak("providers: 5\n", secret);
    assertBadImportDoesNotLeak("providers:\n  - name: 123\n    providerType: openai\n", secret);
    assertBadImportDoesNotLeak("settings:\n  aiRuntime:\n    retryBaseDelayMillis: ''\n", secret);
    assertBadImportDoesNotLeak(
        "settings:\n  integrations:\n    openCliHub:\n      enabled: ''\n", secret);

    // 未知顶层类别是条目级 skip，而不是整份文档错误。
    JsonNode unknownCategory = importYaml("unknownThing: []\n");
    assertTrue(
        unknownCategory.path("skipped").findValuesAsText("kind").contains("unknownThing"),
        "unknown top-level category must be reported as skip");
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void malformedJsonUsesSanitizedResponseWithoutLoggingInput(CapturedOutput output)
      throws Exception {
    // 原生 Jackson 的错误消息包含非法 token；同步入口不得回显或记录这个输入。
    String secret = "syntheticConfigSyncCredentialCanary";
    MvcResult result =
        mockMvc
            .perform(
                post("/api/settings/sync/import")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"yaml\": " + secret + "}"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn();
    assertFalse(output.getAll().contains(secret), "invalid input must not enter request logs");
    assertEquals(
        "Failed to read request",
        objectMapper.readTree(body(result)).path("errors").path("detail").asText());
    assertFalse(body(result).contains(secret));
  }

  // ---------------------------------------------------------------- rollback

  /** 依赖写入后晚到的 DB token 冲突必须整体回滚，且系统设置快照保持不变。 */
  @Test
  void environmentTokenCollisionRollsBackWholeImportAndLeavesSettings() throws Exception {
    JsonNode envDto =
        createEnvironment("rb_env_existing_" + UUID.randomUUID().toString().substring(0, 8));
    String collidingToken = envDto.path("registrationToken").asText();
    String originalDelay =
        getData("/api/settings").path("aiRuntime").path("retryBaseDelayMillis").asText();

    String yaml =
        "providers:\n"
            + "  - name: rb_provider\n"
            + "    providerType: openai\n"
            + "    credential: rb-secret\n"
            + "    baseUrl: https://example.com/v1\n"
            + "environments:\n"
            + "  - name: rb_env_new\n"
            + "    registrationToken: "
            + collidingToken
            + "\n"
            + "settings:\n"
            + "  aiRuntime:\n"
            + "    retryBaseDelayMillis: 7777\n";

    mockMvc
        .perform(
            post("/api/settings/sync/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("yaml", yaml))))
        .andExpect(status().is4xxClientError())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

    assertNull(findProvider("rb_provider"), "earlier provider write must be rolled back");
    assertEquals(
        originalDelay,
        getData("/api/settings").path("aiRuntime").path("retryBaseDelayMillis").asText(),
        "system settings snapshot must not change when import rolls back");
  }

  // ---------------------------------------------------------------- skip semantics

  /** Agent 引用未知工具时整条跳过，而不是删除其工具后再写入；未被引用的其它 Agent 不受影响。 */
  @Test
  void unsupportedAgentToolSkipsEntryWithoutMutatingOthers() throws Exception {
    String suffix = unique();
    String provider = "sk_provider_" + suffix;
    String model = "sk_model_" + suffix;
    String good = "sk_agent_good_" + suffix;
    String bad = "sk_agent_bad_" + suffix;
    createProvider(provider, "skip provider");
    createModel(provider, model, "1.25", "{}");
    createAgent(good, provider + "/" + model, List.of(), List.of(), List.of());

    String yaml =
        "agents:\n"
            + "  - name: "
            + bad
            + "\n"
            + "    model: "
            + provider
            + "/"
            + model
            + "\n"
            + "    config:\n"
            + "      tools: [totally_unknown_tool]\n"
            + "      skills: []\n"
            + "      subagents: []\n";
    JsonNode result = importYaml(yaml);
    assertTrue(
        result.path("skipped").findValuesAsText("name").contains(bad),
        "agent with unsupported tool must be skipped: " + result);
    assertNull(findAgent(bad), "skipped agent must not be created");
    assertNotNull(findAgent(good), "a skipped entry must not mutate other agents");
  }

  /** 不支持的 Provider 协议必须作为条目级 skip 报告，而不是让整份导入硬失败。 */
  @Test
  void unsupportedProviderProtocolIsReportedAsSkip() throws Exception {
    String yaml =
        "providers:\n"
            + "  - name: unsupported_provider\n"
            + "    providerType: not_a_real_protocol\n"
            + "    credential: c\n";
    JsonNode result = importYaml(yaml);
    assertTrue(
        result.path("skipped").findValuesAsText("name").contains("unsupported_provider"),
        "unsupported provider protocol must be reported as skip, got: " + result);
  }

  /** MCP discovery 外部失败必须明确报告（skip），不能吞成功。 */
  @Test
  void mcpDiscoveryFailureIsReportedAsSkip() throws Exception {
    failingMcpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    failingMcpServer.createContext(
        "/mcp",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });
    failingMcpServer.start();
    String url = "http://127.0.0.1:" + failingMcpServer.getAddress().getPort() + "/mcp";
    String yaml =
        "mcpServers:\n"
            + "  - name: broken_mcp\n"
            + "    url: "
            + url
            + "\n"
            + "    enabled: true\n";
    JsonNode result = importYaml(yaml);
    assertTrue(
        result.path("skipped").findValuesAsText("name").contains("broken_mcp"),
        "MCP discovery failure must be reported as skip, got: " + result);
  }

  /** BigDecimal 定价必须保持原精度，不能经浮点丢位。 */
  @Test
  void modelPricingPrecisionSurvivesRoundTrip() throws Exception {
    String suffix = unique();
    String provider = "pr_provider_" + suffix;
    String model = "pr_model_" + suffix;
    String price = "1.234567890123456789";
    createProvider(provider, "precision provider");
    JsonNode modelDto = createModel(provider, model, price, "{}");
    String yaml =
        postData("/api/settings/sync/export", exportRequest(ref("models", provider + "/" + model)))
            .path("yaml")
            .asText();
    deleteOk("/api/ai/catalog/models/" + provider + "/" + model, modelDto.path("version").asText());
    importYaml(yaml);
    JsonNode reModel = findModel(provider, model);
    assertEquals(
        price,
        reModel.path("config").path("pricing").path("inputPerMillionTokens").asText(),
        "pricing BigDecimal must keep full precision");
  }

  // ---------------------------------------------------------------- helpers

  private void assertBadImportDoesNotLeak(String yaml, String secret) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/settings/sync/import")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("yaml", yaml))))
            .andExpect(status().isBadRequest())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andReturn();
    assertFalse(
        body(result).contains(secret), "error output must not leak secret: " + body(result));
  }

  private JsonNode createProvider(String name, String description) throws Exception {
    // credential 为 WRITE_ONLY；测试请求不能通过响应侧 DTO 序列化，否则会悄悄丢掉待验证的凭据。
    return postData(
        "/api/ai/catalog/providers",
        Map.of(
            "name",
            name,
            "description",
            description,
            "providerType",
            "openai",
            "baseUrl",
            "https://example.com/v1",
            "credential",
            "sk-configsync-" + name,
            "modelCallTimeoutMillis",
            120000L,
            "modelCallIdleTimeoutMillis",
            30000L));
  }

  private void updateProviderPut(String name, String expectedVersion, AgentProviderCreateDTO source)
      throws Exception {
    // WRITE_ONLY 凭据不会被 DTO 序列化，显式构造请求以命中真实的凭据更新。
    Map<String, Object> update = new LinkedHashMap<>();
    update.put("description", source.getDescription());
    update.put("providerType", source.getProviderType());
    update.put("baseUrl", source.getBaseUrl());
    update.put("credential", source.getCredential());
    update.put("modelCallTimeoutMillis", source.getModelCallTimeoutMillis());
    update.put("modelCallIdleTimeoutMillis", source.getModelCallIdleTimeoutMillis());
    update.put("expectedVersion", expectedVersion);
    putData("/api/ai/catalog/providers/" + name, update);
  }

  private JsonNode createModel(
      String providerName, String name, String inputPrice, String protocolOptionsJson)
      throws Exception {
    AgentModelCreateDTO dto = new AgentModelCreateDTO();
    dto.setProviderName(providerName);
    dto.setName(name);
    dto.setModelId("wire-" + name);
    dto.setDescription("model " + name);
    dto.setConfig(modelConfig(protocolOptionsJson, inputPrice));
    return postData("/api/ai/catalog/models", dto);
  }

  private JsonNode createAgent(
      String name,
      String model,
      List<String> tools,
      List<SkillRefDTO> skills,
      List<String> subagents)
      throws Exception {
    AgentDefinitionCreateDTO dto = new AgentDefinitionCreateDTO();
    dto.setName(name);
    dto.setDescription("agent " + name);
    dto.setSystemPrompt("prompt " + name);
    dto.setModel(model);
    dto.setVariant("default");
    dto.setConfig(agentConfig(tools, skills, subagents));
    return postData("/api/ai/catalog/agents", dto);
  }

  private JsonNode createSkill(GitFixture git, String pkg, String description) throws Exception {
    SkillPackageCreateDTO dto = new SkillPackageCreateDTO();
    dto.setPackageName(pkg);
    dto.setDescription(description);
    dto.setRepositoryUrl(git.url());
    dto.setBranch("main");
    return postData("/api/ai/catalog/skill-packages", dto);
  }

  private JsonNode createMcp(String name, String url, Map<String, String> headers)
      throws Exception {
    McpServerCreateDTO dto = new McpServerCreateDTO();
    dto.setName(name);
    dto.setUrl(url);
    dto.setHeaders(headers);
    dto.setEnabled(true);
    dto.setTimeoutMillis(10000L);
    return postData("/api/ai/mcp-servers", dto);
  }

  private JsonNode discover(String name, String expectedVersion) throws Exception {
    return postData(
        "/api/ai/mcp-servers/" + name + "/discover?expectedVersion=" + expectedVersion, null);
  }

  private JsonNode createEnvironment(String name) throws Exception {
    EnvironmentCreateDTO dto = new EnvironmentCreateDTO();
    dto.setName(name);
    return postData("/api/harness/environments", dto);
  }

  private JsonNode importYaml(String yaml) throws Exception {
    return postData("/api/settings/sync/import", Map.of("yaml", yaml));
  }

  private String getEnvironmentToken(String id) throws Exception {
    return getData("/api/harness/environments/" + id + "/token").path("registrationToken").asText();
  }

  private void deleteOk(String path, String expectedVersion) throws Exception {
    perform(delete(path).param("expectedVersion", expectedVersion), 204);
  }

  private JsonNode findProvider(String name) throws Exception {
    return findByName(
        getData("/api/ai/catalog/providers?pageNumber=1&pageSize=100").path("results"), name);
  }

  private JsonNode findModel(String providerName, String name) throws Exception {
    for (JsonNode node :
        getData("/api/ai/catalog/models?pageNumber=1&pageSize=100").path("results")) {
      if (name.equals(node.path("name").asText())
          && providerName.equals(node.path("providerName").asText())) {
        return node;
      }
    }
    return null;
  }

  private JsonNode findAgent(String name) throws Exception {
    return findByName(
        getData("/api/ai/catalog/agents?pageNumber=1&pageSize=100").path("results"), name);
  }

  private JsonNode findEnvironment(String name) throws Exception {
    return findByName(getData("/api/harness/environments"), name);
  }

  private static JsonNode findByName(JsonNode results, String name) {
    for (JsonNode node : results) {
      if (name.equals(node.path("name").asText())) {
        return node;
      }
    }
    return null;
  }

  private static Map<String, Object> exportRequest(List<Map<String, String>> items) {
    return Map.of("items", items);
  }

  private static Map<String, Object> exportRequest(Map<String, String> item) {
    return Map.of("items", List.of(item));
  }

  private static Map<String, String> ref(String kind, String name) {
    return Map.of("kind", kind, "name", name);
  }

  private static Map<String, String> providerRef(String name) {
    return ref("providers", name);
  }

  private static Map<String, String> agentRef(String name) {
    return ref("agents", name);
  }

  private static String unique() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
