package fun.fengwk.kkstudio.share.catalog;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Require an exact string wire value, without ordinal or whitespace coercion. */
public final class PromptCacheRetentionDeserializer
    extends ValueDeserializer<PromptCacheRetention> {
  @Override
  public PromptCacheRetention deserialize(JsonParser parser, DeserializationContext context) {
    if (parser.currentToken() != JsonToken.VALUE_STRING) {
      throw DatabindException.from(parser, "promptCacheRetention must be a string");
    }
    try {
      return PromptCacheRetention.valueOf(parser.getString());
    } catch (IllegalArgumentException error) {
      throw DatabindException.from(parser, "promptCacheRetention is invalid");
    }
  }
}
