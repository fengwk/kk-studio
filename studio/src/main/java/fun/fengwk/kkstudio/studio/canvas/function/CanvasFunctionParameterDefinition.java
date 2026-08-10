package fun.fengwk.kkstudio.studio.canvas.function;

import java.util.List;
import java.util.Objects;

/** 一个公开、可校验的 Canvas Function 参数定义。 */
public record CanvasFunctionParameterDefinition(
    String key,
    String label,
    CanvasFunctionParameterType type,
    boolean required,
    Object defaultValue,
    List<String> options,
    Integer min,
    Integer max) {

  public CanvasFunctionParameterDefinition {
    requireToken(key, "key");
    requireText(label, "label");
    Objects.requireNonNull(type, "type");
    options = options == null ? List.of() : List.copyOf(options);
    switch (type) {
      case ENUM -> validateEnum(defaultValue, options, min, max);
      case INTEGER -> validateInteger(defaultValue, options, min, max);
    }
  }

  public static CanvasFunctionParameterDefinition enumParameter(
      String key, String label, boolean required, String defaultValue, List<String> options) {
    return new CanvasFunctionParameterDefinition(
        key, label, CanvasFunctionParameterType.ENUM, required, defaultValue, options, null, null);
  }

  public static CanvasFunctionParameterDefinition integerParameter(
      String key, String label, boolean required, Integer defaultValue, int min, int max) {
    return new CanvasFunctionParameterDefinition(
        key,
        label,
        CanvasFunctionParameterType.INTEGER,
        required,
        defaultValue,
        List.of(),
        min,
        max);
  }

  private static void validateEnum(
      Object defaultValue, List<String> options, Integer min, Integer max) {
    if (options.isEmpty()) {
      throw new IllegalArgumentException("ENUM options must not be empty");
    }
    if (options.stream().anyMatch(option -> option == null || option.isBlank())) {
      throw new IllegalArgumentException("ENUM options must be non-blank strings");
    }
    if (options.stream().distinct().count() != options.size()) {
      throw new IllegalArgumentException("ENUM options must not contain duplicates");
    }
    if (min != null || max != null) {
      throw new IllegalArgumentException("ENUM must not declare min/max");
    }
    if (defaultValue != null
        && (!(defaultValue instanceof String value) || !options.contains(value))) {
      throw new IllegalArgumentException("ENUM defaultValue must be one of options");
    }
  }

  private static void validateInteger(
      Object defaultValue, List<String> options, Integer min, Integer max) {
    if (!options.isEmpty()) {
      throw new IllegalArgumentException("INTEGER must not declare options");
    }
    if (min == null || max == null || min > max) {
      throw new IllegalArgumentException("INTEGER min/max are required and must be ordered");
    }
    if (defaultValue != null) {
      if (!(defaultValue instanceof Integer value) || value < min || value > max) {
        throw new IllegalArgumentException("INTEGER defaultValue must be within min/max");
      }
    }
  }

  private static void requireToken(String value, String field) {
    requireText(value, field);
    if (!value.matches("[a-z][a-zA-Z0-9]*")) {
      throw new IllegalArgumentException(field + " must be a lower camel-case token");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank() || !value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must be non-blank without surrounding space");
    }
  }
}
