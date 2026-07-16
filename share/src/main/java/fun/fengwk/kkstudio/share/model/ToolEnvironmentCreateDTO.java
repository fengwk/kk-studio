package fun.fengwk.kkstudio.share.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** Create request body for {@code /api/environments}. */
@Data
@EqualsAndHashCode(callSuper = true)
public class ToolEnvironmentCreateDTO extends ToolEnvironmentEditablePropertiesDTO {}
