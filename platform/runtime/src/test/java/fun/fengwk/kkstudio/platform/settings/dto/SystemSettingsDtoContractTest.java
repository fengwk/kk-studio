package fun.fengwk.kkstudio.platform.settings.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryBackoffStrategy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** System settings wire DTO 契约：wire 名称稳定，且任何 DTO 都不得携带秘密 / bootstrap 输入。 */
class SystemSettingsDtoContractTest {

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @Test
  void dtoWireNamesMatchTheCanonicalContract() throws Exception {
    String json = objectMapper.writeValueAsString(defaultSections());
    assertTrue(json.contains("\"tool\""), "tool section must serialize: " + json);
    assertTrue(json.contains("\"aiRuntime\""), "aiRuntime section must serialize: " + json);
    assertTrue(json.contains("\"modelGatewayBusyRetryMillis\""));
    assertTrue(json.contains("\"permission\""));
    assertTrue(json.contains("\"backendNetwork\""));
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
            SystemSettingsBackendNetworkDTO.class,
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

  /**
   * HTTP wire profile 的真实往返 fixture：Long 时限字段输出规范十进制字符串，Integer 计数保持 JSON 数值，且能按同一 profile
   * 反序列化回原值。这里复刻 HTTP 层 Long→String 的能力（它属于 HTTP 边界 mixin，但 DTO 迁移不能弱化它）。
   */
  @Test
  void longFieldsSerializeAsWireStringsWhileIntegersStayNumbers() throws Exception {
    JsonMapper wireMapper =
        JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .addModule(longToStringModule())
            .build();

    SystemSettingsAiRuntimeDTO ai = new SystemSettingsAiRuntimeDTO();
    ai.setRetryBaseDelayMillis(250L);
    ai.setSubagentMaxTurns(5);
    ai.setModelHttpRetryStatusCodes(List.of(429, 500));

    String json = wireMapper.writeValueAsString(ai);
    assertTrue(json.contains("\"retryBaseDelayMillis\":\"250\""), json);
    assertTrue(json.contains("\"subagentMaxTurns\":5"), json);
    assertTrue(json.contains("\"modelHttpRetryStatusCodes\":[429,500]"), json);

    SystemSettingsAiRuntimeDTO read = wireMapper.readValue(json, SystemSettingsAiRuntimeDTO.class);
    assertEquals(250L, read.getRetryBaseDelayMillis());
    assertEquals(5, read.getSubagentMaxTurns());
    assertEquals(List.of(429, 500), read.getModelHttpRetryStatusCodes());
  }

  /** 复刻 HTTP 层 Long/long → 十进制字符串的能力，供 wire profile 往返 fixture 使用。 */
  private static SimpleModule longToStringModule() {
    SimpleModule module = new SimpleModule("LongToStringModule");
    module.addSerializer(Long.class, new ToStringSerializer(Long.class));
    module.addSerializer(Long.TYPE, new ToStringSerializer(Long.TYPE));
    return module;
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
    tool.setModelGatewayBusyRetryMillis(5000L);
    tool.setToolGatewayOverloadRetryMillis(5000L);
    dto.setTool(tool);
    SystemSettingsAiRuntimeDTO aiRuntime = new SystemSettingsAiRuntimeDTO();
    aiRuntime.setRetryBackoffStrategy(InvocationRetryBackoffStrategy.EXPONENTIAL);
    dto.setAiRuntime(aiRuntime);
    SystemSettingsBackendNetworkDTO backendNetwork = new SystemSettingsBackendNetworkDTO();
    backendNetwork.setNoProxyHosts("localhost,127.*,::1");
    dto.setBackendNetwork(backendNetwork);
    return dto;
  }
}
