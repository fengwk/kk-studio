package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsCodec;

import java.util.List;

class E2eToolPermissionProfileTest {

  /** 本地验收 profile 必须在非 YOLO 下拦住 read/write/edit，而不是静默执行文件变更。 */
  @Test
  void asksBeforeReadWriteAndEdit() throws Exception {
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader()
            .load("application-e2e", new ClassPathResource("application-e2e.yml"));
    Object raw = sources.getFirst().getProperty("kk-studio.tool.settings-json");
    String settingsJson = assertInstanceOf(String.class, raw);
    ToolSettings settings = new ToolSettingsCodec(new ObjectMapper()).decode(settingsJson);

    assertEquals(PermissionAction.ASK, settings.rulesFor("read").getFirst().action());
    assertEquals(PermissionAction.ASK, settings.rulesFor("write").getFirst().action());
    assertEquals(PermissionAction.ASK, settings.rulesFor("edit").getFirst().action());
    assertFalse(settings.defaultYolo());
  }
}
