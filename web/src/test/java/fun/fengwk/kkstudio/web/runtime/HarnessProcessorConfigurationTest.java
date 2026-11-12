package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import fun.fengwk.kkstudio.harness.spi.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.spi.work.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsTestFixture;
import fun.fengwk.kkstudio.web.events.ApplicationEventConfiguration;
import fun.fengwk.kkstudio.web.events.ApplicationEventSettings;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.Supplier;

/** Processor 组合根在下一次使用时读取最新策略，并保留有界关闭生命周期。 */
class HarnessProcessorConfigurationTest {

  @Test
  void modelExecutionExecutorUsesNonBlockingShutdownDestroyHook() {
    Method factory =
        Arrays.stream(HarnessProcessorConfiguration.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("harnessModelExecutionExecutor"))
            .findFirst()
            .orElseThrow();

    Bean bean = factory.getAnnotation(Bean.class);

    assertEquals("shutdown", bean.destroyMethod());
  }

  @Test
  void processorAndEventSettingsUseValuesFromTheCurrentSnapshot() {
    SystemSettingsSnapshot snapshot = new SystemSettingsSnapshot(SystemSettingsTestFixture.DEFAULT);
    HarnessProcessorConfiguration processors = new HarnessProcessorConfiguration();
    Supplier<ProcessorLeaseConfig> lease = processors.processorLeaseConfigProvider(snapshot);
    var thread = processors.threadProcessorConfig((CompactionConfigProvider) () -> null, snapshot);
    var model = processors.modelProcessorConfig(lease, () -> null, ignored -> null, snapshot, null);
    var tool = processors.toolProcessorConfig(lease, () -> null, snapshot);
    Supplier<ApplicationEventSettings> events =
        new ApplicationEventConfiguration().applicationEventSettingsProvider(snapshot);

    ProcessorLeaseConfig activeLease = lease.get();
    Duration activeThreadDelay = thread.resolveFailureDelayProvider().get();
    Duration activeModelDelay = model.admitFailureDelayProvider().get();
    Duration activePreflightDelay = tool.preflightFailureDelayProvider().get();
    Duration activeToolDelay = tool.admitFailureDelayProvider().get();
    ApplicationEventSettings activeConnection = events.get();
    SystemSettings.Advanced updated =
        new SystemSettings.Advanced(
            100_000, 1_001, 1_002, 1_003, 1_004, 1_005, 19, 2_000_006, 1_007, 1_008, 1_009, 1_010);
    snapshot.replace(withAdvanced(updated));

    assertNotEquals(Duration.ofMillis(100_000), activeLease.leaseDuration());
    assertNotEquals(Duration.ofMillis(1_002), activeThreadDelay);
    assertNotEquals(Duration.ofMillis(1_003), activeModelDelay);
    assertNotEquals(Duration.ofMillis(1_004), activePreflightDelay);
    assertNotEquals(Duration.ofMillis(1_005), activeToolDelay);
    assertNotEquals(19, activeConnection.queueCapacity());
    assertEquals(Duration.ofMillis(100_000), lease.get().leaseDuration());
    assertEquals(Duration.ofMillis(1_001), lease.get().heartbeatInterval());
    assertEquals(Duration.ofMillis(1_002), thread.resolveFailureDelayProvider().get());
    assertEquals(Duration.ofMillis(1_003), model.admitFailureDelayProvider().get());
    assertEquals(Duration.ofMillis(1_004), tool.preflightFailureDelayProvider().get());
    assertEquals(Duration.ofMillis(1_005), tool.admitFailureDelayProvider().get());
    assertEquals(19, events.get().queueCapacity());
    assertEquals(2_000_006L, events.get().maxBytes());
    assertEquals(1_007L, events.get().sendTimeoutMillis());
    assertEquals(1_008L, events.get().heartbeatIntervalMillis());
  }

  private static SystemSettings withAdvanced(SystemSettings.Advanced advanced) {
    SystemSettings defaults = SystemSettingsTestFixture.DEFAULT;
    return new SystemSettings(
        defaults.tool(),
        defaults.aiRuntime(),
        defaults.environment(),
        defaults.backendNetwork(),
        defaults.storageMedia(),
        advanced);
  }
}
