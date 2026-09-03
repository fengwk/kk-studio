package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessBranchSettingsDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;

import java.util.Date;

/**
 * 验证由 Spring Boot 管理并经过 {@link StrictJacksonConfiguration} 定制的 {@link JsonMapper} 的行为：
 *
 * <ul>
 *   <li>默认全局省略 null 字段；
 *   <li>默认保持 DTO 字段声明顺序（禁用字母排序）；
 *   <li>对显式声明 @JsonInclude(ALWAYS) 的字段在 null 时仍输出；
 *   <li>保留严格重复键检测；
 *   <li>保留 long 转 string 与 long 时间戳行为。
 * </ul>
 */
public class StrictJacksonConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
          .withUserConfiguration(StrictJacksonConfiguration.class);

  /**
   * 测试意图：验证 AgentModelVariantDTO 在可选字段为 null 时，全局默认 NON_NULL 策略会自动省略所有 null 字段， 仅序列化非 null 字段；当非
   * null 可选字段存在时，序列化严格保持字段声明顺序（id 在前，reasoningEffort 在后）。
   */
  @Test
  public void shouldOmitNullFieldsAndPreserveDeclarationOrderForAgentModelVariant() {
    runner.run(
        context -> {
          JsonMapper mapper = context.getBean(JsonMapper.class);

          // 1. 仅设置必填 id，所有可选字段均为 null，输出应精确为 {"id":"standard"}
          AgentModelVariantDTO variantOnlyId = new AgentModelVariantDTO();
          variantOnlyId.setId("standard");
          assertEquals("{\"id\":\"standard\"}", mapper.writeValueAsString(variantOnlyId));

          // 2. 设置 reasoningEffort，其他可选字段保持 null；输出应精确为 {"id":"standard","reasoningEffort":"low"}
          AgentModelVariantDTO variantWithReasoning = new AgentModelVariantDTO();
          variantWithReasoning.setId("standard");
          variantWithReasoning.setReasoningEffort("low");
          assertEquals(
              "{\"id\":\"standard\",\"reasoningEffort\":\"low\"}",
              mapper.writeValueAsString(variantWithReasoning));
        });
  }

  /**
   * 测试意图：验证禁用 SORT_PROPERTIES_ALPHABETICALLY 保证外层 DTO（workspacePath -> agentName -> model） 与嵌套
   * DTO（providerName -> modelName -> variant）均严格保持 Java 类中声明的字段顺序（而非字母序）， 且 workspacePath
   * 上的 @JsonInclude(ALWAYS) 确保在全局 NON_NULL 下为 null 时仍显式输出 "workspacePath":null。
   */
  @Test
  public void shouldPreserveDeclarationOrderAndHonorExplicitAlwaysInclude() {
    runner.run(
        context -> {
          JsonMapper mapper = context.getBean(JsonMapper.class);

          HarnessModelSelectionDTO model = new HarnessModelSelectionDTO();
          model.setProviderName("anthropic");
          model.setModelName("claude-3-5-sonnet");
          model.setVariant("default");

          // 1. workspacePath 为 null，但标注了 @JsonInclude(ALWAYS)，必须显式输出；
          // 顺序保持声明顺序：workspacePath -> agentName -> model，
          // model 嵌套顺序保持声明顺序：providerName -> modelName -> variant
          HarnessBranchSettingsDTO settingsNullWorkspace = new HarnessBranchSettingsDTO();
          settingsNullWorkspace.setWorkspacePath(null);
          settingsNullWorkspace.setAgentName("code-assistant");
          settingsNullWorkspace.setModel(model);

          String expectedNullWorkspace =
              "{\"workspacePath\":null,\"agentName\":\"code-assistant\",\"model\":{"
                  + "\"providerName\":\"anthropic\","
                  + "\"modelName\":\"claude-3-5-sonnet\","
                  + "\"variant\":\"default\"}}";
          assertEquals(expectedNullWorkspace, mapper.writeValueAsString(settingsNullWorkspace));

          // 2. workspacePath 为具体路径时同样保持声明顺序
          HarnessBranchSettingsDTO settingsWithPath = new HarnessBranchSettingsDTO();
          settingsWithPath.setWorkspacePath("/workspace/project");
          settingsWithPath.setAgentName("code-assistant");
          settingsWithPath.setModel(model);

          String expectedWithPath =
              "{\"workspacePath\":\"/workspace/project\",\"agentName\":\"code-assistant\",\"model\":{"
                  + "\"providerName\":\"anthropic\","
                  + "\"modelName\":\"claude-3-5-sonnet\","
                  + "\"variant\":\"default\"}}";
          assertEquals(expectedWithPath, mapper.writeValueAsString(settingsWithPath));
        });
  }

  /**
   * 测试意图：验证 StrictJacksonConfiguration 保留了 StreamReadFeature.STRICT_DUPLICATE_DETECTION， 解析含有重复键的
   * JSON 时会抛出异常，防止重复键混淆攻击或不规范输入。
   */
  @Test
  public void shouldRejectDuplicateKeys() {
    runner.run(
        context -> {
          JsonMapper mapper = context.getBean(JsonMapper.class);
          String duplicateKeyJson = "{\"id\":\"1\",\"id\":\"2\"}";
          assertThrows(JacksonException.class, () -> mapper.readTree(duplicateKeyJson));
        });
  }

  /**
   * 测试意图：验证 StrictJacksonConfiguration 保留了 LongToStringModule 以及
   * DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS， 确保 Long/long 字段序列化为 JSON 字符串（防止前端 64
   * 位整数精度丢失），且日期输出为数值时间戳。
   */
  @Test
  public void shouldPreserveLongToStringAndDateTimestampFeatures() {
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

    public LongAndDateSample(Date timestamp, Long boxedId, long primitiveId) {
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
