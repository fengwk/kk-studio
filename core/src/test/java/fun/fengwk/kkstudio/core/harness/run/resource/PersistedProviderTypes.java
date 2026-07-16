package fun.fengwk.kkstudio.core.harness.run.resource;

import fun.fengwk.kkstudio.agent.provider.ProviderType;

/** Keeps the legacy persisted enum out of tests that also exercise the Harness ProviderType. */
final class PersistedProviderTypes {

  private PersistedProviderTypes() {}

  static ProviderType from(String value) {
    return ProviderType.valueOf(value);
  }
}
