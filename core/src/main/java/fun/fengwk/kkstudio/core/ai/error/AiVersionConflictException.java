package fun.fengwk.kkstudio.core.ai.error;

/** Update/delete CAS lost the optimistic-version race; mapped to HTTP 409. */
public class AiVersionConflictException extends AiDomainException {

  private final String expectedVersion;
  private final String actualVersion;

  public AiVersionConflictException(
      String resource, String id, String expectedVersion, String actualVersion) {
    super(
        DomainErrorCode.VERSION_CONFLICT,
        resource,
        resource
            + " version conflict: expected="
            + expectedVersion
            + " actual="
            + actualVersion
            + " id="
            + id);
    this.expectedVersion = expectedVersion;
    this.actualVersion = actualVersion;
  }

  public String expectedVersion() {
    return expectedVersion;
  }

  public String actualVersion() {
    return actualVersion;
  }
}
