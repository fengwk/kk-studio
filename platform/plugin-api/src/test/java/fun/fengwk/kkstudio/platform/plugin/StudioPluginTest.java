package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** StudioPlugin 定义与实例分离：可选认证默认缺席，configure 产出由 Plugin 拥有的可关闭实例。 */
class StudioPluginTest {

  private static final PluginConfigSchema SCHEMA =
      new PluginConfigSchema(new InputSchema(null, Map.of(), Set.of(), false), "{}", Map.of());

  /** 只声明安装期定义的最小实现：认证能力全部默认缺席。 */
  private static final class MinimalPlugin implements StudioPlugin {

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor("acme.plugin", "Display", "1.0.0");
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return SCHEMA;
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.None();
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      return new EmptyInstance();
    }
  }

  private static final class EmptyInstance implements PluginInstance {

    private boolean closed;

    @Override
    public List<HarnessContributor> harnessContributors() {
      return List.of();
    }

    @Override
    public List<CanvasFunctionAdapter> canvasAdapters() {
      return List.of();
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static final class NoopCredentialAccess implements PluginCredentialAccess {

    @Override
    public PluginCredentialSnapshot resolve() {
      throw new IllegalStateException("not used");
    }

    @Override
    public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
      return false;
    }
  }

  @Test
  void optionalAuthenticationCapabilitiesDefaultToEmpty() {
    StudioPlugin plugin = new MinimalPlugin();

    assertEquals("acme.plugin", plugin.descriptor().pluginId());
    assertTrue(plugin.authHandler().isEmpty());
    assertTrue(plugin.refresher().isEmpty());
    assertEquals(new PluginAuthSpec.None(), plugin.authenticationSpec());
    assertSame(SCHEMA, plugin.configurationSchema());
    assertEquals(Set.of(), plugin.requiredAgentNames(PluginConfig.initial(SCHEMA)));
  }

  /** configure 产出的实例是可关闭的，且两域贡献可以为空列表。 */
  @Test
  void configureProducesCloseableInstance() {
    StudioPlugin plugin = new MinimalPlugin();
    PluginContext context =
        new PluginContext(
            new NoopCredentialAccess(), Optional.empty(), Optional.empty(), Optional.empty());

    EmptyInstance instance =
        (EmptyInstance) plugin.configure(PluginConfig.initial(SCHEMA), context);

    assertTrue(instance.harnessContributors().isEmpty());
    assertTrue(instance.canvasAdapters().isEmpty());
    assertFalse(instance.closed);
    instance.close();
    assertTrue(instance.closed);
  }
}
