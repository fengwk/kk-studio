package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** Generic response command; both values are decimal/raw-JSON strings to avoid lossy coercion. */
@Data
public class InteractionResponseDTO {
  private String expectedVersion;
  private String responseJson;
}
