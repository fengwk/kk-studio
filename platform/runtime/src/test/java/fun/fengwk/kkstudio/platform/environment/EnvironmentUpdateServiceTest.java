package fun.fengwk.kkstudio.platform.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.dto.DaemonConfigDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentUpdateDTO;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsTestFixture;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** 卡片完整配置写入必须走同一 CAS，且 name 不参与更新请求。 */
class EnvironmentUpdateServiceTest {

  private static final UUID ENV_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void updateReplacesNoteAndCompleteConfigThroughCas() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    Environment env = environment(3L);
    when(repo.lockById(ENV_ID)).thenReturn(env);
    when(repo.updateById(env, 3L)).thenReturn(true);
    Environment updated = environment(4L);
    updated.setNote("新备注");
    DaemonConfigDTO config = daemonConfig();
    config.setProxyUrl("http://127.0.0.1:7890");
    updated.setDaemonConfig(config);
    when(repo.getById(ENV_ID)).thenReturn(updated);

    EnvironmentService service = service(repo);
    EnvironmentUpdateDTO request = new EnvironmentUpdateDTO();
    request.setExpectedVersion(3L);
    request.setNote("  新备注  ");
    request.setDaemonConfig(config);

    EnvironmentDTO card = service.update(EnvironmentId.of(ENV_ID), request);

    assertEquals("新备注", card.getNote());
    assertEquals("http://127.0.0.1:7890", card.getDaemonConfig().getProxyUrl());
    assertEquals(4L, card.getVersion());
    ArgumentCaptor<Environment> written = ArgumentCaptor.forClass(Environment.class);
    verify(repo).updateById(written.capture(), eq(3L));
    assertEquals("新备注", written.getValue().getNote());
    assertEquals("http://127.0.0.1:7890", written.getValue().getDaemonConfig().getProxyUrl());
  }

  @Test
  void updateWritesEvenWhenTheRequestedContentIsUnchanged() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    Environment env = environment(7L);
    when(repo.lockById(ENV_ID)).thenReturn(env);
    when(repo.updateById(env, 7L)).thenReturn(true);
    when(repo.getById(ENV_ID)).thenReturn(environment(8L));

    EnvironmentService service = service(repo);
    EnvironmentUpdateDTO request = new EnvironmentUpdateDTO();
    request.setExpectedVersion(7L);
    request.setNote(env.getNote());
    request.setDaemonConfig(env.getDaemonConfig());

    service.update(EnvironmentId.of(ENV_ID), request);

    verify(repo).updateById(env, 7L);
  }

  @Test
  void updateRejectsMissingVersionAndInvalidConfigBeforeWriting() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentService service = service(repo);

    assertThrows(DomainException.class, () -> service.update(EnvironmentId.of(ENV_ID), null));

    EnvironmentUpdateDTO missingVersion = new EnvironmentUpdateDTO();
    missingVersion.setDaemonConfig(daemonConfig());
    assertThrows(
        DomainException.class, () -> service.update(EnvironmentId.of(ENV_ID), missingVersion));

    EnvironmentUpdateDTO missingConfig = new EnvironmentUpdateDTO();
    missingConfig.setExpectedVersion(0L);
    assertThrows(
        DomainException.class, () -> service.update(EnvironmentId.of(ENV_ID), missingConfig));

    EnvironmentUpdateDTO stale = new EnvironmentUpdateDTO();
    stale.setExpectedVersion(0L);
    stale.setDaemonConfig(daemonConfig());
    when(repo.lockById(ENV_ID)).thenReturn(environment(2L));
    assertThrows(DomainException.class, () -> service.update(EnvironmentId.of(ENV_ID), stale));
    verify(repo, never()).updateById(any(), anyLong());
  }

  @Test
  void createRequiresCompleteConfigAndNormalizesBlankNote() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    when(repo.existsByName("card")).thenReturn(false);
    when(repo.create(any())).thenReturn(true);
    when(repo.getById(any()))
        .thenAnswer(
            invocation -> {
              Environment created = environment(0L);
              created.setId(invocation.getArgument(0));
              created.setName("card");
              created.setNote(null);
              return created;
            });
    EnvironmentService service = service(repo);

    EnvironmentCreateDTO create = new EnvironmentCreateDTO();
    create.setName("card");
    create.setNote("   ");
    assertThrows(DomainException.class, () -> service.create(create));

    create.setDaemonConfig(daemonConfig());
    EnvironmentDTO card = service.create(create);

    assertNull(card.getNote());
    assertEquals("https://studio.example.com", card.getDaemonConfig().getStudioUrl());
  }

  private static EnvironmentService service(EnvironmentRepository repo) {
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettingsTestFixture.DEFAULT);
    return new EnvironmentService(repo, mock(EnvironmentRegistry.class), snapshot, CLOCK, "3.0.0");
  }

  private static Environment environment(long version) {
    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setNote("旧备注");
    env.setRegistrationTokenHash(RegistrationTokens.hash("token"));
    env.setDaemonConfig(daemonConfig());
    env.setVersion(version);
    env.setCreatedAt(NOW);
    env.setUpdatedAt(NOW);
    return env;
  }

  private static DaemonConfigDTO daemonConfig() {
    DaemonConfigDTO config = new DaemonConfigDTO();
    config.setStudioUrl("https://studio.example.com");
    return config;
  }
}
