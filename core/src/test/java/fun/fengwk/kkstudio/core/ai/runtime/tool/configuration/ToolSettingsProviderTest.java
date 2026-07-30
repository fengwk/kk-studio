package fun.fengwk.kkstudio.core.ai.runtime.tool.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsCodec;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;

class ToolSettingsProviderTest {
  @Test
  void returnsNormalizedGlobalSettingsAndSafeDefaults() {
    ToolSettingsProperties properties = new ToolSettingsProperties();
    ToolSettingsProvider provider =
        new DeploymentToolSettingsProvider(properties, new ToolSettingsCodec(new ObjectMapper()));

    assertTrue(provider.get().permission().isEmpty());
    assertFalse(provider.get().defaultYolo());

    properties.setSettingsJson("{\"permission\":{\"write\":\"ask\"},\"defaultYolo\":true}");
    assertEquals(PermissionAction.ASK, provider.get().rulesFor("write").get(0).action());
    assertTrue(provider.get().defaultYolo());
  }

  @Test
  void rejectsInvalidDeploymentSettings() {
    ToolSettingsProperties properties = new ToolSettingsProperties();
    properties.setSettingsJson("[]");
    ToolSettingsProvider provider =
        new DeploymentToolSettingsProvider(properties, new ToolSettingsCodec(new ObjectMapper()));

    assertThrows(IllegalArgumentException.class, provider::get);
  }
}
