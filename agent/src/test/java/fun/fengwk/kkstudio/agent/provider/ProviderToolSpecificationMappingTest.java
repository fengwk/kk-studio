package fun.fengwk.kkstudio.agent.provider;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import fun.fengwk.kkstudio.agent.model.ModelInfo;
import fun.fengwk.kkstudio.agent.model.Variant;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import fun.fengwk.kkstudio.agent.tool.schema.ToolArraySchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolBooleanSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolEnumSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolNumberSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolObjectSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.agent.tool.schema.ToolStringSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author fengwk
 */
public class ProviderToolSpecificationMappingTest {

    /**
     * 校验结构化 ToolParamsSchema 能完整翻译为 ToolSpecification.parameters。
     */
    @Test
    public void testResolveToolSpecificationsMapsStructuredSchema() {
        TestProvider provider = new TestProvider();
        ToolInfo toolInfo = ToolInfo.builder()
            .name("search_files")
            .description("Search files")
            .inputSchema(ToolParamsSchema.builder()
                .description("search input")
                .properties(Map.of(
                    "keyword", ToolStringSchema.builder().description("keyword").build(),
                    "limit", ToolIntegerSchema.builder().description("limit").build(),
                    "score", ToolNumberSchema.builder().description("score").build(),
                    "recursive", ToolBooleanSchema.builder().description("recursive").build(),
                    "mode", ToolEnumSchema.builder().description("mode").enumValues(List.of("name", "content")).build(),
                    "paths", ToolArraySchema.builder().description("paths").items(ToolStringSchema.builder().description("path item").build()).build(),
                    "filters", ToolObjectSchema.builder()
                        .description("filters")
                        .properties(Map.of("owner", ToolStringSchema.builder().description("owner").build()))
                        .required(List.of("owner"))
                        .additionalProperties(false)
                        .build()))
                .required(List.of("keyword", "mode"))
                .additionalProperties(false)
                .build())
            .build();

        List<ToolSpecification> specifications = provider.exposeResolveToolSpecifications(List.of(toolInfo));

        assertEquals(1, specifications.size());
        ToolSpecification specification = specifications.get(0);
        assertEquals("search_files", specification.name());
        assertEquals("Search files", specification.description());
        JsonObjectSchema parameters = specification.parameters();
        assertNotNull(parameters);
        assertEquals("search input", parameters.description());
        assertEquals(List.of("keyword", "mode"), parameters.required());
        assertEquals(false, parameters.additionalProperties());

        assertInstanceOf(JsonStringSchema.class, parameters.properties().get("keyword"));
        assertInstanceOf(JsonIntegerSchema.class, parameters.properties().get("limit"));
        assertInstanceOf(JsonNumberSchema.class, parameters.properties().get("score"));
        assertInstanceOf(JsonBooleanSchema.class, parameters.properties().get("recursive"));

        JsonEnumSchema modeSchema = assertInstanceOf(JsonEnumSchema.class, parameters.properties().get("mode"));
        assertEquals(List.of("name", "content"), modeSchema.enumValues());

        JsonArraySchema pathsSchema = assertInstanceOf(JsonArraySchema.class, parameters.properties().get("paths"));
        assertInstanceOf(JsonStringSchema.class, pathsSchema.items());

        JsonObjectSchema filtersSchema = assertInstanceOf(JsonObjectSchema.class, parameters.properties().get("filters"));
        assertEquals(List.of("owner"), filtersSchema.required());
        assertEquals(false, filtersSchema.additionalProperties());
        assertInstanceOf(JsonStringSchema.class, filtersSchema.properties().get("owner"));
    }

    /**
     * 校验 inputSchema 为空时不会强行创建 parameters。
     */
    @Test
    public void testResolveToolSpecificationsAllowsNullSchema() {
        TestProvider provider = new TestProvider();
        ToolInfo toolInfo = ToolInfo.builder()
            .name("echo")
            .description("echo")
            .build();

        List<ToolSpecification> specifications = provider.exposeResolveToolSpecifications(List.of(toolInfo));

        assertEquals(1, specifications.size());
        assertNull(specifications.get(0).parameters());
    }

    private static final class TestProvider extends AbstractModelProvider {

        /**
         * 构造最小测试 provider。
         */
        private TestProvider() {
            super(ProviderInfo.builder().providerType(ProviderType.openai).build());
        }

        /**
         * 暴露受保护的 resolveToolSpecifications(...) 供测试调用。
         */
        private List<ToolSpecification> exposeResolveToolSpecifications(List<ToolInfo> toolInfos) {
            return resolveToolSpecifications(toolInfos);
        }

        /**
         * 本测试不需要真实 StreamingChatModel，实现直接抛异常。
         */
        @Override
        protected StreamingChatModel getChatModel(ModelInfo modelInfo,
                                                  Variant variant) {
            throw new UnsupportedOperationException();
        }

    }

}
