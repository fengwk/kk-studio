package fun.fengwk.kkstudio.web.advice;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.introspect.AnnotatedField;
import tools.jackson.databind.introspect.AnnotatedParameter;
import tools.jackson.databind.introspect.BeanPropertyDefinition;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 在 JSON 树中以字段或 record 组件的类型注解校验必填属性。 */
@ControllerAdvice
public class RequestBodyRequiredValidationAdvice extends RequestBodyAdviceAdapter {

  private final ObjectMapper mapper;
  private final ConcurrentMap<Class<?>, List<Property>> metadata = new ConcurrentHashMap<>();

  public RequestBodyRequiredValidationAdvice(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public boolean supports(
      MethodParameter parameter,
      Type targetType,
      Class<? extends HttpMessageConverter<?>> converterType) {
    return parameter.hasParameterAnnotation(RequestBody.class)
        && JacksonJsonHttpMessageConverter.class.isAssignableFrom(converterType);
  }

  @Override
  public HttpInputMessage beforeBodyRead(
      HttpInputMessage input,
      MethodParameter parameter,
      Type targetType,
      Class<? extends HttpMessageConverter<?>> converterType)
      throws IOException {
    byte[] bytes = input.getBody().readAllBytes();
    JsonNode tree;
    try {
      tree = mapper.readTree(bytes);
    } catch (JacksonException error) {
      throw new HttpMessageNotReadableException("Invalid JSON request", error, input);
    }
    // 构造器和自定义 setter 可能先拒绝 null；先确保错误包含属性路径。
    validatePresence(
        tree, mapper.constructType(targetType), parameter.getParameter().getAnnotatedType(), "");
    return new CapturedInput(input.getHeaders(), bytes);
  }

  private void validatePresence(
      JsonNode node, JavaType type, @Nullable AnnotatedType annotated, String path) {
    if (node == null || node.isNull() || type == null) {
      return;
    }
    if (node.isArray() && type.getContentType() != null) {
      AnnotatedType element =
          annotated instanceof AnnotatedArrayType array
              ? array.getAnnotatedGenericComponentType()
              : typeArgument(annotated, 0);
      for (int i = 0; i < node.size(); i++) {
        String child = path + "[" + i + "]";
        requireElement(node.get(i), type.getContentType(), element, child);
        validatePresence(node.get(i), type.getContentType(), element, child);
      }
      return;
    }
    if (!node.isObject()) {
      return;
    }
    if (type.isMapLikeType()) {
      if (type.getContentType() != null && type.getContentType().getRawClass() != Object.class) {
        AnnotatedType element = typeArgument(annotated, 1);
        node.properties()
            .forEach(
                entry -> {
                  String child = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                  requireElement(entry.getValue(), type.getContentType(), element, child);
                  validatePresence(entry.getValue(), type.getContentType(), element, child);
                });
      }
      return;
    }
    Class<?> raw = subtype(type.getRawClass(), node);
    if (raw.getPackageName().startsWith("java.")
        || raw.isInterface()
        || JsonNode.class.isAssignableFrom(raw)) {
      return;
    }
    for (Property property : metadata.computeIfAbsent(raw, this::introspect)) {
      String child = path.isEmpty() ? property.name() : path + "." + property.name();
      JsonNode childNode = node.get(property.name());
      if (!property.nullable() && (childNode == null || childNode.isNull())) {
        throw required(child);
      }
      validatePresence(childNode, property.type(), property.annotated(), child);
    }
  }

  private static Class<?> subtype(Class<?> raw, JsonNode node) {
    JsonTypeInfo info = raw.getAnnotation(JsonTypeInfo.class);
    JsonSubTypes subtypes = raw.getAnnotation(JsonSubTypes.class);
    if (info != null
        && info.use() == JsonTypeInfo.Id.NAME
        && info.include() == JsonTypeInfo.As.PROPERTY
        && subtypes != null) {
      JsonNode discriminator = node.get(info.property());
      if (discriminator != null && discriminator.isString()) {
        for (JsonSubTypes.Type candidate : subtypes.value()) {
          if (candidate.name().equals(discriminator.asText())) {
            return candidate.value();
          }
        }
      }
    }
    return raw;
  }

  private List<Property> introspect(Class<?> type) {
    JavaType javaType = mapper.constructType(type);
    var introspector = mapper.deserializationConfig().classIntrospectorInstance();
    BeanDescription bean =
        introspector.introspectForDeserialization(
            javaType, introspector.introspectClassAnnotations(javaType));
    List<Property> properties = new ArrayList<>();
    for (BeanPropertyDefinition property : bean.findProperties()) {
      if (!property.couldDeserialize()) {
        continue;
      }
      AnnotatedField field = property.getField();
      JavaType declared = field == null ? property.getPrimaryType() : field.getType();
      AnnotatedType annotated = annotatedType(property);
      properties.add(
          new Property(
              property.getName(),
              !declared.isPrimitive()
                  && annotated != null
                  && annotated.isAnnotationPresent(Nullable.class),
              declared,
              annotated));
    }
    return List.copyOf(properties);
  }

  // 与 TsContractGenerator 一致，只从字段或 record 组件的类型注解读取。
  private static @Nullable AnnotatedType annotatedType(BeanPropertyDefinition property) {
    AnnotatedField field = property.getField();
    if (field != null && field.getAnnotated() instanceof Field reflected) {
      return reflected.getAnnotatedType();
    }
    AnnotatedParameter parameter = property.getConstructorParameter();
    if (parameter != null && parameter.getDeclaringClass().isRecord()) {
      for (RecordComponent component : parameter.getDeclaringClass().getRecordComponents()) {
        if (component.getName().equals(property.getInternalName())) {
          return component.getAnnotatedType();
        }
      }
    }
    return null;
  }

  private static @Nullable AnnotatedType typeArgument(@Nullable AnnotatedType type, int index) {
    if (type instanceof AnnotatedParameterizedType parameterized) {
      AnnotatedType[] arguments = parameterized.getAnnotatedActualTypeArguments();
      return index < arguments.length ? arguments[index] : null;
    }
    return null;
  }

  private static void requireElement(
      JsonNode value, JavaType type, @Nullable AnnotatedType annotated, String path) {
    if (value.isNull()
        && type.getRawClass() != Object.class
        && (annotated == null || !annotated.isAnnotationPresent(Nullable.class))) {
      throw required(path);
    }
  }

  private static ResponseStatusException required(String path) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, path + " is required");
  }

  private record Property(
      String name, boolean nullable, JavaType type, @Nullable AnnotatedType annotated) {}

  private record CapturedInput(HttpHeaders headers, byte[] bytes) implements HttpInputMessage {
    @Override
    public HttpHeaders getHeaders() {
      return headers;
    }

    @Override
    public InputStream getBody() {
      return new ByteArrayInputStream(bytes);
    }
  }
}
