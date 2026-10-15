package fun.fengwk.kkstudio.plugin.opencli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;

import java.util.List;
import java.util.Optional;

/** {@link OpenCliInstance} 运行期实例契约、双适配器装配与幂等释放测试。 */
class OpenCliInstanceTest {

  @Test
  void createInstanceAndVerifyContributions() {
    OpenCliSettings settings = OpenCliSettings.DEFAULT;
    PluginCredentialAccess credentialAccess = mock(PluginCredentialAccess.class);
    PluginContext context = new PluginContext(credentialAccess, Optional.empty());

    OpenCliInstance instance =
        OpenCliInstance.create(settings, JsonMapper.builder().build(), context);

    assertNotNull(instance);
    assertTrue(instance.harnessContributors().isEmpty());

    List<CanvasFunctionAdapter> adapters = instance.canvasAdapters();
    assertEquals(2, adapters.size());
    assertThat(adapters.get(0)).isInstanceOf(GptImage2CanvasFunctionAdapter.class);
    assertThat(adapters.get(1)).isInstanceOf(SeedanceCanvasFunctionAdapter.class);

    // 适配器列表冻结不可变
    assertThatThrownBy(() -> adapters.add(mock(CanvasFunctionAdapter.class)))
        .isInstanceOf(UnsupportedOperationException.class);

    assertNotNull(instance.client());

    // 幂等释放
    instance.close();
    instance.close();
  }

  @Test
  void nullArgumentsThrow() {
    OpenCliSettings settings = OpenCliSettings.DEFAULT;
    PluginCredentialAccess credentialAccess = mock(PluginCredentialAccess.class);
    PluginContext context = new PluginContext(credentialAccess, Optional.empty());

    assertThatThrownBy(() -> OpenCliInstance.create(null, JsonMapper.builder().build(), context))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("settings");

    assertThatThrownBy(() -> OpenCliInstance.create(settings, null, context))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("mapper");

    assertThatThrownBy(() -> OpenCliInstance.create(settings, JsonMapper.builder().build(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("context");
  }
}
