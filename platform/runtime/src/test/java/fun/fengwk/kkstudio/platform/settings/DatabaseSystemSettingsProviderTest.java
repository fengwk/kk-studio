package fun.fengwk.kkstudio.platform.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class DatabaseSystemSettingsProviderTest {

  @Test
  void readsTheCurrentDatabaseBackedAggregateOnEveryCall() {
    SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
    when(repository.get())
        .thenReturn(
            new SystemSettingsRepository.SystemSettingsRecord(
                SystemSettingsTestFixture.DEFAULT, 0, null, null));
    DatabaseSystemSettingsProvider provider = new DatabaseSystemSettingsProvider(repository);

    assertEquals(SystemSettingsTestFixture.DEFAULT, provider.get());
  }

  @Test
  void rejectsADeletedSingletonRow() {
    SystemSettingsRepository repository = mock(SystemSettingsRepository.class);
    when(repository.get()).thenReturn(null);
    DatabaseSystemSettingsProvider provider = new DatabaseSystemSettingsProvider(repository);

    assertThrows(IllegalStateException.class, provider::get);
  }
}
