package fun.fengwk.kkstudio.platform.testing;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 测试用 Environment 身份生成器。 */
public final class TestEnvironments {

  private static final EnvironmentId DEFAULT_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  private TestEnvironments() {}

  public static EnvironmentId environmentId() {
    return DEFAULT_ID;
  }

  public static EnvironmentId environmentId(String idOrName) {
    if (idOrName == null) {
      return null;
    }
    try {
      return EnvironmentId.parse(idOrName);
    } catch (IllegalArgumentException e) {
      return EnvironmentId.of(UUID.nameUUIDFromBytes(idOrName.getBytes(StandardCharsets.UTF_8)));
    }
  }

  public static EnvironmentId environmentId(EnvironmentId id) {
    return id;
  }
}
