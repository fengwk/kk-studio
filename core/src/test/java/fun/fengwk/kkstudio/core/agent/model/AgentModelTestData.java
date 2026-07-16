package fun.fengwk.kkstudio.core.agent.model;

import fun.fengwk.kkstudio.share.model.AgentModelEditablePropertiesDTO;

/** Shared complete model JSON used by CRUD tests now that incomplete models are rejected. */
public final class AgentModelTestData {

  public static final String CAPABILITIES = "[\"TEXT\",\"TOOLS\"]";
  public static final String CONFIG =
      "{\"contextWindow\":32768,\"maxOutputTokens\":4096,"
          + "\"inputModalities\":[\"TEXT\"],"
          + "\"variants\":[{\"name\":\"default\",\"maxOutputTokens\":4096}],"
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
