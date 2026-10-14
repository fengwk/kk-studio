package fun.fengwk.kkstudio.platform.environment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.ai.environment.DaemonConfiguration;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallConfigDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallConfigUpdateDTO;

import java.time.Clock;
import java.util.UUID;

/** 保存只变更安装设置；CAS 必须先于幂等判断，且响应绝不包含 token。 */
class EnvironmentInstallConfigServiceTest {
  private final UUID id = UUID.randomUUID();
  private final EnvironmentRepository repo = mock(EnvironmentRepository.class);
  private final EnvironmentServiceImpl service =
      new EnvironmentServiceImpl(
          repo,
          mock(EnvironmentRegistry.class),
          mock(JdbcTemplate.class),
          mock(SystemSettingsSnapshot.class),
          Clock.systemUTC());

  @Test
  void updatesConfigWithoutChangingOrExposingToken() {
    Environment env = environment(0);
    when(repo.lockById(id)).thenReturn(env);
    when(repo.updateById(env, 0))
        .thenAnswer(
            invocation -> {
              env.setVersion(1L);
              return true;
            });
    when(repo.getById(id)).thenReturn(env);
    var result = service.updateInstallConfig(EnvironmentId.of(id), request());
    assertEquals("1", result.getVersion());
    assertEquals(request().getInstallConfig(), result.getInstallConfig());
    assertNull(result.getRegistrationToken());
    assertEquals("private-token", env.getRegistrationToken());
    verify(repo).updateById(env, 0);
  }

  @Test
  void unchangedIsNoOpButStaleUnchangedStillConflicts() {
    Environment env = environment(0);
    env.setInstallConfig(request().getInstallConfig());
    when(repo.lockById(id)).thenReturn(env);
    assertEquals("0", service.updateInstallConfig(EnvironmentId.of(id), request()).getVersion());
    verify(repo, never()).updateById(any(), anyLong());
    env.setVersion(1L);
    assertThrows(
        AiVersionConflictException.class,
        () -> service.updateInstallConfig(EnvironmentId.of(id), request()));
    verify(repo, never()).updateById(any(), anyLong());
  }

  @Test
  void invalidRequestFailsBeforeRepositoryAccess() {
    assertThrows(
        AiValidationException.class, () -> service.updateInstallConfig(EnvironmentId.of(id), null));
    var empty = new EnvironmentInstallConfigUpdateDTO();
    empty.setExpectedVersion("0");
    assertThrows(
        AiValidationException.class,
        () -> service.updateInstallConfig(EnvironmentId.of(id), empty));
    verify(repo, never()).lockById(any());
  }

  @Test
  void missingAndCasLossAreReportedWithoutFalseSuccess() {
    assertThrows(
        AiResourceNotFoundException.class,
        () -> service.updateInstallConfig(EnvironmentId.of(id), request()));
    when(repo.lockById(id)).thenReturn(environment(0));
    assertThrows(
        AiResourceNotFoundException.class,
        () -> service.updateInstallConfig(EnvironmentId.of(id), request()));
    when(repo.lockById(id)).thenReturn(environment(0));
    when(repo.getById(id)).thenReturn(environment(2));
    assertThrows(
        AiVersionConflictException.class,
        () -> service.updateInstallConfig(EnvironmentId.of(id), request()));
  }

  private Environment environment(long version) {
    Environment env = new Environment();
    env.setId(id);
    env.setName("install");
    env.setRegistrationToken("private-token");
    env.setVersion(version);
    return env;
  }

  private static EnvironmentInstallConfigUpdateDTO request() {
    DaemonConfiguration daemon = new DaemonConfiguration();
    daemon.setStudioUrl("https://studio.example.com");
    EnvironmentInstallConfigDTO config = new EnvironmentInstallConfigDTO();
    config.setOperatingSystem("linux");
    config.setDaemon(daemon);
    EnvironmentInstallConfigUpdateDTO request = new EnvironmentInstallConfigUpdateDTO();
    request.setExpectedVersion("0");
    request.setInstallConfig(config);
    return request;
  }
}
