package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptContent;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * {@link PluginContext} 的四端口形状契约。
 *
 * <p>测试意图：宿主端口以 {@code Optional} 表达「部署是否绑定」，但四个字段本身都不可为 null；缺失端口只能由 {@code Optional.empty()}
 * 表达，绝不静默降级成 null。
 */
class PluginContextTest {

  @Test
  void keepsAllFourHostPortsAndAllowsUnboundDeployments() {
    PluginCredentialAccess access = new NoopCredentialAccess();
    PluginPromptTasks tasks = new NoopPromptTasks();
    PluginPromptResources resources = new NoopPromptResources();

    PluginContext bound =
        new PluginContext(access, Optional.empty(), Optional.of(tasks), Optional.of(resources));
    assertSame(access, bound.credentialAccess());
    assertTrue(bound.resourceGateway().isEmpty());
    assertSame(tasks, bound.promptTasks().orElseThrow());
    assertSame(resources, bound.promptResources().orElseThrow());

    PluginContext unbound =
        new PluginContext(access, Optional.empty(), Optional.empty(), Optional.empty());
    assertTrue(unbound.promptTasks().isEmpty());
    assertTrue(unbound.promptResources().isEmpty());
  }

  @Test
  void rejectsNullFields() {
    PluginCredentialAccess access = new NoopCredentialAccess();

    assertThrows(
        NullPointerException.class,
        () -> new PluginContext(null, Optional.empty(), Optional.empty(), Optional.empty()));
    assertThrows(NullPointerException.class, () -> new PluginContext(access, null, null, null));
    assertThrows(
        NullPointerException.class,
        () -> new PluginContext(access, Optional.empty(), null, Optional.empty()));
    assertThrows(
        NullPointerException.class,
        () -> new PluginContext(access, Optional.empty(), Optional.empty(), null));
  }

  private static final class NoopCredentialAccess implements PluginCredentialAccess {

    @Override
    public PluginCredentialSnapshot resolve() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
      return false;
    }
  }

  private static final class NoopPromptTasks implements PluginPromptTasks {

    @Override
    public UUID submit(String agentName, String systemText, List<PluginPromptContent> contents) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String await(UUID taskId, Duration timeout, BooleanSupplier continueWaiting) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void stop(UUID taskId) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class NoopPromptResources implements PluginPromptResources {

    @Override
    public StagedResource stage(
        String filename, String mediaType, InputStream input, long expectedSize) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void discard(StagedResource resource) {
      throw new UnsupportedOperationException();
    }
  }

  /** 断言记录组件形态：四端口顺序固定为 credentialAccess / resourceGateway / promptTasks / promptResources。 */
  @Test
  void recordComponentsKeepStableOrder() {
    var components = PluginContext.class.getRecordComponents();

    assertEquals(4, components.length);
    assertEquals("credentialAccess", components[0].getName());
    assertEquals("resourceGateway", components[1].getName());
    assertEquals("promptTasks", components[2].getName());
    assertEquals("promptResources", components[3].getName());
  }
}
