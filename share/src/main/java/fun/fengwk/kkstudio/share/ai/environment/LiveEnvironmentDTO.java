package fun.fengwk.kkstudio.share.ai.environment;

import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Compact read model for one server-memory live Environment registry entry.
 *
 * <p>{@code id} is the canonical durable route identity (lowercase UUID); {@code name} is a
 * display-only label that may be reused across different ids.
 */
@Data
public class LiveEnvironmentDTO {
  private String id;
  private String name;
  private String status;
  private Instant lastSeen;
  private List<LiveEnvironmentToolDTO> tools;
  private List<LiveEnvironmentSkillDTO> skills;
}
