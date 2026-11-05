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
import tools.jackson.databind.introspect.AnnotatedMember;
import tools.jackson.databind.introspect.AnnotatedParameter;
import tools.jackson.databind.introspect.BeanPropertyDefinition;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 请求反序列化成功后，以字段或 record 组件的类型注解校验必填属性。 */
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
    // 构造器和自定义 setter 可能先于 afterBodyRead 拒绝 null；先确保错误仍包含属性路径。
    validatePresence(tree, mapper.constructType(targetType), "");
    return new CapturedInput(input.getHeaders(), bytes, tree);
  }

  @Override
  public Object afterBodyRead(
      Object body,
      HttpInputMessage input,
      MethodParameter parameter,
      Type targetType,
      Class<? extends HttpMessageConverter<?>> converterType) {
    // 原始树区分缺失与 primitive/有默认值的字段；解析错误由消息转换器处理。
    JsonNode tree = ((CapturedInput) input).tree();
    validate(
        body,
        tree,
        "",
        mapper.constructType(targetType),
        parameter.getParameter().getAnnotatedType());
    return body;
  }

  private void validatePresence(JsonNode node, JavaType type, String path) {
    if (node == null || node.isNull() || type == null) {
      return;
    }
    if (node.isArray() && type.getContentType() != null) {
      for (int i = 0; i < node.size(); i++) {
        validatePresence(node.get(i), type.getContentType(), path + "[" + i + "]");
      }
      return;
    }
    if (!node.isObject()) {
      return;
    }
    if (type.isMapLikeType()) {
      if (type.getContentType() != null && type.getContentType().getRawClass() != Object.class) {
        node.properties()
            .forEach(
                entry ->
                    validatePresence(
                        entry.getValue(),
                        type.getContentType(),
                        path.isEmpty() ? entry.getKey() : path + "." + entry.getKey()));
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
      validatePresence(childNode, property.type(), child);
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

  private void validate(
      Object value,
      JsonNode node,
      String path,
      JavaType declared,
      @Nullable AnnotatedType annotated) {
    if (value == null || declared.getRawClass() == Object.class) {
      return;
    }
    if (value instanceof Map<?, ?> map) {
      JavaType content = declared.getContentType();
      if (content == null || content.getRawClass() == Object.class) {
        return;
      }
      AnnotatedType element = typeArgument(annotated, 1);
      for (var entry : map.entrySet()) {
        String key = entry.getKey().toString();
        String child = path.isEmpty() ? key : path + "." + key;
        requireElement(entry.getValue(), element, child);
        validate(entry.getValue(), node.get(key), child, content, element);
      }
      return;
    }
    if (value instanceof Iterable<?> values) {
      JavaType content = declared.getContentType();
      if (content == null || content.getRawClass() == Object.class) {
        return;
      }
      AnnotatedType element = typeArgument(annotated, 0);
      int index = 0;
      for (Object item : values) {
        String child = path + "[" + index + "]";
        requireElement(item, element, child);
        validate(item, node.get(index++), child, content, element);
      }
      return;
    }
    if (value.getClass().isArray()) {
      JavaType content = declared.getContentType();
      for (int i = 0; i < Array.getLength(value); i++) {
        Object item = Array.get(value, i);
        String child = path + "[" + i + "]";
        requireElement(item, null, child);
        validate(item, node.get(i), child, content, null);
      }
      return;
    }
    Class<?> type = value.getClass();
    if (type.isEnum() || type.getPackageName().startsWith("java.") || value instanceof JsonNode) {
      return;
    }
    for (Property property : metadata.computeIfAbsent(type, this::introspect)) {
      String child = path.isEmpty() ? property.name() : path + "." + property.name();
      JsonNode childNode = node.get(property.name());
      if (childNode == null || childNode.isNull()) {
        if (!property.nullable()) {
          throw required(child);
        }
        continue;
      }
      Object childValue = property.accessor().getValue(value);
      if (childValue == null) {
        if (!property.nullable()) {
          throw required(child);
        }
      } else {
        validate(childValue, childNode, child, property.type(), property.annotated());
      }
    }
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
      AnnotatedMember accessor = property.getAccessor();
      if (accessor == null) {
        throw new IllegalStateException(
            "No readable field for " + type.getName() + "." + property.getName());
      }
      accessor.fixAccess(true);
      properties.add(
          new Property(
              property.getName(),
              !declared.isPrimitive()
                  && annotated != null
                  && annotated.isAnnotationPresent(Nullable.class),
              declared,
              annotated,
              accessor));
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
      return parameterized.getAnnotatedActualTypeArguments()[index];
    }
    return null;
  }

  private static void requireElement(Object value, @Nullable AnnotatedType type, String path) {
    if (value == null && (type == null || !type.isAnnotationPresent(Nullable.class))) {
      throw required(path);
    }
  }

  private static ResponseStatusException required(String path) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, path + " is required");
  }

  private record Property(
      String name,
      boolean nullable,
      JavaType type,
      @Nullable AnnotatedType annotated,
      AnnotatedMember accessor) {}

  private record CapturedInput(HttpHeaders headers, byte[] bytes, JsonNode tree)
      implements HttpInputMessage {
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
