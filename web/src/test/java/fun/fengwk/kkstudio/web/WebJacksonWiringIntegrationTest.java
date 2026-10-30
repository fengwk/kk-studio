package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;
import fun.fengwk.kkstudio.web.advice.ApiResponseStatusAdvice;
import fun.fengwk.kkstudio.web.project.ProjectDtoMapper;

import java.lang.reflect.Field;

/**
 * 真实 Spring 容器验收：容器内只装配官方唯一 Jackson 3 mapper，classpath 无 Jackson 2 databind，Web 响应由 {@link
 * ApiResponseStatusAdvice} 对齐状态。
 */
@AutoConfigureMockMvc
class WebJacksonWiringIntegrationTest extends WebPostgresTestSupport {

  @Autowired private ApplicationContext context;

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void containerExposesOnlyTheUniqueJackson3MapperToOwnCode() throws Exception {
    JsonMapper jsonMapper = context.getBean(JsonMapper.class);
    // Boot4 装配的唯一 Jackson 3 mapper：JsonMapper 与其父类型 ObjectMapper 是同一 bean。
    assertSame(jsonMapper, context.getBean(ObjectMapper.class));

    // classpath 已彻底无 Jackson 2 databind（不仅是 bean 未注册）。
    assertFalse(
        ClassUtils.isPresent(
            String.join(".", "com", "fasterxml", "jackson", "databind", "ObjectMapper"),
            WebJacksonWiringIntegrationTest.class.getClassLoader()),
        "Jackson 2 databind must not be present on the classpath");

    // 自有代码只拿到该 Jackson 3 mapper，而不是构件内部或 Jackson 2 的副本。
    Field field = ProjectDtoMapper.class.getDeclaredField("objectMapper");
    field.setAccessible(true);
    assertSame(jsonMapper, field.get(context.getBean(ProjectDtoMapper.class)));

    // 显式 ApiResponse 状态同步 advice 是活跃响应信封处理器。
    assertFalse(context.getBeanNamesForType(ApiResponseStatusAdvice.class).length == 0);
  }

  @Test
  void mvcSelectsTheJackson3ConverterForJsonDtoReadsAndWrites() {
    // Bean 身份不足以证明 wire 选择；检查 MVC 按顺序实际选中的读写 converter。
    var converters = context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters();
    assertInstanceOf(
        JacksonJsonHttpMessageConverter.class,
        converters.stream()
            .filter(
                converter ->
                    converter.canRead(AgentModelVariantDTO.class, MediaType.APPLICATION_JSON))
            .findFirst()
            .orElseThrow());
    assertInstanceOf(
        JacksonJsonHttpMessageConverter.class,
        converters.stream()
            .filter(
                converter ->
                    converter.canWrite(AgentModelVariantDTO.class, MediaType.APPLICATION_JSON))
            .findFirst()
            .orElseThrow());
  }

  @Test
  void pageTotalCountIsDecimalStringOnTheHttpWire() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/agents").param("pageNumber", "1").param("pageSize", "1"))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode totalCount =
        objectMapper
            .readTree(result.getResponse().getContentAsString())
            .path("data")
            .path("totalCount");
    assertTrue(totalCount.isString(), "totalCount must be serialized as a decimal string");
    assertTrue(totalCount.asText().matches("\\d+"), "totalCount must contain only digits");
  }
}
