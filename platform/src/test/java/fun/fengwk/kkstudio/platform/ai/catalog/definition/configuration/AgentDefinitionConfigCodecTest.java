package fun.fengwk.kkstudio.platform.ai.catalog.definition.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.util.List;

class AgentDefinitionConfigCodecTest {

  private final AgentDefinitionConfigCodec codec =
      new AgentDefinitionConfigCodec(new ObjectMapper());

  @Test
  void roundTripsCompleteConfig() {
    AgentDefinitionConfigDTO config = config();
    config.setTools(List.of("read"));
    config.setSkills(List.of("dev"));
    config.setSubagents(List.of("reviewer"));

    AgentDefinitionConfigDTO decoded = codec.decode(codec.encode(config));

    assertEquals(List.of("read"), decoded.getTools());
    assertEquals(List.of("dev"), decoded.getSkills());
    assertEquals(List.of("reviewer"), decoded.getSubagents());
  }

  @Test
  void rejectsIncompleteAndNonCanonicalTypedConfigs() {
    assertThrows(IllegalArgumentException.class, () -> codec.encode(null));

    AgentDefinitionConfigDTO missingTools = config();
    missingTools.setTools(null);
    assertThrows(IllegalArgumentException.class, () -> codec.encode(missingTools));

    AgentDefinitionConfigDTO missingSkills = config();
    missingSkills.setSkills(null);
    assertThrows(IllegalArgumentException.class, () -> codec.encode(missingSkills));

    AgentDefinitionConfigDTO missingSubagents = config();
    missingSubagents.setSubagents(null);
    assertThrows(IllegalArgumentException.class, () -> codec.encode(missingSubagents));

    AgentDefinitionConfigDTO duplicate = config();
    duplicate.setSkills(List.of("dev", "dev"));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(duplicate));

    AgentDefinitionConfigDTO whitespace = config();
    whitespace.setTools(List.of(" helper "));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(whitespace));
  }

  @Test
  void rejectsMalformedUnknownCoercedAndTrailingJson() {
    assertThrows(IllegalStateException.class, () -> codec.decode(null));
    assertThrows(IllegalStateException.class, () -> codec.decode(" "));
    assertThrows(IllegalStateException.class, () -> codec.decode("null"));
    assertThrows(IllegalStateException.class, () -> codec.decode("{}"));
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode("{\"tools\":[],\"skills\":[],\"subagents\":[],\"unknown\":true}"));
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode("{\"tools\":[42],\"skills\":[],\"subagents\":[]}"));
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode("{\"tools\":[],\"skills\":[],\"subagents\":[]} {}"));
  }

  private static AgentDefinitionConfigDTO config() {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    return config;
  }
}
