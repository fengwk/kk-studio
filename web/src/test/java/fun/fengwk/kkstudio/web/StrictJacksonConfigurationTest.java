package fun.fengwk.kkstudio.web;

import static fun.fengwk.kkstudio.platformtest.harness.HarnessRuntimeTestFixtures.jsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.chat.dto.ChatDTO;
import fun.fengwk.kkstudio.harness.model.ModelPricingCondition;
import fun.fengwk.kkstudio.platform.catalog.dto.HarnessExecutionSelectionDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelVariantDTO;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessBranchSettingsDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandCreateDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsBackendNetworkDTO;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 验证 Spring Boot 管理并经过 {@link StrictJacksonConfiguration} 定制的 {@link JsonMapper} wire 行为。 */
class StrictJacksonConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
          .withUserConfiguration(StrictJacksonConfiguration.class);

  /** 共享 mapper 与 HTTP 派生 mapper 均以毫秒整数读写 Instant，包含负值和非整秒。 */
  @Test
  void instantUsesEpochMillisecondsInBothMappers() {
    runner.run(
        context -> {
          JsonMapper shared = context.getBean(JsonMapper.class);
          for (JsonMapper mapper :
              List.of(shared, HttpBoundaryConfiguration.strictHttpMapper(shared))) {
            for (long millis : new long[] {1772366400123L, 123L, -123L}) {
              Instant instant = Instant.ofEpochMilli(millis);
              assertEquals(Long.toString(millis), mapper.writeValueAsString(instant));
              assertEquals(instant, mapper.readValue(Long.toString(millis), Instant.class));
            }
          }
        });
  }

  /** Chat 默认环境是 required-nullable，不能被全局 NON_NULL 配置省略。 */
  @Test
  void shouldAlwaysIncludeChatDefaultEnvironment() {
    runner.run(
        context -> {
          JsonMapper mapper = context.getBean(JsonMapper.class);
          ChatDTO chat = new ChatDTO();
          assertEquals(
              "{\"environmentName\":null,\"yoloEnabled\":false}", mapper.writeValueAsString(chat));
          chat.setEnvironmentName("dev");
          assertEquals(
              "{\"environmentName\":\"dev\",\"yoloEnabled\":false}",
              mapper.writeValueAsString(chat));
        });
  }

  /** 测试意图：验证可选字段为 null 时默认省略，且存在非 null 可选字段时按 DTO 声明顺序输出。 */
  @Test
  void shouldOmitNullFieldsAndPreserveDeclarationOrderForAgentModelVariant() {
    runner.run(
        context -> {
          JsonMapper mapper = context.getBean(JsonMapper.class);

          ModelVariantDTO variantOnlyId = new ModelVariantDTO();
          variantOnlyId.setId("standard");
          assertEquals("{\"id\":\"standard\"}", mapper.writeValueAsString(variantOnlyId));

          ModelVariantDTO variantWithReasoning = new ModelVariantDTO();
          variantWithReasoning.setId("standard");
          variantWithReasoning.setReasoningEffort("low");
          assertEquals(
              "{\"id\":\"standard\",\"reasoningEffort\":\"low\"}",
              mapper.writeValueAsString(variantWithReasoning));
        });
  }

  /** 测试意图：验证禁用字母排序保留外层与嵌套 DTO 声明顺序，且 @JsonInclude(ALWAYS) 对 environmentName 的 null 仍显式输出。 */
  @Test
  void shouldPreserveDeclarationOrderAndHonorExplicitAlwaysInclude() {
    runner.run(
        context -> {
          JsonMapper mapper = context.getBean(JsonMapper.class);

          HarnessExecutionSelectionDTO execution = new HarnessExecutionSelectionDTO();
          execution.setDriver("builtin");
          ObjectNode settingsMap = jsonObject();
          settingsMap.put("modelName", "claude-3-5-sonnet");
          settingsMap.put("variant", "default");
          execution.setSettings(settingsMap);

          HarnessBranchSettingsDTO settings = new HarnessBranchSettingsDTO();
          settings.setAgentName("code-assistant");
          settings.setExecution(execution);

          // 完整快照要求 environmentName 总是显式出现：全局 NON_NULL 必须被 @JsonInclude(ALWAYS) 覆盖。
          String expectedWithNullEnvironment =
              "{\"agentName\":\"code-assistant\",\"execution\":{"
                  + "\"driver\":\"builtin\","
                  + "\"settings\":{\"modelName\":\"claude-3-5-sonnet\",\"variant\":\"default\"}},"
                  + "\"environmentName\":null,\"goal\":null}";
          assertEquals(expectedWithNullEnvironment, mapper.writeValueAsString(settings));

          settings.setEnvironmentName("local");
          String expectedWithEnvironment =
              "{\"agentName\":\"code-assistant\",\"execution\":{"
                  + "\"driver\":\"builtin\","
                  + "\"settings\":{\"modelName\":\"claude-3-5-sonnet\",\"variant\":\"default\"}},"
                  + "\"environmentName\":\"local\",\"goal\":null}";
          assertEquals(expectedWithEnvironment, mapper.writeValueAsString(settings));

          // 往返必须保留 null 与非 null 两种形态。
          assertEquals(
              null,
              mapper
                  .readValue(expectedWithNullEnvironment, HarnessBranchSettingsDTO.class)
                  .getEnvironmentName());
          assertEquals(
              "local",
              mapper
                  .readValue(expectedWithEnvironment, HarnessBranchSettingsDTO.class)
                  .getEnvironmentName());
        });
  }

  /** Driver settings 中的 null 与键的缺失不同，嵌套对象也不能被响应 mapper 裁剪。 */
  @Test
  void preservesNullsInsideExecutionSettings() {
    runner.run(
        context -> {
          JsonMapper mapper = context.getBean(JsonMapper.class);
          ObjectNode nested = jsonObject();
          nested.putNull("optional");
          ObjectNode values = jsonObject();
          values.putNull("variant");
          values.set("nested", nested);
          HarnessExecutionSelectionDTO execution = new HarnessExecutionSelectionDTO();
          execution.setDriver("custom");
          execution.setSettings(values);
          assertEquals(
              "{\"driver\":\"custom\",\"settings\":{\"variant\":null,\"nested\":{\"optional\":null}}}",
              mapper.writeValueAsString(execution));
        });
  }

  /** SET_ENVIRONMENT 的显式 null 必须反序列化为明确 variant，并在序列化时保留 null 字段。 */
  @Test
  void shouldPreserveExplicitNullEnvironmentNameOnCommandUnion() {
    runner.run(
        context -> {
          JsonMapper mapper = context.getBean(JsonMapper.class);

          String cleared =
              "{\"type\":\"SET_ENVIRONMENT\",\"idempotencyKey\":\"id-1\",\"environmentName\":null}";
          HarnessCommandCreateDTO dto = mapper.readValue(cleared, HarnessCommandCreateDTO.class);
          HarnessCommandCreateDTO.SetEnvironmentDTO environment =
              assertInstanceOf(HarnessCommandCreateDTO.SetEnvironmentDTO.class, dto);
          assertNull(environment.environmentName());

          String selected =
              "{\"type\":\"SET_ENVIRONMENT\",\"idempotencyKey\":\"id-1\","
                  + "\"environmentName\":\"local\"}";
          HarnessCommandCreateDTO withName =
              mapper.readValue(selected, HarnessCommandCreateDTO.class);
          assertEquals(
              "local",
              assertInstanceOf(HarnessCommandCreateDTO.SetEnvironmentDTO.class, withName)
                  .environmentName());

          HarnessCommandCreateDTO absent =
              mapper.readValue(
                  "{\"type\":\"SET_AGENT\",\"idempotencyKey\":\"id-1\",\"agentName\":\"a\"}",
                  HarnessCommandCreateDTO.class);
          assertInstanceOf(HarnessCommandCreateDTO.SetAgentDTO.class, absent);
          assertEquals(
              "{\"type\":\"SET_ENVIRONMENT\",\"idempotencyKey\":\"id-1\","
                  + "\"environmentName\":null}",
              mapper.writeValueAsString(dto));
        });
  }

  /** 测试意图：验证全局默认 NON_NULL 的 content inclusion 生效，Map 中的 null 值 entry 被省略且保留顺序。 */
  @Test
  void shouldOmitNullContentInMap() {
    runner.run(
        context -> {
          JsonMapper mapper = context.getBean(JsonMapper.class);

          Map<String, Object> map = new LinkedHashMap<>();
          map.put("alpha", "val1");
          map.put("nullable", null);
          map.put("beta", 123);

          assertEquals("{\"alpha\":\"val1\",\"beta\":123}", mapper.writeValueAsString(map));
        });
  }

  /**
   * 测试意图：生产 HTTP mapper 必须保留 requestBody.equals 的 JSON scalar 精度与类型，随后 runtime config parser
   * 仍能精确解析；缺省 equals 与显式 null 必须在同一链路上保持区别。
   */
  @Test
  void shouldPreserveRequestBodyScalarPrecisionThroughHttpDtoAndRuntimeParser() {
    runner.run(
        context -> {
          JsonMapper httpMapper = context.getBean(JsonMapper.class);
          ModelRuntimeConfigParser parser =
              new ModelRuntimeConfigParser(JsonMapper.builder().build());

          String configJson =
              requestBodyConfig(
                  "{\"id\":\"long\",\"when\":{\"requestBody\":"
                      + "{\"path\":\"/long\",\"equals\":0.123456789012345678}},"
                      + "\"rates\":{\"input\":\"2\"}},"
                      + "{\"id\":\"big\",\"when\":{\"requestBody\":"
                      + "{\"path\":\"/big\",\"equals\":9007199254740993}},"
                      + "\"rates\":{\"input\":\"3\"}},"
                      + "{\"id\":\"two\",\"when\":{\"requestBody\":"
                      + "{\"path\":\"/two\",\"equals\":2.0}},"
                      + "\"rates\":{\"input\":\"4\"}},"
                      + "{\"id\":\"null\",\"when\":{\"requestBody\":"
                      + "{\"path\":\"/null\",\"equals\":null}},"
                      + "\"rates\":{\"input\":\"5\"}}");
          ModelConfigDTO dto = httpMapper.readValue(configJson, ModelConfigDTO.class);
          String encoded = parser.encode(dto);
          assertTrue(encoded.contains("\"equals\":0.123456789012345678"), encoded);
          String httpEncoded = httpMapper.writeValueAsString(dto);
          assertTrue(httpEncoded.contains("\"equals\":0.123456789012345678"), httpEncoded);

          var parsed = parser.parse(encoded);
          var overrides = parsed.pricing().overrides();
          assertEquals(
              "0.123456789012345678", overrides.get(0).when().requestBody().canonicalValue());
          assertEquals(
              ModelPricingCondition.ScalarType.NUMBER,
              overrides.get(0).when().requestBody().type());
          assertEquals("9007199254740993", overrides.get(1).when().requestBody().canonicalValue());
          assertEquals("2", overrides.get(2).when().requestBody().canonicalValue());
          assertEquals(
              ModelPricingCondition.ScalarType.NULL, overrides.get(3).when().requestBody().type());
          assertEquals("", overrides.get(3).when().requestBody().canonicalValue());

          ModelConfigDTO missingEquals =
              httpMapper.readValue(
                  requestBodyConfig(
                      "{\"id\":\"missing\",\"when\":{\"requestBody\":"
                          + "{\"path\":\"/missing\"}},\"rates\":{\"input\":\"6\"}}"),
                  ModelConfigDTO.class);
          assertThrows(IllegalArgumentException.class, () -> parser.parse(missingEquals));
        });
  }

  private static String requestBodyConfig(String overrides) {
    return "{\"limit\":{\"context\":128000,\"output\":8192},"
        + "\"abilities\":{\"tools\":true,\"reasoning\":true,"
        + "\"inputModalities\":[\"TEXT\"]},"
        + "\"pricing\":{\"base\":{\"input\":\"1\",\"output\":\"2\","
        + "\"cacheRead\":\"0\",\"cacheWrite\":\"0\","
        + "\"cacheWriteByTtl\":{},\"reasoning\":null},"
        + "\"overrides\":["
        + overrides
        + "],\"multipliers\":[]},"
        + "\"defaultVariant\":\"default\","
        + "\"variants\":[{\"id\":\"default\",\"promptCacheRetention\":\"NONE\"}]}";
  }

  /** 测试意图：验证严格重复键检测仍然生效，解析包含重复 key 的 JSON 时拒绝。 */
  @Test
  void shouldRejectDuplicateKeys() {
    runner.run(
        context -> {
          JsonMapper mapper = context.getBean(JsonMapper.class);
          String duplicateKeyJson = "{\"id\":\"1\",\"id\":\"2\"}";
          assertThrows(JacksonException.class, () -> mapper.readTree(duplicateKeyJson));
        });
  }

  /** 测试意图：验证 HTTP DTO 边界拒绝已从 Model Variant 契约删除的字段，而不是静默丢弃。 */
  @Test
  void shouldRejectUnknownAgentModelVariantFields() {
    runner.run(
        context -> {
          JsonMapper mapper =
              HttpBoundaryConfiguration.strictHttpMapper(context.getBean(JsonMapper.class));

          assertThrows(
              JacksonException.class,
              () ->
                  mapper.readValue(
                      "{\"id\":\"default\",\"temperature\":0.5}", ModelVariantDTO.class));
        });
  }

  /**
   * 测试意图：真实 Jackson3 wire mapper 下 {@code protocolOptionsJson} 只接受 JSON 字符串 token。数字/布尔/对象/数组都不是
   * String，必须被拒绝而不是被默认标量强制转换吞掉（否则声明的“保留 JSON token 实际类型”约束失效）。
   */
  @Test
  void shouldRejectNonStringProtocolOptionsJsonTokens() {
    runner.run(
        context -> {
          JsonMapper mapper =
              HttpBoundaryConfiguration.strictHttpMapper(context.getBean(JsonMapper.class));

          ModelVariantDTO accepted =
              mapper.readValue(
                  "{\"id\":\"default\",\"protocolOptionsJson\":\"{}\"}", ModelVariantDTO.class);
          assertEquals("{}", accepted.getProtocolOptionsJson());

          for (String token : List.of("123", "1.5", "true", "false", "{}", "[]")) {
            String json = "{\"id\":\"default\",\"protocolOptionsJson\":" + token + "}";
            assertThrows(
                JacksonException.class,
                () -> mapper.readValue(json, ModelVariantDTO.class),
                "non-string protocolOptionsJson token must be rejected: " + token);
          }
        });
  }

  /** 测试意图：HTTP 严格 mapper 不能把网络字段的布尔/数字转换成合法主机名，同时保留 null 与空字符串语义。 */
  @Test
  void shouldRejectNonStringBackendNetworkTokensAndUnknownFields() {
    runner.run(
        context -> {
          JsonMapper mapper =
              HttpBoundaryConfiguration.strictHttpMapper(context.getBean(JsonMapper.class));
          SystemSettingsBackendNetworkDTO direct =
              mapper.readValue(
                  "{\"proxyUrl\":null,\"noProxyHosts\":\"\"}",
                  SystemSettingsBackendNetworkDTO.class);
          assertNull(direct.getProxyUrl());
          assertEquals("", direct.getNoProxyHosts());
          SystemSettingsBackendNetworkDTO configured =
              mapper.readValue(
                  "{\"proxyUrl\":\"http://proxy:3128\",\"noProxyHosts\":\"localhost,127.*,::1\"}",
                  SystemSettingsBackendNetworkDTO.class);
          assertEquals("http://proxy:3128", configured.getProxyUrl());
          assertEquals("localhost,127.*,::1", configured.getNoProxyHosts());
          for (String field : List.of("proxyUrl", "noProxyHosts")) {
            for (String token : List.of("123", "1.5", "true", "false", "{}", "[]")) {
              assertThrows(
                  JacksonException.class,
                  () ->
                      mapper.readValue(
                          "{\"" + field + "\":" + token + "}",
                          SystemSettingsBackendNetworkDTO.class),
                  "non-string backendNetwork token must be rejected: " + field);
            }
          }
          assertThrows(
              JacksonException.class,
              () -> mapper.readValue("{\"enabled\":true}", SystemSettingsBackendNetworkDTO.class));
        });
  }

  /** 测试意图：验证 Long/long 序列化为字符串且 Date 输出为数值时间戳。 */
  @Test
  void shouldPreserveLongToStringAndDateTimestampFeatures() {
    runner.run(
        context -> {
          JsonMapper mapper = context.getBean(JsonMapper.class);

          Date timestamp = new Date(1700000000000L);
          LongAndDateSample sample =
              new LongAndDateSample(timestamp, 123456789012345678L, 987654321098765432L);

          String expected =
              "{\"timestamp\":1700000000000,\"boxedId\":\"123456789012345678\",\"primitiveId\":\"987654321098765432\"}";
          assertEquals(expected, mapper.writeValueAsString(sample));
        });
  }

  static class LongAndDateSample {
    private final Date timestamp;
    private final Long boxedId;
    private final long primitiveId;

    LongAndDateSample(Date timestamp, Long boxedId, long primitiveId) {
      this.timestamp = timestamp;
      this.boxedId = boxedId;
      this.primitiveId = primitiveId;
    }

    public Date getTimestamp() {
      return timestamp;
    }

    public Long getBoxedId() {
      return boxedId;
    }

    public long getPrimitiveId() {
      return primitiveId;
    }
  }
}
