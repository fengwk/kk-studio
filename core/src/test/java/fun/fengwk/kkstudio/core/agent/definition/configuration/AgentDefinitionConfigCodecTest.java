package fun.fengwk.kkstudio.core.agent.definition.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;

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

    AgentDefinitionConfigDTO decoded = codec.decode(codec.encode(config));

    assertEquals("local", decoded.getEnvironmentName());
    assertEquals(List.of("read"), decoded.getTools());
    assertEquals(List.of("dev"), decoded.getSkills());
  }

  @Test
  void roundTripsNullEnvironmentName() {
    AgentDefinitionConfigDTO config = config();
    config.setEnvironmentName(null);
    String json = codec.encode(config);
    // Project's Jackson convention writes nulls explicitly; round-trip preserves the value.
    assertNull(codec.decode(json).getEnvironmentName());
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

    AgentDefinitionConfigDTO blankEnv = config();
    blankEnv.setEnvironmentName(" ");
    assertThrows(IllegalArgumentException.class, () -> codec.encode(blankEnv));

    AgentDefinitionConfigDTO whitespaceEnv = config();
    whitespaceEnv.setEnvironmentName(" local ");
    assertThrows(IllegalArgumentException.class, () -> codec.encode(whitespaceEnv));

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
        () -> codec.decode("{\"tools\":[],\"skills\":[],\"unknown\":true}"));
    assertThrows(
        IllegalStateException.class,
        () -> codec.decode("{\"tools\":[],\"skills\":[],\"environmentName\":42}"));
    assertThrows(
        IllegalStateException.class, () -> codec.decode("{\"tools\":[],\"skills\":[]} {}"));
  }

  private static AgentDefinitionConfigDTO config() {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    return config;
  }
}
