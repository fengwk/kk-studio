package fun.fengwk.kkstudio.share.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** Update request body for {@code /api/environments/{id}}. */
@Data
@EqualsAndHashCode(callSuper = true)
public class ToolEnvironmentUpdateDTO extends ToolEnvironmentEditablePropertiesDTO {}
