package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessBranchSettingsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/** 验证 Spring Boot 管理并经过 {@link StrictJacksonConfiguration} 定制的 {@link JsonMapper} wire 行为。 */
class StrictJacksonConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
          .withUserConfiguration(StrictJacksonConfiguration.class);

  /** 测试意图：验证可选字段为 null 时默认省略，且存在非 null 可选字段时按 DTO 声明顺序输出。 */
  @Test
  void shouldOmitNullFieldsAndPreserveDeclarationOrderForAgentModelVariant() {
    runner.run(
        context -> {
          JsonMapper mapper = context.getBean(JsonMapper.class);

          AgentModelVariantDTO variantOnlyId = new AgentModelVariantDTO();
          variantOnlyId.setId("standard");
          assertEquals("{\"id\":\"standard\"}", mapper.writeValueAsString(variantOnlyId));

          AgentModelVariantDTO variantWithReasoning = new AgentModelVariantDTO();
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

          HarnessModelSelectionDTO model = new HarnessModelSelectionDTO();
          model.setProviderName("anthropic");
          model.setModelName("claude-3-5-sonnet");
          model.setVariant("default");

          HarnessBranchSettingsDTO settings = new HarnessBranchSettingsDTO();
          settings.setAgentName("code-assistant");
          settings.setModel(model);

          // 完整快照要求 environmentName 总是显式出现：全局 NON_NULL 必须被 @JsonInclude(ALWAYS) 覆盖。
          String expectedWithNullEnvironment =
              "{\"agentName\":\"code-assistant\",\"model\":{"
                  + "\"providerName\":\"anthropic\","
                  + "\"modelName\":\"claude-3-5-sonnet\","
                  + "\"variant\":\"default\"},"
                  + "\"environmentName\":null,\"goal\":null}";
          assertEquals(expectedWithNullEnvironment, mapper.writeValueAsString(settings));

          settings.setEnvironmentName("local");
          String expectedWithEnvironment =
              "{\"agentName\":\"code-assistant\",\"model\":{"
                  + "\"providerName\":\"anthropic\","
                  + "\"modelName\":\"claude-3-5-sonnet\","
                  + "\"variant\":\"default\"},"
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

          assertThrows(
              JacksonException.class,
              () ->
                  mapper.readValue(
                      "{\"workspacePath\":\"/workspace/project\",\"agentName\":\"code-assistant\","
                          + "\"model\":{\"providerName\":\"anthropic\",\"modelName\":"
                          + "\"claude-3-5-sonnet\",\"variant\":\"default\"},"
                          + "\"environmentName\":null}",
                      HarnessBranchSettingsDTO.class));
        });
  }

  /** 测试意图：SET_ENVIRONMENT 命令的显式 null 必须在 wire 上保留，且请求字段出现性可被精确判定。 */
  @Test
  void shouldPreserveExplicitNullEnvironmentNameOnCommandDto() {
    runner.run(
        context -> {
          JsonMapper mapper = context.getBean(JsonMapper.class);

          String cleared =
              "{\"type\":\"SET_ENVIRONMENT\",\"idempotencyKey\":\"id-1\",\"environmentName\":null}";
          HarnessCommandCreateDTO dto = mapper.readValue(cleared, HarnessCommandCreateDTO.class);
          assertTrue(dto.hasEnvironmentNameField());
          assertNull(dto.getEnvironmentName());

          String selected =
              "{\"type\":\"SET_ENVIRONMENT\",\"idempotencyKey\":\"id-1\","
                  + "\"environmentName\":\"local\"}";
          HarnessCommandCreateDTO withName =
              mapper.readValue(selected, HarnessCommandCreateDTO.class);
          assertTrue(withName.hasEnvironmentNameField());
          assertEquals("local", withName.getEnvironmentName());

          HarnessCommandCreateDTO absent =
              mapper.readValue(
                  "{\"type\":\"SET_AGENT\",\"idempotencyKey\":\"id-1\",\"agentName\":\"a\"}",
                  HarnessCommandCreateDTO.class);
          assertFalse(absent.hasEnvironmentNameField());
          // 序列化仍按声明顺序输出 text；SET_ENVIRONMENT 的请求形态不允许携带它，因此只断言输出形态。
          assertEquals(
              "{\"type\":\"SET_ENVIRONMENT\",\"idempotencyKey\":\"id-1\","
                  + "\"environmentName\":null,\"text\":null}",
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
          JsonMapper mapper = context.getBean(JsonMapper.class);

          assertThrows(
              JacksonException.class,
              () ->
                  mapper.readValue(
                      "{\"id\":\"default\",\"temperature\":0.5}", AgentModelVariantDTO.class));
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
