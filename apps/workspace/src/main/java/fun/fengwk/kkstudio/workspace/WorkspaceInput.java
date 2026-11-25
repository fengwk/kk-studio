package fun.fengwk.kkstudio.workspace;

import fun.fengwk.kkstudio.common.error.DomainException;

import java.util.UUID;

final class WorkspaceInput {
  private WorkspaceInput() {}

  static long version(String value) {
    try {
      long number = Long.parseLong(value);
      if (number >= 0 && Long.toString(number).equals(value)) {
        return number;
      }
    } catch (NumberFormatException ex) {
      throw invalid("Expected a nonnegative version");
    }
    throw invalid("Expected a nonnegative version");
  }

  static UUID uuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (parsed.toString().equals(value)) {
        return parsed;
      }
    } catch (IllegalArgumentException ex) {
      throw invalid("Expected a canonical UUID");
    }
    throw invalid("Expected a canonical UUID");
  }

  static <T> T required(T value, String field) {
    if (value == null) {
      throw invalid(field + " is required");
    }
    return value;
  }

  static DomainException invalid(String message) {
    return DomainException.validationReason("INVALID_ARGUMENT", message);
  }
}
