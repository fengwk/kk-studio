package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.agent.AgentInfo;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.agent.AgentInfo;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * EmbeddedAgentRuntimeMetadataFactory 的聚焦行为测试。
 *
 * @author fengwk
 */
public class EmbeddedAgentRuntimeMetadataFactoryTest {

  /** 校验会从 agent 定义里补默认 variant，并解析字符串列表字段。 */
  @Test
  public void shouldBuildAgentInfoWithParsedListsAndDefaultVariant() {
    EmbeddedAgentRuntimeMetadataFactory factory = newFactory();
    AgentDefinition agentDefinition = new AgentDefinition();
    agentDefinition.setName("assistant-a");
    agentDefinition.setSystemPrompt("you are helpful");
    agentDefinition.setDefaultVariant(" ");
    agentDefinition.setToolsJson("[\"browser\", \" \", \"shell\"]");
    agentDefinition.setSubagentsJson("{}");
    agentDefinition.setSkillsJson("[\"writer\"]");

    AgentInfo agentInfo = factory.toAgentInfo(agentDefinition, "openai", "gpt-4.1");

    assertEquals("assistant-a", agentInfo.getName());
    assertEquals("openai", agentInfo.getDefaultProvider());
    assertEquals("gpt-4.1", agentInfo.getDefaultModel());
    assertEquals("default", agentInfo.getDefaultVariant());
    assertEquals(List.of("browser", "shell"), agentInfo.getTools());
    assertTrue(agentInfo.getSubagents().isEmpty());
    assertEquals(List.of("writer"), agentInfo.getSkills());
  }

  /** 校验会从 model variantsJson 里恢复 variant 细节与 providerOptions。 */
  @Test
  public void shouldBuildModelInfoWithParsedVariants() {
    EmbeddedAgentRuntimeMetadataFactory factory = newFactory();
    AgentModel agentModel = new AgentModel();
    agentModel.setName("gpt-4.1");
    agentModel.setDefaultVariant("stable");
    agentModel.setVariantsJson(
        """
        [
          {
            "name": "stable",
            "maxOutputTokens": 4096,
            "temperature": 0.7,
            "stopSequences": ["END"],
            "providerOptions": {"mode": "json"}
          }
        ]
        """);

    ModelInfo modelInfo = factory.toModelInfo(agentModel, "openai");

    assertEquals("openai", modelInfo.getProvider());
    assertEquals("gpt-4.1", modelInfo.getName());
    assertEquals("stable", modelInfo.getDefaultVariant());
    assertEquals(1, modelInfo.getVariants().size());
    Variant variant = modelInfo.getVariants().get(0);
    assertEquals("stable", variant.getName());
    assertEquals(4096, variant.getMaxOutputTokens());
    assertEquals(0.7D, variant.getTemperature());
    assertEquals(List.of("END"), variant.getStopSequences());
    assertEquals(Map.of("mode", "json"), variant.getProviderOptions());
  }

  /** 校验 provider/model 解析遇到非法配置时会明确失败，而不是静默生成错误 metadata。 */
  @Test
  public void shouldRejectInvalidRuntimeMetadata() {
    EmbeddedAgentRuntimeMetadataFactory factory = newFactory();
    AgentProvider provider = new AgentProvider();
    provider.setProviderType(ProviderType.openai);
    provider.setTimeout(Duration.ofSeconds(30));

    assertNotNull(factory.toProviderInfo(provider));
    assertThrows(
        IllegalArgumentException.class, () -> new EmbeddedAgentRuntimeMetadataFactory(null));
    assertThrows(IllegalArgumentException.class, () -> factory.toProviderInfo(null));
    assertThrows(
        IllegalArgumentException.class, () -> factory.toAgentInfo(null, "openai", "gpt-4.1"));
    assertThrows(IllegalArgumentException.class, () -> factory.toModelInfo(null, "openai"));

    AgentModel invalidModel = new AgentModel();
    invalidModel.setName("gpt-4.1");
    invalidModel.setVariantsJson("{}");
    assertThrows(IllegalArgumentException.class, () -> factory.toModelInfo(invalidModel, "openai"));

    AgentDefinition invalidAgent = new AgentDefinition();
    invalidAgent.setName("assistant-a");
    invalidAgent.setToolsJson("bad-json");
    assertThrows(
        IllegalArgumentException.class,
        () -> factory.toAgentInfo(invalidAgent, "openai", "gpt-4.1"));
  }

  private static EmbeddedAgentRuntimeMetadataFactory newFactory() {
    return new EmbeddedAgentRuntimeMetadataFactory(new ObjectMapper());
  }
}
