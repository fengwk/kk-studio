package fun.fengwk.kkstudio.harness.model.provider;

/**
 * A provider-hosted tool definition added to the conversation without rewriting the request prefix.
 */
public record ProviderSystemHostedToolAdditionBlock(String definitionJson)
    implements ProviderContentBlock {

  public ProviderSystemHostedToolAdditionBlock {
    if (definitionJson == null || definitionJson.isBlank()) {
      throw new IllegalArgumentException("definitionJson must not be blank");
    }
  }
}
