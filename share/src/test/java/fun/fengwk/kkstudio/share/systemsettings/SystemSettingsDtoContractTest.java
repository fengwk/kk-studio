package fun.fengwk.kkstudio.share.systemsettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * System settings wire DTO 契约：未知字段必须在每个嵌套层级被拒绝，且任何 DTO 都不得携带秘密 / bootstrap 输入（key、
 * token、instanceId、endpoint、filesystem 路径等）。
 */
class SystemSettingsDtoContractTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void rejectsUnknownFieldsAtEveryNestingLevel() throws Exception {
    assertRejectsUnknown(
        () ->
            objectMapper.readValue(
                "{\"tool\":{},\"aiRuntime\":{},\"environment\":{},\"network\":{},\"integrations\":{},"
                    + "\"storageMedia\":{},\"advanced\":{},\"unknown\":1}",
                SystemSettingsUpdateDTO.class));
    assertRejectsUnknown(
        () ->
            objectMapper.readValue(
                "{\"pattern\":\"*\",\"action\":\"ask\",\"unknown\":1}",
                SystemSettingsToolDTO.PermissionRuleDTO.class));
    assertRejectsUnknown(
        () ->
            objectMapper.readValue(
                "{\"enabled\":true,\"unknown\":1}",
                SystemSettingsIntegrationsDTO.ComfyuiDTO.class));
    assertRejectsUnknown(
        () ->
            objectMapper.readValue(
                "{\"paidEnabled\":false,\"unknown\":1}",
                SystemSettingsIntegrationsDTO.GptImage2DTO.class));
    // network 只允许两个全局字段，不接受 enabled 或模块级 override。
    assertRejectsUnknown(
        () -> objectMapper.readValue("{\"enabled\":true}", SystemSettingsNetworkDTO.class));
    assertRejectsUnknown(
        () -> objectMapper.readValue("{\"overrides\":{}}", SystemSettingsNetworkDTO.class));
  }

  @Test
  void rejectsUnknownFieldsAtEverySchemaNestingLevel() throws Exception {
    assertRejectsUnknown(
        () ->
            objectMapper.readValue(
                "{\"sections\":[{\"unknown\":1}]}", SystemSettingsSchemaDTO.class));
    assertRejectsUnknown(
        () ->
            objectMapper.readValue(
                "{\"key\":\"tool\",\"labelKey\":\"l\",\"descriptionKey\":\"d\",\"restartRequired\":false,"
                    + "\"groups\":[{\"key\":\"g\",\"labelKey\":\"l\",\"descriptionKey\":\"d\","
                    + "\"restartRequired\":false,\"applyTiming\":null,\"unknown\":1}]}",
                SystemSettingsSchemaDTO.class));
    assertRejectsUnknown(
        () ->
            objectMapper.readValue(
                "{\"key\":\"g\",\"labelKey\":\"l\",\"descriptionKey\":\"d\",\"restartRequired\":false,"
                    + "\"applyTiming\":null,\"fields\":[{\"path\":\"p\",\"labelKey\":\"l\",\"hintKey\":null,"
                    + "\"type\":\"TEXT\",\"nullable\":false,\"min\":null,\"max\":null,\"options\":null,"
                    + "\"unknown\":1}]}",
                SystemSettingsSchemaDTO.GroupDTO.class));
    assertRejectsUnknown(
        () ->
            objectMapper.readValue(
                "{\"value\":\"allow\",\"labelKey\":\"l\",\"unknown\":1}",
                SystemSettingsSchemaDTO.OptionDTO.class));
  }

  @Test
  void dtoWireNamesMatchTheCanonicalContract() throws Exception {
    String json = objectMapper.writeValueAsString(defaultSections());
    assertTrue(json.contains("\"tool\""), "tool section must serialize: " + json);
    assertTrue(json.contains("\"aiRuntime\""), "aiRuntime section must serialize: " + json);
    assertTrue(json.contains("\"modelGatewayBusyRetryMillis\""));
    assertTrue(json.contains("\"permission\""));
    assertTrue(json.contains("\"network\""));
    assertTrue(json.contains("\"proxyUrl\""));
    assertTrue(json.contains("\"noProxyHosts\":\"localhost,127.*,::1\""));
  }

  @Test
  void noDtoCarriesSecretsOrBootstrapInputs() throws Exception {
    List<String> violations = new ArrayList<>();
    List<Class<?>> classes =
        List.of(
            SystemSettingsSectionsDTO.class,
            SystemSettingsDTO.class,
            SystemSettingsUpdateDTO.class,
            SystemSettingsToolDTO.class,
            SystemSettingsToolDTO.PermissionRuleDTO.class,
            SystemSettingsAiRuntimeDTO.class,
            SystemSettingsEnvironmentDTO.class,
            SystemSettingsNetworkDTO.class,
            SystemSettingsIntegrationsDTO.class,
            SystemSettingsIntegrationsDTO.ComfyuiDTO.class,
            SystemSettingsIntegrationsDTO.OpenCliHubDTO.class,
            SystemSettingsIntegrationsDTO.SeedanceDTO.class,
            SystemSettingsIntegrationsDTO.GptImage2DTO.class,
            SystemSettingsIntegrationsDTO.MiniMaxH3DTO.class,
            SystemSettingsStorageMediaDTO.class,
            SystemSettingsAdvancedDTO.class);
    List<String> forbidden =
        List.of(
            "apikey",
            "accesskey",
            "secretkey",
            "bearer",
            "token",
            "instanceid",
            "credential",
            "secret",
            "endpoint",
            "region",
            "bucket",
            "workdir",
            "environmentroot",
            "tempdir",
            "binary",
            "prefix",
            "password",
            "username");
    for (Class<?> dtoClass : classes) {
      for (Field field : allDeclaredFields(dtoClass)) {
        if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        String normalized = field.getName().toLowerCase();
        boolean pluralTokenBudget = normalized.endsWith("tokens");
        for (String forbiddenPart : forbidden) {
          if ("token".equals(forbiddenPart) && pluralTokenBudget) {
            // compactionKeepRecentTokens 是 token 预算计数，不是秘密 token。
            continue;
          }
          if (normalized.contains(forbiddenPart)) {
            violations.add(dtoClass.getSimpleName() + "." + field.getName());
          }
        }
      }
    }
    assertTrue(
        violations.isEmpty(), "DTOs must not expose secrets or bootstrap inputs: " + violations);
  }

  @Test
  void networkWirePreservesNullAndEmptyTextButRejectsScalarCoercion() throws Exception {
    // 默认 ObjectMapper 也必须严格：网络字段不能把数字/布尔静默转换成可保存字符串。
    SystemSettingsNetworkDTO dto =
        objectMapper.readValue(
            "{\"proxyUrl\":null,\"noProxyHosts\":\"\"}", SystemSettingsNetworkDTO.class);
    assertNull(dto.getProxyUrl());
    assertEquals("", dto.getNoProxyHosts());
    SystemSettingsNetworkDTO proxy =
        objectMapper.readValue(
            "{\"proxyUrl\":\"http://proxy:3128\",\"noProxyHosts\":\"localhost,127.*,::1\"}",
            SystemSettingsNetworkDTO.class);
    assertEquals("http://proxy:3128", proxy.getProxyUrl());
    assertEquals("localhost,127.*,::1", proxy.getNoProxyHosts());
    for (String field : List.of("proxyUrl", "noProxyHosts")) {
      for (String value : List.of("1", "1.5", "true", "[]", "{}")) {
        assertThrows(
            JsonMappingException.class,
            () ->
                objectMapper.readValue(
                    "{\"" + field + "\":" + value + "}", SystemSettingsNetworkDTO.class));
      }
    }
  }

  private static List<Field> allDeclaredFields(Class<?> type) {
    List<Field> fields = new ArrayList<>();
    Class<?> cursor = type;
    while (cursor != null && cursor != Object.class) {
      for (Field field : cursor.getDeclaredFields()) {
        fields.add(field);
      }
      cursor = cursor.getSuperclass();
    }
    return fields;
  }

  private static SystemSettingsSectionsDTO defaultSections() {
    SystemSettingsSectionsDTO dto = new SystemSettingsSectionsDTO();
    SystemSettingsToolDTO tool = new SystemSettingsToolDTO();
    SystemSettingsToolDTO.PermissionRuleDTO rule = new SystemSettingsToolDTO.PermissionRuleDTO();
    rule.setPattern("*");
    rule.setAction("ask");
    tool.setPermission(Map.of("write", List.of(rule)));
    tool.setDefaultYolo(false);
    tool.setModelGatewayBusyRetryMillis(5000L);
    tool.setToolGatewayBusyRetryMillis(1000L);
    tool.setToolGatewayOverloadRetryMillis(5000L);
    dto.setTool(tool);
    SystemSettingsNetworkDTO network = new SystemSettingsNetworkDTO();
    network.setNoProxyHosts("localhost,127.*,::1");
    dto.setNetwork(network);
    return dto;
  }

  private static void assertRejectsUnknown(ThrowingSupplier supplier) {
    try {
      supplier.get();
      fail("expected unknown-field rejection");
    } catch (Exception error) {
      assertTrue(
          containsUnknown(error), () -> "expected unknown-field rejection, but got: " + error);
    }
  }

  @FunctionalInterface
  private interface ThrowingSupplier {
    Object get() throws Exception;
  }

  private static boolean containsUnknown(Throwable error) {
    Throwable cursor = error;
    while (cursor != null) {
      if (cursor.getMessage() != null && cursor.getMessage().contains("unknown")) {
        return true;
      }
      cursor = cursor.getCause();
    }
    return false;
  }
}
