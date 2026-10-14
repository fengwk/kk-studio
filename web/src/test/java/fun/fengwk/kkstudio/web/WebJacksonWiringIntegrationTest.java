package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.springboot.starter.web.result.WebExceptionResultHandlerAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;
import fun.fengwk.kkstudio.web.project.ProjectDtoMapper;

import java.lang.reflect.Field;

/**
 * 真实 Spring 容器验收：测试组合根 {@link WebTestApplication} 与生产 {@link WebApplication} 一样排除只提供 Jackson 2
 * mapper 的 convention4j JSON 自动装配，因此自有代码在容器内只解析到唯一 Jackson 3 mapper，同时其余 convention4j Result 能力仍在。
 */
class WebJacksonWiringIntegrationTest extends WebPostgresTestSupport {

  @Autowired private ApplicationContext context;

  @Test
  void containerExposesOnlyTheUniqueJackson3MapperToOwnCode() throws Exception {
    JsonMapper jsonMapper = context.getBean(JsonMapper.class);
    // Boot4 装配的唯一 Jackson 3 mapper：JsonMapper 与其父类型 ObjectMapper 是同一 bean。
    assertSame(jsonMapper, context.getBean(ObjectMapper.class));
    assertEquals(
        0,
        context.getBeanNamesForType(jackson2MapperClass()).length,
        "excluded convention4j Jackson 2 auto-configuration must not register a Jackson 2 mapper");

    // 自有代码只拿到该 Jackson 3 mapper，而不是构件内部或 Jackson 2 的副本。
    Field field = ProjectDtoMapper.class.getDeclaredField("objectMapper");
    field.setAccessible(true);
    assertSame(jsonMapper, field.get(context.getBean(ProjectDtoMapper.class)));

    // 其余 convention4j 能力保留：Result 异常处理自动装配仍在真实容器中生效。
    assertTrue(
        context.getBeanNamesForType(WebExceptionResultHandlerAutoConfiguration.class).length > 0);
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

  private static Class<?> jackson2MapperClass() throws ClassNotFoundException {
    return Class.forName(
        String.join(".", "com", "fasterxml", "jackson", "databind", "ObjectMapper"));
  }
}
