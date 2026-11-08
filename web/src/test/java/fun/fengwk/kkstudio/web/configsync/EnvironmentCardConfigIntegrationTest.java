package fun.fengwk.kkstudio.web.configsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;

import fun.fengwk.kkstudio.platform.environment.RegistrationTokens;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 完整 Environment 卡片的 HTTP 与配置导入导出契约：note + daemonConfig、CAS 与旧字段拒绝。 */
class EnvironmentCardConfigIntegrationTest extends ConfigSyncTestSupport {
  @Autowired private JdbcTemplate jdbc;

  /** 创建、读取、列表、陈旧 CAS、轮换 token 与删除都以同一张完整卡片为准。 */
  @Test
  void createReadListStaleCasRotateAndDelete() throws Exception {
    JsonNode created = create("card-env", "  host note  ");
    String id = created.path("id").asText();
    String path = "/api/platform/environments/" + id;
    String token = created.path("registrationToken").asText();
    assertEquals(0, created.path("version").asInt());
    assertEquals("host note", created.path("note").asText());
    assertEquals("https://studio.example.com", created.at("/daemonConfig/studioUrl").asText());
    assertEquals("/bin/bash", created.at("/daemonConfig/bashExecutable").asText());
    assertEquals("http://127.0.0.1:7890", created.at("/daemonConfig/proxyUrl").asText());
    assertEquals("jdtls", created.at("/daemonConfig/lsp/servers/jdtls/command/0").asText());
    assertFalse(created.has("installConfig"));
    assertFalse(token.isBlank());
    assertEquals(RegistrationTokens.hash(token), storedTokenHash(id));
    assertEquals("host note", storedNote(id));
    assertEquals(0, storedVersion(id));
    JsonNode stored = storedConfig(id);
    assertEquals("https://studio.example.com", stored.path("studioUrl").asText());
    assertEquals("/bin/bash", stored.path("bashExecutable").asText());
    assertEquals("http://127.0.0.1:7890", stored.path("proxyUrl").asText());

    JsonNode read = getData(path);
    assertEquals("host note", read.path("note").asText());
    assertEquals(created.path("daemonConfig"), read.path("daemonConfig"));
    assertFalse(read.has("registrationToken"));

    JsonNode listed = getData("/api/platform/environments").get(0);
    assertEquals(read.path("note"), listed.path("note"));
    assertEquals(read.path("daemonConfig"), listed.path("daemonConfig"));
    assertFalse(listed.has("registrationToken"));

    JsonNode updated = putData(path, update("0", "updated note", changedConfig()));
    assertEquals(1, updated.path("version").asInt());
    assertEquals("updated note", updated.path("note").asText());
    assertEquals("https://changed.example.com", updated.at("/daemonConfig/studioUrl").asText());
    assertEquals("/usr/bin/bash", updated.at("/daemonConfig/bashExecutable").asText());
    assertEquals("http://127.0.0.1:7891", updated.at("/daemonConfig/proxyUrl").asText());
    assertFalse(updated.path("daemonConfig").has("lsp"));
    assertEquals("updated note", storedNote(id));
    assertEquals(1, storedVersion(id));
    assertEquals("https://changed.example.com", storedConfig(id).path("studioUrl").asText());

    JsonNode beforeStale = getData(path);
    perform(
        put(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(update("0", "stale note", config()))),
        409);
    JsonNode afterStale = getData(path);
    assertEquals(beforeStale.path("version"), afterStale.path("version"));
    assertEquals(beforeStale.path("note"), afterStale.path("note"));
    assertEquals(beforeStale.path("daemonConfig"), afterStale.path("daemonConfig"));
    assertEquals(beforeStale.path("updatedAt"), afterStale.path("updatedAt"));

    JsonNode rotated = postData(path + "/rotate-token", Map.of("expectedVersion", "1"));
    assertEquals(2, rotated.path("version").asInt());
    assertEquals(updated.path("note"), rotated.path("note"));
    assertEquals(updated.path("daemonConfig"), rotated.path("daemonConfig"));
    String rotatedToken = rotated.path("registrationToken").asText();
    assertFalse(token.equals(rotatedToken));
    assertEquals(RegistrationTokens.hash(rotatedToken), storedTokenHash(id));

    perform(delete(path).param("expectedVersion", "2"), 204);
    assertEquals(0, countEnvironments());
  }

  /** 旧字段、缺失必填、类型错误与非法 note 都必须在 HTTP 边界零写入。 */
  @Test
  void invalidBodiesAndRemovedHttpFieldsFailWithoutMutation() throws Exception {
    JsonNode created = create("invalid-env", "initial note");
    String id = created.path("id").asText();
    String path = "/api/platform/environments/" + id;
    long version = storedVersion(id);
    String note = storedNote(id);
    String config = storedConfigJson(id);

    for (String json :
        List.of(
            "{\"expectedVersion\":\"0\"}",
            "{\"expectedVersion\":\"0\",\"daemonConfig\":null}",
            "{\"expectedVersion\":\"0\",\"daemonConfig\":{}}",
            "{\"expectedVersion\":\"0\",\"daemonConfig\":{\"studioUrl\":42}}",
            "{\"expectedVersion\":\"0\",\"daemonConfig\":{\"studioUrl\":\"https://studio.example.com\",\"lsp\":{\"servers\":[]}}}",
            "{\"expectedVersion\":\"0\",\"daemonConfig\":{\"studioUrl\":\"https://studio.example.com\",\"lsp\":{\"servers\":{}}}}",
            "{\"expectedVersion\":\"0\",\"installConfig\":{\"operatingSystem\":\"linux\"}}",
            "{\"expectedVersion\":\"0\",\"operatingSystem\":\"linux\"}",
            "{\"expectedVersion\":\" 0\",\"daemonConfig\":{\"studioUrl\":\"https://studio.example.com\"}}",
            "{\"daemonConfig\":{\"studioUrl\":\"https://studio.example.com\"}}",
            "{\"expectedVersion\":\"0\",\"note\":\"bad\\u0000note\",\"daemonConfig\":{\"studioUrl\":\"https://studio.example.com\"}}")) {
      perform(put(path).contentType(MediaType.APPLICATION_JSON).content(json), 400);
    }

    for (String json :
        List.of(
            "{\"name\":\"old-create\"}",
            "{\"name\":\"old-create\",\"installConfig\":{\"operatingSystem\":\"linux\"}}",
            "{\"name\":\"old-create\",\"operatingSystem\":\"linux\"}")) {
      perform(
          post("/api/platform/environments").contentType(MediaType.APPLICATION_JSON).content(json),
          400);
    }

    assertEquals(version, storedVersion(id));
    assertEquals(note, storedNote(id));
    assertEquals(config, storedConfigJson(id));
    assertEquals(1, countEnvironments());
  }

  /** 旧安装设置与全局卸载脚本接口必须明确消失，旧请求体不能被当作正向入口。 */
  @Test
  void oldInstallSettingsAndGlobalUninstallInterfacesAreRejected() throws Exception {
    JsonNode created = create("old-route-env", "note");
    String id = created.path("id").asText();
    String path = "/api/platform/environments/" + id;

    perform(
        put(path + "/install-settings")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(update("0", "note", config()))),
        404);
    perform(get("/api/platform/daemon-uninstall-scripts/windows"), 404);
    perform(
        put(path)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"expectedVersion\":\"0\",\"installConfig\":{\"operatingSystem\":\"linux\"}}"),
        400);

    assertEquals(0, storedVersion(id));
    assertEquals(1, countEnvironments());
  }

  /** 导出必须携带 note 与完整 daemonConfig，导入按 CAS 更新并支持清空 note；缺失 daemonConfig 必须拒绝。 */
  @Test
  void configSyncRoundtripExportsAndImportsNoteAndDaemonConfig() throws Exception {
    JsonNode created = create("roundtrip-env", "host note");
    String id = created.path("id").asText();
    String path = "/api/platform/environments/" + id;
    String token = created.path("registrationToken").asText();

    String yaml = export("roundtrip-env");
    Map<String, Object> card = environment(parseYaml(yaml));
    assertEquals("roundtrip-env", card.get("name"));
    assertEquals("host note", card.get("note"));
    assertEquals(RegistrationTokens.hash(token), card.get("registrationTokenHash"));
    Map<String, Object> config = daemonConfig(card);
    assertEquals("https://studio.example.com", config.get("studioUrl"));
    assertEquals("/bin/bash", config.get("bashExecutable"));
    assertEquals("http://127.0.0.1:7890", config.get("proxyUrl"));
    Map<String, Object> lsp = map(config.get("lsp"));
    Map<String, Object> servers = map(lsp.get("servers"));
    Map<String, Object> jdtls = map(servers.get("jdtls"));
    assertEquals(List.of("jdtls", "--stdio"), jdtls.get("command"));
    assertEquals(List.of(".java"), jdtls.get("extensions"));
    assertEquals(List.of("pom.xml"), jdtls.get("rootMarkers"));
    assertEquals(List.of(".git"), jdtls.get("firstMatchMarkers"));
    assertFalse(card.containsKey("installConfig"));
    assertFalse(card.containsKey("operatingSystem"));
    assertFalse(yaml.contains(token));

    postData("/api/platform/config-sync/import", Map.of("yaml", yaml));
    JsonNode afterImport = getData(path);
    assertEquals(1, afterImport.path("version").asInt());
    assertEquals("host note", afterImport.path("note").asText());
    assertEquals(created.path("daemonConfig"), afterImport.path("daemonConfig"));

    String changed =
        mutateYaml(
            yaml,
            document -> {
              Map<String, Object> entry = environment(document);
              entry.put("note", "changed note");
              entry.put("registrationTokenHash", RegistrationTokens.hash("replacement-token"));
              Map<String, Object> changedConfig = daemonConfig(entry);
              changedConfig.put("studioUrl", "https://new.example.com");
              changedConfig.put("proxyUrl", "http://127.0.0.1:8899");
              changedConfig.remove("lsp");
            });
    postData("/api/platform/config-sync/import", Map.of("yaml", changed));
    JsonNode updated = getData(path);
    assertEquals(2, updated.path("version").asInt());
    assertEquals("changed note", updated.path("note").asText());
    assertEquals("https://new.example.com", updated.at("/daemonConfig/studioUrl").asText());
    assertEquals("http://127.0.0.1:8899", updated.at("/daemonConfig/proxyUrl").asText());
    assertFalse(updated.path("daemonConfig").has("lsp"));
    assertEquals(RegistrationTokens.hash("replacement-token"), storedTokenHash(id));

    String withoutNote = mutateYaml(changed, document -> environment(document).remove("note"));
    postData("/api/platform/config-sync/import", Map.of("yaml", withoutNote));
    JsonNode withoutNoteCard = getData(path);
    assertEquals(3, withoutNoteCard.path("version").asInt());
    assertFalse(withoutNoteCard.has("note"));
    assertNull(storedNote(id));

    String withoutConfig =
        mutateYaml(changed, document -> environment(document).remove("daemonConfig"));
    perform(
        post("/api/platform/config-sync/import")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("yaml", withoutConfig))),
        400);
    assertEquals(3, storedVersion(id));
    assertNull(storedNote(id));
    assertEquals("https://new.example.com", storedConfig(id).path("studioUrl").asText());

    perform(delete(path).param("expectedVersion", "3"), 204);
    postData("/api/platform/config-sync/import", Map.of("yaml", yaml));
    JsonNode recreated = getData("/api/platform/environments").get(0);
    assertEquals(0, recreated.path("version").asInt());
    assertEquals("host note", recreated.path("note").asText());
    assertEquals("https://studio.example.com", recreated.at("/daemonConfig/studioUrl").asText());
    assertEquals(RegistrationTokens.hash(token), storedTokenHash(recreated.path("id").asText()));
  }

  /** 资源文件中的完整卡片必须能直接导入，且导出保持 note 与 daemonConfig。 */
  @Test
  void configSyncResourceFixtureImportsCompleteCard() throws Exception {
    String yaml = resource("environment-card.yaml");
    postData("/api/platform/config-sync/check-import", Map.of("yaml", yaml));
    postData("/api/platform/config-sync/import", Map.of("yaml", yaml));

    JsonNode card = getData("/api/platform/environments").get(0);
    assertEquals("resource-env", card.path("name").asText());
    assertEquals("resource note 'quoted'", card.path("note").asText());
    assertEquals("https://studio.example.com", card.at("/daemonConfig/studioUrl").asText());
    assertEquals("/bin/bash", card.at("/daemonConfig/bashExecutable").asText());
    assertEquals("http://127.0.0.1:7890", card.at("/daemonConfig/proxyUrl").asText());
    assertEquals("jdtls", card.at("/daemonConfig/lsp/servers/jdtls/command/0").asText());
    assertEquals(".java", card.at("/daemonConfig/lsp/servers/jdtls/extensions/0").asText());
    assertEquals(0, card.path("version").asInt());
    assertEquals(
        "98956f3dc7f197ae785c16ea7017600dc33bd59e15fc27d90b8e8d43b8055bb6",
        storedTokenHash(card.path("id").asText()));

    Map<String, Object> exported = environment(parseYaml(export("resource-env")));
    assertEquals("resource note 'quoted'", exported.get("note"));
    assertEquals("https://studio.example.com", daemonConfig(exported).get("studioUrl"));
    assertFalse(exported.containsKey("installConfig"));
    assertFalse(exported.containsKey("operatingSystem"));
  }

  /** 预检查与 allowPartial 导入仍必须拒绝缺失 daemonConfig 与旧 installConfig/operatingSystem。 */
  @Test
  void configSyncRejectsMissingAndRemovedFieldsBeforeWrites() throws Exception {
    String tokenHash = RegistrationTokens.hash("rejected-import-token");
    List<String> invalidYamls =
        List.of(
            """
            environments:
              - name: old-install
                registrationTokenHash: %s
                installConfig:
                  operatingSystem: linux
                  daemon:
                    studioUrl: https://studio.example.com
            """
                .formatted(tokenHash),
            """
            environments:
              - name: old-operating-system
                registrationTokenHash: %s
                operatingSystem: linux
                daemonConfig:
                  studioUrl: https://studio.example.com
            """
                .formatted(tokenHash),
            """
            environments:
              - name: missing-daemon-config
                registrationTokenHash: %s
            """
                .formatted(tokenHash),
            """
            environments:
              - name: invalid-lsp-config
                registrationTokenHash: %s
                daemonConfig:
                  studioUrl: https://studio.example.com
                  lsp:
                    servers:
                      jdtls:
                        command: [jdtls]
                        extensions: [.java]
                        rootMarkers: [../outside]
            """
                .formatted(tokenHash));
    for (String yaml : invalidYamls) {
      for (String endpoint : List.of("check-import", "import")) {
        MvcResult result =
            perform(
                post("/api/platform/config-sync/" + endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of("yaml", yaml, "allowPartial", true))),
                400);
        assertFalse(body(result).contains(tokenHash));
      }
      assertEquals(0, countEnvironments());
    }

    String unknownField =
        """
        environments:
          - name: unknown-config-field
            registrationTokenHash: %s
            daemonConfig:
              studioUrl: https://studio.example.com
              unknown: true
        """
            .formatted(tokenHash);
    perform(
        post("/api/platform/config-sync/import")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("yaml", unknownField))),
        400);
    MvcResult partial =
        perform(
            post("/api/platform/config-sync/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of("yaml", unknownField, "allowPartial", true))),
            200);
    assertEquals(0, countEnvironments());
    assertTrue(body(partial).contains("unknown-config-field"));
    assertTrue(body(partial).contains("unsupported field"));
  }

  /** 第二张卡片写入失败时，第一张卡片已写入的 token、note、configuration 与 version 必须一起回滚。 */
  @Test
  void failureAfterFirstEnvironmentUpdateRollsBackTokenConfigAndVersion() throws Exception {
    JsonNode first = create("first", "first note");
    JsonNode second = create("second", "second note");
    String firstId = first.path("id").asText();
    long firstVersion = storedVersion(firstId);
    String firstNote = storedNote(firstId);
    String firstConfig = storedConfigJson(firstId);
    String firstTokenHash = storedTokenHash(firstId);
    long secondVersion = storedVersion(second.path("id").asText());
    String secondNote = storedNote(second.path("id").asText());
    String secondConfig = storedConfigJson(second.path("id").asText());
    String secondTokenHash = storedTokenHash(second.path("id").asText());

    jdbc.execute(
        """
        create function reject_second_card_update() returns trigger language plpgsql as $$
        begin
          if new.name = 'second' then
            raise unique_violation using message = 'test write failure';
          end if;
          return new;
        end $$;
        create trigger reject_second_card_update before update on environment
          for each row execute function reject_second_card_update();
        """);
    String yaml =
        """
        environments:
          - name: first
            note: first import note
            registrationTokenHash: %s
            daemonConfig:
              studioUrl: https://first-import.example.com
          - name: second
            note: second import note
            registrationTokenHash: %s
            daemonConfig:
              studioUrl: https://second-import.example.com
        """
            .formatted(
                RegistrationTokens.hash("first-import-token"),
                RegistrationTokens.hash("second-import-token"));
    try {
      perform(
          post("/api/platform/config-sync/import")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(Map.of("yaml", yaml))),
          409);
      assertEquals(firstVersion, storedVersion(firstId));
      assertEquals(firstNote, storedNote(firstId));
      assertEquals(firstConfig, storedConfigJson(firstId));
      assertEquals(firstTokenHash, storedTokenHash(firstId));
      assertEquals(secondVersion, storedVersion(second.path("id").asText()));
      assertEquals(secondNote, storedNote(second.path("id").asText()));
      assertEquals(secondConfig, storedConfigJson(second.path("id").asText()));
      assertEquals(secondTokenHash, storedTokenHash(second.path("id").asText()));
    } finally {
      jdbc.execute("drop trigger reject_second_card_update on environment");
      jdbc.execute("drop function reject_second_card_update()");
    }
  }

  private JsonNode create(String name, String note) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    if (note != null) {
      body.put("note", note);
    }
    body.put("daemonConfig", config());
    return postData("/api/platform/environments", body);
  }

  private String export(String name) throws Exception {
    return postData(
            "/api/platform/config-sync/export",
            Map.of("items", List.of(Map.of("kind", "environments", "name", name))))
        .path("yaml")
        .asText();
  }

  /** Environment 与配置同步端点直接返回资源，没有 {@code data} 包装。 */
  @Override
  protected JsonNode data(MvcResult result) throws Exception {
    return objectMapper
        .reader()
        .with(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .readTree(result.getResponse().getContentAsString());
  }

  private static Map<String, Object> update(String version, String note, Object config) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedVersion", version);
    if (note != null) {
      body.put("note", note);
    }
    body.put("daemonConfig", config);
    return body;
  }

  private static Map<String, Object> config() {
    return Map.of(
        "studioUrl",
        "https://studio.example.com",
        "bashExecutable",
        "/bin/bash",
        "proxyUrl",
        "http://127.0.0.1:7890",
        "lsp",
        Map.of(
            "servers",
            Map.of(
                "jdtls",
                Map.of(
                    "command",
                    List.of("jdtls", "--stdio"),
                    "extensions",
                    List.of(".java"),
                    "rootMarkers",
                    List.of("pom.xml"),
                    "firstMatchMarkers",
                    List.of(".git")))));
  }

  private static Map<String, Object> changedConfig() {
    return Map.of(
        "studioUrl", "https://changed.example.com",
        "bashExecutable", "/usr/bin/bash",
        "proxyUrl", "http://127.0.0.1:7891");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> environment(Map<String, Object> document) {
    return ((List<Map<String, Object>>) document.get("environments")).get(0);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> daemonConfig(Map<String, Object> environment) {
    return (Map<String, Object>) environment.get("daemonConfig");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  private String resource(String name) throws Exception {
    try (InputStream input =
        getClass().getResourceAsStream("/fun/fengwk/kkstudio/web/configsync/" + name)) {
      assertNotNull(input, name);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private long storedVersion(String id) {
    Long version =
        jdbc.queryForObject(
            "select version from environment where id = ?", Long.class, UUID.fromString(id));
    return version == null ? -1 : version;
  }

  private String storedNote(String id) {
    return jdbc.queryForObject(
        "select note from environment where id = ?", String.class, UUID.fromString(id));
  }

  private JsonNode storedConfig(String id) {
    String json =
        jdbc.queryForObject(
            "select daemon_config::text from environment where id = ?",
            String.class,
            UUID.fromString(id));
    return objectMapper.readTree(json);
  }

  private String storedConfigJson(String id) {
    return jdbc.queryForObject(
        "select daemon_config::text from environment where id = ?",
        String.class,
        UUID.fromString(id));
  }

  private String storedTokenHash(String id) {
    return jdbc.queryForObject(
        "select registration_token_hash from environment where id = ?",
        String.class,
        UUID.fromString(id));
  }

  private int countEnvironments() {
    Integer count = jdbc.queryForObject("select count(1) from environment", Integer.class);
    return count == null ? 0 : count;
  }
}
