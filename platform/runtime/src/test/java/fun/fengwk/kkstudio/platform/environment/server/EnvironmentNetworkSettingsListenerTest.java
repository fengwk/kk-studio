package fun.fengwk.kkstudio.platform.environment.server;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsChangeHandler;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsRepository;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.time.Instant;

class EnvironmentNetworkSettingsListenerTest {
  /** 验证生产 Spring 事件连接：通知与重连回读后均唤醒本节点会话。 */
  @Test
  void refreshPublishesToDaemonServer() {
    EnvironmentDaemonServer server = mock(EnvironmentDaemonServer.class);
    SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
    Instant now = Instant.now();
    when(repository.get())
        .thenReturn(
            new SystemSettingsRepository.SystemSettingsRecord(
                SystemSettings.DEFAULT, 1L, now, now));
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          EnvironmentNetworkSettingsListener.class,
          () -> new EnvironmentNetworkSettingsListener(server));
      context.refresh();
      SystemSettingsChangeHandler handler =
          new SystemSettingsChangeHandler(
              repository, new SystemSettingsSnapshot(SystemSettings.DEFAULT), context);
      handler.onNotification("1");
      handler.onResync();
      verify(server, times(2)).reconcileNetworkSettings();
    }
  }
}
