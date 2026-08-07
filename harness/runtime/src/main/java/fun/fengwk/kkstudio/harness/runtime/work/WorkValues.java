package fun.fengwk.kkstudio.harness.runtime.work;

/** canonical lease token 值的小型 package-private 工具类。 */
final class WorkValues {

  private WorkValues() {}

  static String requireCanonicalToken(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("lease token must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException("lease token must not contain surrounding whitespace");
    }
    if (value.length() > 128) {
      throw new IllegalArgumentException("lease token must be <= 128 characters");
    }
    return value;
  }
}
