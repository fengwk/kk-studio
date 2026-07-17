package fun.fengwk.kkstudio.core.harness.run.resource;

import fun.fengwk.kkstudio.share.model.AgentProviderType;

/** Resource-side provider type helper for tests that also exercise Harness ProviderType. */
final class PersistedProviderTypes {

  private PersistedProviderTypes() {}

  static AgentProviderType from(String value) {
    return AgentProviderType.valueOf(value);
  }
}
