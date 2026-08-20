package fun.fengwk.kkstudio.share.systemsettings;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * Server-owned metadata for rendering the editable system settings aggregate.
 *
 * <p>The schema is ordered deliberately: the order of sections, groups, fields and enum options is
 * the order exposed to the settings UI.
 */
@Data
public class SystemSettingsSchemaDTO {

  private List<SectionDTO> sections;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown system settings schema field: " + name);
  }

  @Data
  public static class SectionDTO {

    private String key;

    private String labelKey;

    private String descriptionKey;

    private boolean restartRequired;

    private List<GroupDTO> groups;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
      throw new IllegalArgumentException("unknown system settings schema section field: " + name);
    }
  }

  @Data
  public static class GroupDTO {

    private String key;

    private String labelKey;

    private String descriptionKey;

    private boolean restartRequired;

    private ApplyTiming applyTiming;

    private List<FieldDTO> fields;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
      throw new IllegalArgumentException("unknown system settings schema group field: " + name);
    }
  }

  @Data
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public static class FieldDTO {

    private String path;

    private String labelKey;

    private String hintKey;

    private FieldType type;

    private boolean nullable;

    private Integer min;

    private Integer max;

    private List<OptionDTO> options;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
      throw new IllegalArgumentException("unknown system settings schema field metadata: " + name);
    }
  }

  @Data
  public static class OptionDTO {

    private String value;

    private String labelKey;

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
      throw new IllegalArgumentException("unknown system settings schema option field: " + name);
    }
  }

  public enum ApplyTiming {
    NEXT_INVOCATION,
    NEXT_CHAT,
    RESTART
  }

  public enum FieldType {
    BOOLEAN,
    INTEGER,
    LONG,
    TEXT,
    ENUM,
    PERMISSION,
    MODEL_SELECTION
  }
}
