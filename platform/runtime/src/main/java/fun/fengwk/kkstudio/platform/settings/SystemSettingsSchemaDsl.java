package fun.fengwk.kkstudio.platform.settings;

import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO.FieldType;

import java.util.List;

final class SystemSettingsSchemaDsl {

  private SystemSettingsSchemaDsl() {}

  static SystemSettingsSchemaDTO.SectionDTO section(
      String key,
      String labelKey,
      String descriptionKey,
      SystemSettingsSchemaDTO.GroupDTO... groups) {
    SystemSettingsSchemaDTO.SectionDTO section = new SystemSettingsSchemaDTO.SectionDTO();
    section.setKey(key);
    section.setLabelKey(labelKey);
    section.setDescriptionKey(descriptionKey);
    section.setGroups(List.of(groups));
    return section;
  }

  static SystemSettingsSchemaDTO.GroupDTO group(
      String key,
      String labelKey,
      String descriptionKey,
      SystemSettingsSchemaDTO.FieldDTO... fields) {
    SystemSettingsSchemaDTO.GroupDTO group = new SystemSettingsSchemaDTO.GroupDTO();
    group.setKey(key);
    group.setLabelKey(labelKey);
    group.setDescriptionKey(descriptionKey);
    group.setFields(List.of(fields));
    return group;
  }

  static SystemSettingsSchemaDTO.FieldDTO field(
      String path,
      String labelKey,
      String hintKey,
      FieldType type,
      boolean nullable,
      Integer min,
      Integer max,
      SystemSettingsSchemaDTO.OptionDTO... options) {
    SystemSettingsSchemaDTO.FieldDTO field = new SystemSettingsSchemaDTO.FieldDTO();
    field.setPath(path);
    field.setLabelKey(labelKey);
    field.setHintKey(hintKey != null ? hintKey : labelKey + ".hint");
    field.setType(type);
    field.setNullable(nullable);
    field.setMin(min);
    field.setMax(max);
    field.setOptions(options.length == 0 ? null : List.of(options));
    return field;
  }

  static SystemSettingsSchemaDTO.OptionDTO option(String value, String labelKey) {
    SystemSettingsSchemaDTO.OptionDTO option = new SystemSettingsSchemaDTO.OptionDTO();
    option.setValue(value);
    option.setLabelKey(labelKey);
    return option;
  }
}
