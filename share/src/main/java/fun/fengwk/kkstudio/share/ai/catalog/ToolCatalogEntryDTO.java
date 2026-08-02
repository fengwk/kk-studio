package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

/** Offline-selectable tool catalog entry. */
@Data
public class ToolCatalogEntryDTO {
  private String name;
  private String version;
  private String type;
  private String description;
}
