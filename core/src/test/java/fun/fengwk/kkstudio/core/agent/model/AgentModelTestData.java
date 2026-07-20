package fun.fengwk.kkstudio.core.agent.model;

import fun.fengwk.kkstudio.share.model.AgentModelEditablePropertiesDTO;

/** Shared complete model JSON used by CRUD tests now that incomplete models are rejected. */
public final class AgentModelTestData {

  public static final String CAPABILITIES = "[\"TEXT\",\"TOOLS\"]";
  public static final String CONFIG =
      "{\"limit\":{\"context\":32768,\"output\":4096},"
          + "\"abilities\":{\"tools\":true,\"reasoning\":false,"
          + "\"modalities\":{\"input\":[\"TEXT\"],\"output\":[\"TEXT\"]}},"
          + "\"defaultVariant\":\"default\","
          + "\"variants\":[{\"id\":\"default\"}],"
          + "\"pricing\":{\"currency\":\"USD\",\"pricingTier\":\"test\","
          + "\"serviceTier\":\"default\",\"serviceTierMultiplier\":1,"
          + "\"version\":\"test-v1\",\"inputPerMillionTokens\":0,"
          + "\"outputPerMillionTokens\":0,\"cacheReadPerMillionTokens\":0,"
          + "\"cacheWritePerMillionTokens\":0,"
          + "\"cacheWriteLongPerMillionTokens\":0,"
          + "\"reasoningPerMillionTokens\":0}}";

  private AgentModelTestData() {}

  public static void executable(AgentModelEditablePropertiesDTO properties) {
    properties.setCapabilitiesJson(CAPABILITIES);
    properties.setConfigJson(CONFIG);
  }
}
