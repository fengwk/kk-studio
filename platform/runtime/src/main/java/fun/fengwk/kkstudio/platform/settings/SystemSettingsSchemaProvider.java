package fun.fengwk.kkstudio.platform.settings;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO;

import java.util.List;

/**
 * The single ordered metadata source for editable system settings.
 *
 * <p>Field paths intentionally follow the record component paths and group order follows the
 * existing settings cards. Cross-field constraints remain in {@link SystemSettings}'s canonical
 * constructors; this provider only describes individual editable leaves and their controls.
 */
@Component
public class SystemSettingsSchemaProvider {

  private static final SystemSettingsSchemaDTO SCHEMA = createSchema();

  public SystemSettingsSchemaDTO get() {
    return SCHEMA;
  }

  private static SystemSettingsSchemaDTO createSchema() {
    SystemSettingsSchemaDTO schema = new SystemSettingsSchemaDTO();
    schema.setSections(
        List.of(
            AiRuntimeSettingsSchema.definition(),
            ToolSettingsSchema.definition(),
            EnvironmentSettingsSchema.definition(),
            BackendNetworkSettingsSchema.definition(),
            StorageMediaSettingsSchema.definition(),
            AdvancedSettingsSchema.definition()));
    return schema;
  }
}
