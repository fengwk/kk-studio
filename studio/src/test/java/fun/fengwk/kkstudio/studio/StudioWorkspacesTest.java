package fun.fengwk.kkstudio.studio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class StudioWorkspacesTest {

  @Test
  void acceptsOnlyDefaultWorkspace() {
    assertDoesNotThrow(() -> StudioWorkspaces.requireDefault(StudioWorkspaces.DEFAULT_ID));
    assertThrows(IllegalArgumentException.class, () -> StudioWorkspaces.requireDefault(2L));
  }
}
