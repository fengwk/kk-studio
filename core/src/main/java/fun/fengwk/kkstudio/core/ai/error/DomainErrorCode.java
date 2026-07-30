package fun.fengwk.kkstudio.core.ai.error;

/**
 * Stable machine-readable error codes for AI catalog domain failures.
 *
 * <p>The codes are part of the HTTP contract: clients use them to branch on retry/refresh behavior
 * without parsing messages. New codes may be added; existing codes are frozen.
 */
public enum DomainErrorCode {

  /** Request body or parameter failed validation. */
  VALIDATION("validation"),

  /** Resource referenced by id does not exist. */
  RESOURCE_NOT_FOUND("resource_not_found"),

  /** Update/delete CAS lost the optimistic-version race; client must refresh. */
  VERSION_CONFLICT("version_conflict"),

  /** Unique name / provider+name conflict (detected as race after create/update). */
  DUPLICATE("duplicate"),

  /** Resource cannot be removed because it is still referenced. */
  IN_USE("in_use");

  private final String code;

  DomainErrorCode(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}
