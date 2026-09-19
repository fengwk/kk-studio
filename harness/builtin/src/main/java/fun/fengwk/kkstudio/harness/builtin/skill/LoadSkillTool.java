package fun.fengwk.kkstudio.harness.builtin.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 由当前 Thread Agent 选中、用于加载完整 Skill 正文的 internal Tool。
 *
 * <p>Skill 正文是 Platform 自身的全局目录事实，因此本工具只依赖冻结的 invocation 选择与 {@link SkillContentLoader}，不要求绑定
 * Environment、不经过 Daemon、也不使用会话目录或独立超时；加载按冻结的精确 revision 完成，绝不允许按名称回退到同名新版本。
 */
public final class LoadSkillTool implements Tool {

  public static final String NAME = "load_skill";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          SkillToolPrompts.load("load_skill.md"),
          NAME,
          SkillToolPrompts.schema("load_skill.schema.json"),
          ToolSideEffect.READ_ONLY,
          Duration.ofMinutes(1));

  private final ThreadSelectedSkillLookup skillLookup;
  private final SkillContentLoader contentLoader;

  public LoadSkillTool(ThreadSelectedSkillLookup skillLookup, SkillContentLoader contentLoader) {
    this.skillLookup = Objects.requireNonNull(skillLookup, "skillLookup");
    this.contentLoader = Objects.requireNonNull(contentLoader, "contentLoader");
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  /** 正文由 Platform 直接提供，因此不要求绑定 Environment。 */
  @Override
  public ToolRequirements requirements() {
    return ToolRequirements.none();
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    String callId = request.call().id();
    try {
      ToolExecutionContext context = request.context();
      if (context == null) {
        throw new IllegalArgumentException("load_skill requires a durable execution context");
      }
      String skillName = parseName(request.call().argumentsJson());
      Optional<SelectedSkill> selected =
          skillLookup.findSelected(context.invocationId(), context.threadId(), skillName);
      if (selected.isEmpty()) {
        return complete(listener, error(callId, "unknown or unselected skill: " + skillName));
      }
      SelectedSkill skill = selected.get();
      String content =
          contentLoader.load(
              skill.packageName(), skill.packageVersion(), skill.name(), skill.contentRevision());
      return complete(
          listener, new ToolResult(callId, List.of(new TextResultContent(content)), false, "{}"));
    } catch (RuntimeException error) {
      return complete(listener, error(callId, message(error)));
    }
  }

  private static String parseName(String argumentsJson) {
    try {
      JsonNode root = OBJECT_MAPPER.readTree(argumentsJson == null ? "{}" : argumentsJson);
      if (root == null || !root.isObject()) {
        throw new IllegalArgumentException("arguments must be a JSON object");
      }
      Iterator<String> names = root.fieldNames();
      while (names.hasNext()) {
        String field = names.next();
        if (!"name".equals(field)) {
          throw new IllegalArgumentException("unknown argument: " + field);
        }
      }
      JsonNode value = root.get("name");
      if (value == null || value.isNull() || !value.isTextual()) {
        throw new IllegalArgumentException("name is required and must be a string");
      }
      String text = value.textValue().trim();
      if (text.isEmpty()) {
        throw new IllegalArgumentException("name must not be blank");
      }
      return text;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("arguments must be valid JSON", error);
    }
  }

  private static ToolExecutionHandle complete(ToolExecutionListener listener, ToolResult result) {
    listener.onComplete(result);
    return CompletedToolExecutionHandle.INSTANCE;
  }

  private static ToolResult error(String callId, String message) {
    String detail = message == null || message.isBlank() ? "tool execution failed" : message;
    return new ToolResult(callId, List.of(new TextResultContent(detail)), true, "{}");
  }

  private static String message(Throwable error) {
    String detail = error.getMessage();
    return detail == null || detail.isBlank() ? error.getClass().getSimpleName() : detail;
  }
}
