package fun.fengwk.kkstudio.platform.catalog.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.catalog.dto.McpServerCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.McpServerDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.McpServerDiscoverDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.McpServerSummaryDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.McpServerUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.mcp.test.FakeStreamableHttpMcpServer;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Platform MCP Server name-keyed 服务集成测试。
 *
 * <p>基于真实 PostgreSQL Testcontainer 与轻量 Streamable HTTP MCP Server Mock，验证：配置保存不发起网络 I/O、显式发现同步
 * 执行并整体物理替换工具行、CAS 并发冲突、发现失败不破坏既有快照、被 Agent 引用的工具删除 fail closed， 以及 name 即身份的 CRUD 契约。
 */
public class McpServerServiceTest extends PostgresSpringTestSupport {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private McpServerService mcpServerService;
  @Autowired private McpServerRepository repository;
  @Autowired private JdbcTemplate jdbc;

  private FakeStreamableHttpMcpServer fakeServer;

  @BeforeEach
  void setUpServer() throws IOException {
    fakeServer = new FakeStreamableHttpMcpServer();
  }

  @AfterEach
  void tearDownServer() {
    if (fakeServer != null) {
      fakeServer.close();
    }
  }

  @Test
  public void createsServerWithoutIoAndExposesSafeConfigOnlyViaExplicitEndpoint() {
    // 意图：创建只持久化显式 HTTP 配置且不发起任何网络 I/O；公开视图不含 URL/headers，显式 config 端点才返回完整配置
    fakeServer.setFailDiscovery(true);
    McpServerCreateDTO createDTO =
        createDto(
            "github_core",
            fakeServer.endpointUrl(),
            Map.of("Authorization", "Bearer secret_token_123"),
            10000L);

    McpServerDTO server = mcpServerService.createServer(createDTO);
    assertNotNull(server);
    assertEquals("github_core", server.getName());
    assertEquals("0", server.getVersion());
    assertEquals(10000L, server.getTimeoutMillis());
    assertEquals(McpDiscoveryStatus.UNVERIFIED, server.getDiscoveryStatus());
    assertEquals(0, server.getToolCount());
    assertEquals(0, fakeServer.toolCallCount());

    McpServerDTO detail = mcpServerService.getServer("github_core");
    assertEquals("github_core", detail.getName());
    assertEquals(fakeServer.endpointUrl(), detail.getUrl());
    assertTrue(detail.getHeaders().configured());
    assertEquals("0", detail.getVersion());

    // 列表项物理上只有安全元数据字段：不存在 url、headers、type、environmentId 或任何代理 id
    assertEquals(
        List.of(
            "createdAt",
            "discoveryStatus",
            "enabled",
            "name",
            "timeoutMillis",
            "toolCount",
            "updatedAt",
            "version"),
        declaredFieldNames());

    // name 不可重复
    assertThrows(DomainException.class, () -> mcpServerService.createServer(createDTO));
  }

  @Test
  public void rejectsInvalidNameAndUrlBeforeAnyPersistence() {
    // 意图：缺失请求体、name 正则、URL 绝对地址与 user-info 凭据约束在落库前确定性拒绝
    assertThrows(DomainException.class, () -> mcpServerService.createServer(null));
    assertThrows(DomainException.class, () -> mcpServerService.updateServer("github_core", null));
    assertThrows(
        DomainException.class,
        () ->
            mcpServerService.createServer(
                createDto("BadName", fakeServer.endpointUrl(), Map.of(), null)));
    assertThrows(
        DomainException.class,
        () -> mcpServerService.createServer(createDto("bad_url", "not-a-url", Map.of(), null)));
    assertThrows(
        DomainException.class,
        () ->
            mcpServerService.createServer(
                createDto("bad_cred", "http://user:pw@example.com/mcp", Map.of(), null)));
    assertThrows(DomainException.class, () -> mcpServerService.getServer("Missing"));

    assertEquals(0, countServers());
  }

  @Test
  public void discoversRemoteToolsSynchronouslyAndReplacesWholeResult() {
    // 意图：显式发现同步执行并把状态置为 AVAILABLE，成功的发现整体物理替换当前工具行，不存在 tombstone 或修订版本
    fakeServer.addTool("Search-Files.v1", "Search files by glob", "{\"type\":\"object\"}");
    fakeServer.addTool("read_file", "Read file content", "{\"type\":\"object\"}");
    mcpServerService.createServer(
        createDto(
            "remote_sync", fakeServer.endpointUrl(), Map.of("Authorization", "Bearer tok"), 5000L));

    McpServerDTO discovered = discover("remote_sync", "0");
    assertEquals(McpDiscoveryStatus.AVAILABLE, discovered.getDiscoveryStatus());
    assertEquals(2, discovered.getToolCount());
    assertEquals("0", discovered.getVersion());

    List<McpTool> tools = repository.listTools("remote_sync");
    assertEquals(2, tools.size());
    McpTool searchTool =
        tools.stream()
            .filter(t -> t.sourceName().equals("Search-Files.v1"))
            .findFirst()
            .orElseThrow();
    assertEquals("mcp_remote_sync_search_files_v1", searchTool.name());
    assertFalse(tools.stream().anyMatch(t -> t.name().contains("-")));

    // 远端删掉 read_file 并更新 schema：整行删除并重建，不存在历史 tombstone。
    fakeServer.clearTools();
    fakeServer.addTool(
        "Search-Files.v1",
        "Updated desc",
        "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}");

    McpServerDTO rediscovered = discover("remote_sync", "0");
    assertEquals(1, rediscovered.getToolCount());

    List<McpTool> after = repository.listTools("remote_sync");
    assertEquals(1, after.size());
    assertEquals("mcp_remote_sync_search_files_v1", after.get(0).name());
    assertEquals("Updated desc", after.get(0).description());
  }

  @Test
  public void discoveryFailureKeepsExistingSnapshotAndStatusUnchanged() {
    // 意图：发现网络失败时抛 DomainException，既有工具行与状态保持原样，不被清空
    fakeServer.addTool("stable", "Stable tool", "{\"type\":\"object\"}");
    mcpServerService.createServer(createDto("failing", fakeServer.endpointUrl(), Map.of(), 5000L));
    discover("failing", "0");
    assertEquals(1, repository.listTools("failing").size());

    fakeServer.setFailDiscovery(true);
    assertThrows(DomainException.class, () -> discover("failing", "0"));

    assertEquals(1, repository.listTools("failing").size());
    assertEquals(
        McpDiscoveryStatus.AVAILABLE, mcpServerService.getServer("failing").getDiscoveryStatus());
    // 失败绝不推进版本或改变状态
    assertEquals("0", mcpServerService.getServer("failing").getVersion());
  }

  @Test
  public void updatesServerWithCasAndResetsDiscoveryStatus() {
    // 意图：更新通过 CAS 推进 version 并把 discoveryStatus 重置为 UNVERIFIED；过期版本确定性冲突
    fakeServer.addTool("ping", "Ping", "{}");
    mcpServerService.createServer(
        createDto("update_test", fakeServer.endpointUrl(), Map.of(), 5000L));
    discover("update_test", "0");
    assertEquals(
        McpDiscoveryStatus.AVAILABLE,
        mcpServerService.getServer("update_test").getDiscoveryStatus());

    McpServerUpdateDTO update = new McpServerUpdateDTO();
    update.setExpectedVersion("0");
    update.setUrl(fakeServer.endpointUrl());
    update.setTimeoutMillis(8000L);

    McpServerDTO updated = mcpServerService.updateServer("update_test", update);
    assertEquals("1", updated.getVersion());
    assertEquals(8000L, updated.getTimeoutMillis());
    assertEquals(McpDiscoveryStatus.UNVERIFIED, updated.getDiscoveryStatus());

    // 同一 expectedVersion 再次提交必须冲突
    assertThrows(DomainException.class, () -> mcpServerService.updateServer("update_test", update));

    // discover 与 delete 也必须校验版本
    assertThrows(DomainException.class, () -> discover("update_test", "0"));
    assertThrows(DomainException.class, () -> mcpServerService.deleteServer("update_test", "0"));
  }

  @Test
  public void blocksDiscoveryThatWouldRemoveAgentReferencedTool() {
    // 意图：若被 Agent 引用的工具名将从目录中消失，发现必须 fail closed 并完整保留旧快照
    fakeServer.addTool("critical_tool", "Critical", "{}");
    fakeServer.addTool("removable_tool", "Removable", "{}");
    mcpServerService.createServer(
        createDto("inuse_test", fakeServer.endpointUrl(), Map.of(), 5000L));
    discover("inuse_test", "0");

    String referencedName =
        repository.listTools("inuse_test").stream()
            .filter(t -> t.sourceName().equals("critical_tool"))
            .findFirst()
            .orElseThrow()
            .name();
    insertFakeAgentReferencingTool(referencedName);

    // 远端未提供 critical_tool：引用保护必须阻止整次替换
    fakeServer.clearTools();
    fakeServer.addTool("removable_tool", "Removable", "{}");

    assertThrows(DomainException.class, () -> discover("inuse_test", "0"));

    List<McpTool> preserved = repository.listTools("inuse_test");
    assertEquals(2, preserved.size());
    assertTrue(preserved.stream().anyMatch(t -> t.name().equals(referencedName)));
    assertEquals(
        McpDiscoveryStatus.AVAILABLE,
        mcpServerService.getServer("inuse_test").getDiscoveryStatus());
  }

  @Test
  public void blocksServerDeleteWhenReferencedByAgent() {
    // 意图：被 Agent 引用的工具所属 Server 拒绝删除；引用清理后删除成功并级联物理删除工具行
    fakeServer.addTool("critical_tool", "Critical", "{}");
    mcpServerService.createServer(
        createDto("delete_guard", fakeServer.endpointUrl(), Map.of(), 5000L));
    discover("delete_guard", "0");

    String referencedName = repository.listTools("delete_guard").get(0).name();
    insertFakeAgentReferencingTool(referencedName);

    assertThrows(DomainException.class, () -> mcpServerService.deleteServer("delete_guard", "0"));
    assertEquals(1, repository.listTools("delete_guard").size());

    jdbc.update("delete from agent where name = 'test_agent_mcp'");
    mcpServerService.deleteServer("delete_guard", "0");

    assertEquals(0, countServers());
    assertEquals(0, repository.listTools("delete_guard").size());
    assertThrows(DomainException.class, () -> mcpServerService.getServer("delete_guard"));
  }

  @Test
  public void getAndPageServersByName() {
    // 意图：name 即查询身份；未知 name 返回 404 语义异常
    mcpServerService.createServer(
        createDto("page_test", fakeServer.endpointUrl(), Map.of(), 5000L));

    McpServerDTO retrieved = mcpServerService.getServer("page_test");
    assertEquals("page_test", retrieved.getName());
    assertEquals(McpDiscoveryStatus.UNVERIFIED, retrieved.getDiscoveryStatus());

    assertThrows(DomainException.class, () -> mcpServerService.getServer("no_such_server"));

    List<McpServerSummaryDTO> servers = mcpServerService.listServers();
    assertTrue(servers.stream().anyMatch(s -> "page_test".equals(s.getName())));
  }

  @Test
  public void headerSecretKeepsClearsAndReplacesAsAWhole() {
    // 意图：PUT 的 headers 秘密更新 KEEP 保留、CLEAR 清空、SET 整体替换；响应只暴露 configured 事实
    mcpServerService.createServer(
        createDto(
            "headers_secret",
            fakeServer.endpointUrl(),
            Map.of("Authorization", "Bearer a"),
            5000L));
    assertTrue(mcpServerService.getServer("headers_secret").getHeaders().configured());

    McpServerUpdateDTO keep = new McpServerUpdateDTO();
    keep.setExpectedVersion("0");
    keep.setUrl(fakeServer.endpointUrl());
    keep.setHeaders(SecretUpdate.keep());
    assertTrue(mcpServerService.updateServer("headers_secret", keep).getHeaders().configured());
    assertEquals(
        "Bearer a",
        repository.getByName("headers_secret").orElseThrow().headers().get("Authorization"));

    McpServerUpdateDTO clear = new McpServerUpdateDTO();
    clear.setExpectedVersion("1");
    clear.setUrl(fakeServer.endpointUrl());
    clear.setHeaders(SecretUpdate.clear());
    assertFalse(mcpServerService.updateServer("headers_secret", clear).getHeaders().configured());
    assertTrue(repository.getByName("headers_secret").orElseThrow().headers().isEmpty());

    McpServerUpdateDTO set = new McpServerUpdateDTO();
    set.setExpectedVersion("2");
    set.setUrl(fakeServer.endpointUrl());
    set.setHeaders(SecretUpdate.set("{\"X-Token\":\"b\"}"));
    assertTrue(mcpServerService.updateServer("headers_secret", set).getHeaders().configured());
    assertEquals(
        Map.of("X-Token", "b"), repository.getByName("headers_secret").orElseThrow().headers());

    // 非法 header 文本在落库前拒绝，且不推进版本
    McpServerUpdateDTO invalid = new McpServerUpdateDTO();
    invalid.setExpectedVersion("3");
    invalid.setUrl(fakeServer.endpointUrl());
    invalid.setHeaders(SecretUpdate.set("not-json"));
    assertThrows(
        DomainException.class, () -> mcpServerService.updateServer("headers_secret", invalid));
    assertEquals("3", mcpServerService.getServer("headers_secret").getVersion());
  }

  private static McpServerCreateDTO createDto(
      String name, String url, Map<String, String> headers, Long timeoutMillis) {
    McpServerCreateDTO createDTO = new McpServerCreateDTO();
    createDTO.setName(name);
    createDTO.setUrl(url);
    createDTO.setHeaders(headersSecret(headers));
    createDTO.setTimeoutMillis(timeoutMillis);
    return createDTO;
  }

  /** headers 以秘密更新的 SET 值提交：JSON 对象文本。 */
  private static SecretUpdate headersSecret(Map<String, String> headers) {
    if (headers.isEmpty()) {
      return SecretUpdate.keep();
    }
    return SecretUpdate.set(JSON.writeValueAsString(headers));
  }

  private McpServerDTO discover(String name, String expectedVersion) {
    McpServerDiscoverDTO discoverDTO = new McpServerDiscoverDTO();
    discoverDTO.setExpectedVersion(expectedVersion);
    return mcpServerService.discoverServer(name, discoverDTO);
  }

  /** 列表项 DTO 的声明字段集合，用于断言不存在隐藏身份或敏感字段。 */
  private static List<String> declaredFieldNames() {
    List<String> names = new ArrayList<>();
    for (Field field : McpServerSummaryDTO.class.getDeclaredFields()) {
      names.add(field.getName());
    }
    Collections.sort(names);
    return names;
  }

  private int countServers() {
    return jdbc.queryForObject("select count(*) from mcp_server", Integer.class);
  }

  private void insertFakeAgentReferencingTool(String modelName) {
    jdbc.update(
        "insert into provider (name, provider_type, config, connection_generation_id) values ('test_p_mcp', 'openai', '{}'::jsonb, '00000000-0000-0000-0000-000000000099'::uuid) on conflict do nothing");
    jdbc.update(
        "insert into model (name, kind, provider_name, model_id, config) "
            + "values ('m1', 'MODEL', 'test_p_mcp', 'm1', '{}'::jsonb) on conflict do nothing");

    jdbc.update(
        "insert into agent (name, execution, config) "
            + "values ('test_agent_mcp', "
            + "'{\"driver\":\"builtin\",\"settings\":{\"modelName\":\"m1\",\"variant\":null}}'::jsonb, "
            + "cast('{\"tools\": [\""
            + modelName
            + "\"]}' as jsonb))");
  }
}
