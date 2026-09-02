package fun.fengwk.kkstudio.platform.testing;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class TestEnvironmentBindings {

  private static final EnvironmentId DEFAULT_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  private TestEnvironmentBindings() {}

  public static EnvironmentBinding binding() {
    return new EnvironmentBinding(DEFAULT_ID, ".");
  }

  public static EnvironmentBinding binding(String idOrName) {
    if (idOrName == null) {
      return null;
    }
    try {
      return new EnvironmentBinding(EnvironmentId.parse(idOrName), ".");
    } catch (IllegalArgumentException e) {
      return new EnvironmentBinding(
          EnvironmentId.of(UUID.nameUUIDFromBytes(idOrName.getBytes(StandardCharsets.UTF_8))), ".");
    }
  }

  public static EnvironmentBinding binding(EnvironmentId id) {
    return new EnvironmentBinding(id, ".");
  }
}
