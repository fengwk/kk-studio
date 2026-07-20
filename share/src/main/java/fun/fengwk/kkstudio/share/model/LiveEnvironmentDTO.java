package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.time.Instant;
import java.util.List;

/** Compact read model for one server-memory live Environment registry entry. */
@Data
public class LiveEnvironmentDTO {
  private String name;
  private String status;
  private Instant lastSeen;
  private List<LiveEnvironmentToolDTO> tools;
  private List<LiveEnvironmentSkillDTO> skills;
}
