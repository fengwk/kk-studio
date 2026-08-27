package fun.fengwk.kkstudio.platform.catalog.definition.configuration;

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
    config.setToolIds(List.of("base.read"));
    config.setSkills(List.of("dev"));
    config.setSubagents(List.of("reviewer"));

    AgentDefinitionConfigDTO decoded = codec.decode(codec.encode(config));

    assertEquals(List.of("base.read"), decoded.getToolIds());
    assertEquals(List.of("dev"), decoded.getSkills());
    assertEquals(List.of("reviewer"), decoded.getSubagents());
  }

  @Test
  void rejectsIncompleteAndNonCanonicalTypedConfigs() {
    assertThrows(IllegalArgumentException.class, () -> codec.encode(null));

    AgentDefinitionConfigDTO missingToolIds = config();
    missingToolIds.setToolIds(null);
    assertThrows(IllegalArgumentException.class, () -> codec.encode(missingToolIds));

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
    whitespace.setToolIds(List.of(" base.read "));
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
        () -> codec.decode("{\"toolIds\":[],\"skills\":[],\"subagents\":[],\"unknown\":true}"));
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode("{\"tools\":[],\"skills\":[],\"subagents\":[]}"));
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode("{\"toolIds\":[42],\"skills\":[],\"subagents\":[]}"));
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode("{\"toolIds\":[],\"skills\":[],\"subagents\":[]} {}"));
  }

  private static AgentDefinitionConfigDTO config() {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setToolIds(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    return config;
  }
}
