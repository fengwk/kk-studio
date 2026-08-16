package fun.fengwk.kkstudio.core.ai.runtime.model.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.ResponseUsage;

import java.io.IOException;

/**
 * OpenAI Responses compatible endpoints may omit optional usage breakdowns even though the OpenAI
 * SDK models them as required. Missing breakdowns are normalized to zero before LangChain4j reads
 * them; malformed non-object values remain invalid.
 */
final class OpenAiResponsesUsageJsonMapper {

  private static final JsonMapper BASE_MAPPER = ObjectMappers.jsonMapper();
  private static final JsonMapper INSTANCE = create();

  private OpenAiResponsesUsageJsonMapper() {}

  static JsonMapper instance() {
    return INSTANCE;
  }

  private static JsonMapper create() {
    JsonMapper mapper = BASE_MAPPER.copy();
    SimpleModule module = new SimpleModule();
    module.addDeserializer(ResponseUsage.class, new ResponseUsageDeserializer());
    mapper.registerModule(module);
    return mapper;
  }

  private static final class ResponseUsageDeserializer extends JsonDeserializer<ResponseUsage> {

    @Override
    public ResponseUsage deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode node = parser.getCodec().readTree(parser);
      if (node instanceof ObjectNode usage) {
        fillMissingCount(usage, "input_tokens_details", "cached_tokens");
        fillMissingCount(usage, "output_tokens_details", "reasoning_tokens");
      }
      return BASE_MAPPER.treeToValue(node, ResponseUsage.class);
    }

    private static void fillMissingCount(ObjectNode usage, String detailsField, String countField) {
      JsonNode details = usage.get(detailsField);
      if (details == null || details.isNull()) {
        usage.putObject(detailsField).put(countField, 0);
      } else if (details instanceof ObjectNode objectDetails) {
        JsonNode count = objectDetails.get(countField);
        if (count == null || count.isNull()) {
          objectDetails.put(countField, 0);
        }
      }
    }
  }
}
