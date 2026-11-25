package fun.fengwk.kkstudio.workspace.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.UUID;

/** Omitted defaults stay unchanged; explicit null clears them. */
@JsonDeserialize(builder = ChatSpacePatchDTO.Builder.class)
public record ChatSpacePatchDTO(
    String expectedVersion,
    @Nullable String title,
    @Nullable Boolean archived,
    @Nullable Boolean defaultYoloEnabled,
    @Nullable String defaultAgentName,
    @Nullable UUID defaultEnvironmentId,
    @JsonProperty(access = JsonProperty.Access.READ_ONLY) boolean hasDefaultAgentName,
    @JsonProperty(access = JsonProperty.Access.READ_ONLY) boolean hasDefaultEnvironmentId) {
  @JsonIgnore
  public boolean hasDefaultAgentName() {
    return hasDefaultAgentName;
  }

  @JsonIgnore
  public boolean hasDefaultEnvironmentId() {
    return hasDefaultEnvironmentId;
  }

  @JsonPOJOBuilder
  static final class Builder {
    @JsonProperty private String expectedVersion;
    @JsonProperty private String title;
    @JsonProperty private Boolean archived;
    @JsonProperty private Boolean defaultYoloEnabled;
    private String agent;
    private UUID environment;
    private boolean hasAgent;
    private boolean hasEnvironment;

    @JsonSetter("defaultAgentName")
    void agent(@Nullable String value) {
      agent = value;
      hasAgent = true;
    }

    @JsonSetter("defaultEnvironmentId")
    void environment(@Nullable UUID value) {
      environment = value;
      hasEnvironment = true;
    }

    public ChatSpacePatchDTO build() {
      return new ChatSpacePatchDTO(
          expectedVersion,
          title,
          archived,
          defaultYoloEnabled,
          agent,
          environment,
          hasAgent,
          hasEnvironment);
    }
  }
}
