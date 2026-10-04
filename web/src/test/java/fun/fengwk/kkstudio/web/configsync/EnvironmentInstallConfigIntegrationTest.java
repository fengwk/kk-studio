package fun.fengwk.kkstudio.web.configsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** HTTP 边界和真实隔离 PG17 验证保存、CAS、导入清空以及事务回滚，而非 SQL 文本替代。 */
class EnvironmentInstallConfigIntegrationTest extends ConfigSyncTestSupport {
  @Autowired private JdbcTemplate jdbc;

  @Test
  void saveReadListStaleCasNoOpRotateAndDelete() throws Exception {
    JsonNode created = create("install-env");
    String id = created.path("id").asText();
    String path = "/api/harness/environments/" + id;
    String token = created.path("registrationToken").asText();
    assertFalse(created.hasNonNull("installConfig"));
    JsonNode saved = putData(path + "/install-config", update("0", config()));
    assertEquals("1", saved.path("version").asText());
    assertFalse(saved.has("registrationToken"));
    assertEquals(
        "https://studio.example.com", saved.at("/installConfig/daemon/studioUrl").asText());
    assertEquals(saved.path("installConfig"), getData(path).path("installConfig"));
    assertEquals(
        saved.path("installConfig"),
        getData("/api/harness/environments").get(0).path("installConfig"));
    assertEquals(
        "linux",
        jdbc.queryForObject(
            "select install_config->>'operatingSystem' from environment where id = ?",
            String.class,
            UUID.fromString(id)));
    assertEquals(token, getData(path + "/token").path("registrationToken").asText());

    perform(
        put(path + "/install-config")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(update("0", config()))),
        409);
    JsonNode unchanged = putData(path + "/install-config", update("1", config()));
    assertEquals(saved.path("version"), unchanged.path("version"));
    assertEquals(saved.path("updateTime"), unchanged.path("updateTime"));
    JsonNode rotated = postData(path + "/registration-token", Map.of("expectedVersion", "1"));
    assertEquals(saved.path("installConfig"), rotated.path("installConfig"));
    assertEquals("2", rotated.path("version").asText());
    perform(delete(path).param("expectedVersion", "2"), 204);
    assertEquals(0, jdbc.queryForObject("select count(*) from environment", Integer.class));
  }

  @Test
  void invalidKnownFieldsAndTypesFailWithoutMutation() throws Exception {
    String id = create("invalid-env").path("id").asText();
    String path = "/api/harness/environments/" + id + "/install-config";
    for (String json :
        List.of(
            "{\"expectedVersion\":0,\"installConfig\":{}}",
            "{\"expectedVersion\":\"0\",\"installConfig\":null}",
            "{\"expectedVersion\":\"0\",\"bogus\":true,\"installConfig\":{}}",
            "{\"expectedVersion\":\"0\",\"installConfig\":{\"operatingSystem\":\"linux\",\"daemon\":{\"studioUrl\":42}}}",
            "{\"expectedVersion\":\"0\",\"installConfig\":{\"operatingSystem\":\"linux\",\"javaHome\":\"${JAVA_HOME}\",\"daemon\":{\"studioUrl\":\"https://studio.example.com\"}}}",
            "{\"expectedVersion\":\"0\",\"installConfig\":{\"operatingSystem\":\"linux\",\"daemon\":{\"studioUrl\":\"https://studio.example.com\",\"lsp\":{\"servers\":{}}}}}")) {
      perform(put(path).contentType(MediaType.APPLICATION_JSON).content(json), 400);
    }
    assertEquals(0L, jdbc.queryForObject("select version from environment", Long.class));
    assertNull(jdbc.queryForObject("select install_config::text from environment", String.class));
  }

  @Test
  void configSyncRoundtripCreateAtomicUpdateIdempotencyAndAbsentClears() throws Exception {
    JsonNode created = create("roundtrip-env");
    String id = created.path("id").asText();
    String path = "/api/harness/environments/" + id;
    JsonNode saved = putData(path + "/install-config", update("0", config()));
    String yaml =
        postData(
                "/api/settings/sync/export",
                Map.of("items", List.of(Map.of("kind", "environments", "name", "roundtrip-env"))))
            .path("yaml")
            .asText();
    assertTrue(yaml.contains("installConfig:"));
    assertTrue(yaml.contains(created.path("registrationToken").asText()));
    postData("/api/settings/sync/import", Map.of("yaml", yaml));
    assertEquals(saved.path("version"), getData(path).path("version"));
    perform(delete(path).param("expectedVersion", "1"), 204);
    postData("/api/settings/sync/import", Map.of("yaml", yaml));
    JsonNode imported = getData("/api/harness/environments").get(0);
    assertEquals(saved.path("installConfig"), imported.path("installConfig"));
    String importedPath = "/api/harness/environments/" + imported.path("id").asText();
    String changed =
        mutateYaml(
            yaml,
            doc -> {
              Map<String, Object> env = environment(doc);
              env.put("registrationToken", "replacement-token");
              env.put(
                  "installConfig",
                  Map.of(
                      "operatingSystem",
                      "macos",
                      "daemon",
                      Map.of("studioUrl", "https://new.example.com")));
            });
    postData("/api/settings/sync/import", Map.of("yaml", changed));
    JsonNode updated = getData(importedPath);
    assertEquals("1", updated.path("version").asText());
    assertEquals("macos", updated.at("/installConfig/operatingSystem").asText());
    assertEquals(
        "replacement-token", getData(importedPath + "/token").path("registrationToken").asText());
    String cleared = mutateYaml(changed, doc -> environment(doc).remove("installConfig"));
    postData("/api/settings/sync/import", Map.of("yaml", cleared));
    assertFalse(getData(importedPath).hasNonNull("installConfig"));
    assertEquals("2", getData(importedPath).path("version").asText());
    postData("/api/settings/sync/import", Map.of("yaml", cleared));
    assertEquals("2", getData(importedPath).path("version").asText());
  }

  @Test
  void precheckAndAllowPartialImportRejectDeepKnownErrorsBeforeWrites() throws Exception {
    String yaml =
        """
        environments:
          - name: valid-new
            registrationToken: valid-token
          - name: invalid-new
            registrationToken: invalid-token
            bogus: ignored
            installConfig:
              operatingSystem: linux
              daemon:
                studioUrl: https://studio.example.com
                lsp:
                  servers:
                    jdtls:
                      command: [jdtls]
                      extensions: [.java]
                      rootMarkers: [../outside]
        """;
    for (String endpoint : List.of("import/check", "import")) {
      perform(
          post("/api/settings/sync/" + endpoint)
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(Map.of("yaml", yaml, "allowPartial", true))),
          400);
      assertEquals(0, jdbc.queryForObject("select count(*) from environment", Integer.class));
    }
  }

  @Test
  void failureAfterFirstEnvironmentUpdateRollsBackTokenConfigAndVersion() throws Exception {
    JsonNode first = create("first");
    create("second");
    String firstPath = "/api/harness/environments/" + first.path("id").asText();
    putData(firstPath + "/install-config", update("0", config()));
    jdbc.execute(
        """
        create function reject_second_install() returns trigger language plpgsql as $$
        begin
          if new.name = 'second' then
            raise unique_violation using message = 'test write failure';
          end if;
          return new;
        end $$;
        create trigger reject_second_install before update on environment
          for each row execute function reject_second_install();
        """);
    String yaml =
        """
        environments:
          - name: first
            registrationToken: replacement-first
          - name: second
            registrationToken: replacement-second
        """;
    try {
      perform(
          post("/api/settings/sync/import")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(Map.of("yaml", yaml))),
          409);
      JsonNode current = getData(firstPath);
      assertEquals("1", current.path("version").asText());
      assertEquals("linux", current.at("/installConfig/operatingSystem").asText());
      assertEquals(
          first.path("registrationToken"), getData(firstPath + "/token").path("registrationToken"));
    } finally {
      jdbc.execute("drop trigger reject_second_install on environment");
      jdbc.execute("drop function reject_second_install()");
    }
  }

  private JsonNode create(String name) throws Exception {
    return postData("/api/harness/environments", Map.of("name", name));
  }

  private static Map<String, Object> update(String version, Object config) {
    return Map.of("expectedVersion", version, "installConfig", config);
  }

  private static Map<String, Object> config() {
    return Map.of(
        "operatingSystem",
        "linux",
        "javaHome",
        "/opt/jdk21",
        "daemon",
        Map.of(
            "studioUrl",
            "https://studio.example.com/",
            "note",
            "host",
            "lsp",
            Map.of(
                "servers",
                Map.of(
                    "jdtls",
                    Map.of(
                        "command",
                        List.of("jdtls"),
                        "extensions",
                        List.of(".java"),
                        "rootMarkers",
                        List.of("pom.xml"),
                        "firstMatchMarkers",
                        List.of(".git"))))));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> environment(Map<String, Object> document) {
    return ((List<Map<String, Object>>) document.get("environments")).get(0);
  }
}
