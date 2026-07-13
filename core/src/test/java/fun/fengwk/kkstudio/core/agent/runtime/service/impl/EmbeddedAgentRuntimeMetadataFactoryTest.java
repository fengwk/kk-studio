package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.agent.AgentInfo;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;

import java.util.List;

/** Runtime metadata consumes the new JSON configuration fields. */
public class EmbeddedAgentRuntimeMetadataFactoryTest {

  @Test
  public void shouldBuildRuntimeMetadataFromWorkspaceConfiguration() {
    EmbeddedAgentRuntimeMetadataFactory factory =
        new EmbeddedAgentRuntimeMetadataFactory(new ObjectMapper());
    AgentDefinition definition = new AgentDefinition();
    definition.setName("assistant");
    definition.setVariant("stable");
    definition.setConfigJson("{\"tools\":[\"browser\",\"shell\"]}");
    AgentInfo agentInfo = factory.toAgentInfo(definition, "openai", "gpt-4.1");
    assertEquals("stable", agentInfo.getDefaultVariant());
    assertEquals(List.of("browser", "shell"), agentInfo.getTools());

    AgentModel model = new AgentModel();
    model.setName("gpt-4.1");
    model.setConfigJson("{\"variants\":[{\"name\":\"stable\",\"temperature\":0.7}]}");
    ModelInfo modelInfo = factory.toModelInfo(model, "openai");
    assertEquals("default", modelInfo.getDefaultVariant());
    assertEquals("stable", modelInfo.getVariants().get(0).getName());

    AgentProvider provider = new AgentProvider();
    provider.setConfigJson("{\"timeoutMillis\":30000}");
    assertEquals(30_000L, factory.toProviderInfo(provider).getTimeout().toMillis());
  }
}
