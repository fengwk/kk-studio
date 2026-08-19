package fun.fengwk.kkstudio.share.systemsettings;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
                "{\"tool\":{},\"aiRuntime\":{},\"environment\":{},\"integrations\":{},"
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
  }

  @Test
  void dtoWireNamesMatchTheCanonicalContract() throws Exception {
    String json = objectMapper.writeValueAsString(defaultSections());
    assertTrue(json.contains("\"tool\""), "tool section must serialize: " + json);
    assertTrue(json.contains("\"aiRuntime\""), "aiRuntime section must serialize: " + json);
    assertTrue(json.contains("\"modelGatewayBusyRetryMillis\""));
    assertTrue(json.contains("\"permission\""));
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
            // compactionReserveTokens / compactionMaxRecentTokens 是 token 预算计数，不是秘密 token。
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
    tool.setSkillLoadTimeoutMillis(30000L);
    dto.setTool(tool);
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
