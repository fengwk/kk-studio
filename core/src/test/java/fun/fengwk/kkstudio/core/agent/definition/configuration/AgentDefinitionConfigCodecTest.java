package fun.fengwk.kkstudio.core.agent.definition.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentExecutionPolicyDTO;

import java.util.List;

class AgentDefinitionConfigCodecTest {

  private final AgentDefinitionConfigCodec codec =
      new AgentDefinitionConfigCodec(new ObjectMapper());

  @Test
  void roundTripsCompleteConfig() {
    AgentDefinitionConfigDTO config = config();
    config.setEnvironmentName("local");
    config.setTools(List.of("read"));
    config.setSkills(List.of("dev"));
    config.setAllowedSubagents(List.of("helper"));
    config.getExecutionPolicy().setMaxTurns(10);

    AgentDefinitionConfigDTO decoded = codec.decode(codec.encode(config));

    assertEquals("local", decoded.getEnvironmentName());
    assertEquals(List.of("read"), decoded.getTools());
    assertEquals(List.of("dev"), decoded.getSkills());
    assertEquals(List.of("helper"), decoded.getAllowedSubagents());
    assertEquals(10, decoded.getExecutionPolicy().getMaxTurns());
  }

  @Test
  void rejectsIncompleteAndNonCanonicalTypedConfigs() {
    assertThrows(IllegalArgumentException.class, () -> codec.encode(null));

    AgentDefinitionConfigDTO missingTools = config();
    missingTools.setTools(null);
    assertThrows(IllegalArgumentException.class, () -> codec.encode(missingTools));

    AgentDefinitionConfigDTO missingPolicy = config();
    missingPolicy.setExecutionPolicy(null);
    assertThrows(IllegalArgumentException.class, () -> codec.encode(missingPolicy));

    AgentDefinitionConfigDTO duplicate = config();
    duplicate.setSkills(List.of("dev", "dev"));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(duplicate));

    AgentDefinitionConfigDTO whitespace = config();
    whitespace.setAllowedSubagents(List.of(" helper "));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(whitespace));

    AgentDefinitionConfigDTO invalidPolicy = config();
    invalidPolicy.getExecutionPolicy().setMaxDepth(0);
    assertThrows(IllegalArgumentException.class, () -> codec.encode(invalidPolicy));
  }

  @Test
  void rejectsMalformedUnknownCoercedAndTrailingJson() {
    assertThrows(IllegalStateException.class, () -> codec.decode(null));
    assertThrows(IllegalStateException.class, () -> codec.decode(" "));
    assertThrows(IllegalStateException.class, () -> codec.decode("null"));
    assertThrows(IllegalStateException.class, () -> codec.decode("{}"));
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.decode(
                "{\"tools\":[],\"skills\":[],\"allowedSubagents\":[],"
                    + "\"executionPolicy\":{},\"unknown\":true}"));
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.decode(
                "{\"tools\":[],\"skills\":[],\"allowedSubagents\":[],"
                    + "\"executionPolicy\":{\"maxTurns\":\"10\"}}"));
    assertThrows(
        IllegalStateException.class,
        () ->
            codec.decode(
                "{\"tools\":[],\"skills\":[],\"allowedSubagents\":[],"
                    + "\"executionPolicy\":{}} {}"));
  }

  private static AgentDefinitionConfigDTO config() {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setAllowedSubagents(List.of());
    config.setExecutionPolicy(new AgentExecutionPolicyDTO());
    return config;
  }
}
