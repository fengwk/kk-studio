package fun.fengwk.kkstudio.platform.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import fun.fengwk.convention4j.comfyui.ComfyUIClient;
import fun.fengwk.convention4j.comfyui.ComfyUIJob;
import fun.fengwk.convention4j.comfyui.PromptSubmission;
import fun.fengwk.convention4j.comfyui.input.UploadResult;
import fun.fengwk.convention4j.comfyui.workflow.Workflow;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import fun.fengwk.kkstudio.platform.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiBindings;
import fun.fengwk.kkstudio.platform.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiBindings.Binding;
import fun.fengwk.kkstudio.platform.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiBindings.Kind;
import fun.fengwk.kkstudio.platform.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiBindings.ParameterOptions;
import fun.fengwk.kkstudio.platform.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiBindings.ValueType;
import fun.fengwk.kkstudio.platform.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiLookupService;
import fun.fengwk.kkstudio.platform.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiSelectorValidator;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowCancelDTO;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowJobDTO;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowRunDTO;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowRunFileDTO;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowRunRequestDTO;

import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 基于已配置工作流的 ComfyUI 运行门面。
 *
 * <p>服务解析绑定、校验并转换输入、提交工作流、查询或取消作业，并归一化终态输出。它不在本地保存 Run；对外的 {@code runId} 直接使用 ComfyUI 返回的 prompt
 * ID。
 *
 * <p>文件输入先按大小上限从 S3 读取，再上传到 ComfyUI。参数转换由绑定类型驱动，结果选择器先经过语法校验，产物通过 {@code (nodeId, mediaType,
 * index)} 定位。
 *
 * @author fengwk
 */
public class ComfyuiRuntimeService {

  private static final Set<String> TERMINAL_STATUSES =
      Set.of(
          "completed",
          "complete",
          "succeeded",
          "success",
          "failed",
          "error",
          "cancelled",
          "canceled",
          "interrupted");
  private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

  private final ComfyuiWorkflowApiLookupService workflowApiLookupService;
  private final SystemSettings.Comfyui settings;
  private final ObjectProvider<ComfyUIClient> comfyUIClientProvider;
  private final StorageBlobContentService blobContentService;
  private final ObjectMapper objectMapper;

  public ComfyuiRuntimeService(
      ComfyuiWorkflowApiLookupService workflowApiLookupService,
      SystemSettingsSnapshot snapshot,
      ObjectProvider<ComfyUIClient> comfyUIClientProvider,
      StorageBlobContentService blobContentService,
      ObjectMapper objectMapper) {
    this.workflowApiLookupService =
        Objects.requireNonNull(
            workflowApiLookupService, "workflowApiLookupService must not be null");
    this.settings = Objects.requireNonNull(snapshot, "snapshot").get().integrations().comfyui();
    this.comfyUIClientProvider =
        Objects.requireNonNull(comfyUIClientProvider, "comfyUIClientProvider must not be null");
    this.blobContentService =
        Objects.requireNonNull(blobContentService, "blobContentService must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  /** 解析绑定并提交工作流；文件输入先经 Storage 权威读取并中转到 ComfyUI。 */
  public ComfyuiWorkflowRunDTO run(UUID workflowId, ComfyuiWorkflowRunRequestDTO request) {
    Objects.requireNonNull(workflowId, "workflowId must not be null");
    ComfyUIClient client = requireClient();
    ComfyuiWorkflowApiBindings configured =
        workflowApiLookupService
            .findEnabledBindings(workflowId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "enabled ComfyUI workflow not found: " + workflowId));
    Workflow workflow = configured.workflow().copy();
    Map<String, Object> parameters =
        request == null || request.getParameters() == null
            ? Collections.emptyMap()
            : request.getParameters();
    Map<String, ComfyuiWorkflowRunFileDTO> files =
        request == null || request.getFiles() == null ? Collections.emptyMap() : request.getFiles();

    validateProvidedNames(configured, parameters, files);
    Map<Binding, Object> parameterValues = resolveParameterValues(configured, parameters);
    List<PlannedFile> plannedFiles = resolveFiles(configured, files);

    parameterValues.forEach(
        (binding, value) ->
            workflow.getNode(binding.nodeId()).setInput(binding.inputName(), value));
    if (!plannedFiles.isEmpty()) {
      long maxSizeBytes = maxInputFileSizeBytes();
      for (PlannedFile plannedFile : plannedFiles) {
        StorageBlobContent blobContent =
            blobContentService.readBlobContent(plannedFile.blobId(), maxSizeBytes);
        String contentType =
            StringUtils.hasText(blobContent.getMediaType())
                ? blobContent.getMediaType().trim()
                : DEFAULT_CONTENT_TYPE;
        UploadResult uploadResult =
            requireResult(
                client
                    .uploadFile(plannedFile.filename(), blobContent.getBytes(), contentType)
                    .block(blockTimeout()),
                "ComfyUI upload returned no result");
        String uploadedPath = uploadedPath(uploadResult);
        workflow
            .getNode(plannedFile.binding().nodeId())
            .setInput(plannedFile.binding().inputName(), uploadedPath);
      }
    }

    PromptSubmission submission =
        requireResult(
            client.submit(workflow).block(blockTimeout()),
            "ComfyUI workflow submission returned no result");
    return ComfyuiWorkflowRunDTO.builder()
        .runId(submission.getPromptId())
        .status("pending")
        .defaultSelector(configured.defaultSelector())
        .build();
  }

  /** 查询作业；仅在终态归一化输出，并按已校验的 JsonPath 选择器投影结果。 */
  public ComfyuiWorkflowJobDTO getJob(String runId, String select) {
    String validatedSelector = ComfyuiWorkflowApiSelectorValidator.validate(select);
    ComfyUIJob job = getRawJob(runId);
    JsonNode result = null;
    if (isTerminal(job)) {
      ObjectNode normalized = normalizeResult(job);
      if (validatedSelector == null) {
        result = normalized;
      } else {
        try {
          Object selected = JsonPath.parse(normalized.toString()).read(validatedSelector);
          result = objectMapper.valueToTree(selected);
        } catch (RuntimeException e) {
          throw new IllegalArgumentException("select is not valid for the normalized result", e);
        }
      }
    }
    return ComfyuiWorkflowJobDTO.builder()
        .runId(job.getId())
        .status(job.getStatus())
        .priority(job.getPriority())
        .createTime(job.getCreateTime())
        .updateTime(job.getUpdateTime())
        .workflowId(job.getWorkflowId())
        .executionStartTime(job.getExecutionStartTime())
        .executionEndTime(job.getExecutionEndTime())
        .outputsCount(job.getOutputsCount())
        .executionError(job.getExecutionError())
        .executionStatus(job.getExecutionStatus())
        .workflow(job.getWorkflow())
        .previewOutput(job.getPreviewOutput())
        .result(result)
        .build();
  }

  /** 向 ComfyUI 发送取消作业指令。 */
  public ComfyuiWorkflowCancelDTO cancel(String runId) {
    boolean cancelled =
        Boolean.TRUE.equals(
            requireResult(
                requireClient().cancelJob(requireText(runId, "runId")).block(blockTimeout()),
                "ComfyUI cancel returned no result"));
    return ComfyuiWorkflowCancelDTO.builder().runId(runId).cancelled(cancelled).build();
  }

  /** 按 {@code (nodeId, mediaType, index)} 定位并下载产物。 */
  public ComfyuiFileDownload downloadFile(
      String runId, String nodeId, String mediaType, int index) {
    if (index < 0) {
      throw new IllegalArgumentException("index must be greater than or equal to 0");
    }
    ComfyUIJob job = getRawJob(runId);
    OutputDescriptor descriptor =
        resolveOutputDescriptor(
            job.getOutputs(),
            requireText(nodeId, "nodeId"),
            requireText(mediaType, "mediaType"),
            index);
    byte[] bytes =
        requireResult(
            requireClient()
                .getFile(descriptor.filename(), descriptor.subfolder(), descriptor.type())
                .block(blockTimeout()),
            "ComfyUI file download returned no content");
    String contentType = URLConnection.guessContentTypeFromName(descriptor.filename());
    if (!StringUtils.hasText(contentType)) {
      contentType = DEFAULT_CONTENT_TYPE;
    }
    return new ComfyuiFileDownload(descriptor.filename(), contentType, bytes);
  }

  private ComfyUIJob getRawJob(String runId) {
    return requireResult(
        requireClient().getJob(requireText(runId, "runId")).block(blockTimeout()),
        "ComfyUI job query returned no result");
  }

  private void validateProvidedNames(
      ComfyuiWorkflowApiBindings configured,
      Map<String, Object> parameters,
      Map<String, ComfyuiWorkflowRunFileDTO> files) {
    Set<String> supplied = new HashSet<>();
    for (String name : parameters.keySet()) {
      Binding binding =
          configured
              .find(name)
              .orElseThrow(() -> new IllegalArgumentException("unknown input name: " + name));
      if (binding.kind() != Kind.PARAMETER) {
        throw new IllegalArgumentException("file input must be supplied in files: " + name);
      }
      supplied.add(name);
    }
    for (String name : files.keySet()) {
      Binding binding =
          configured
              .find(name)
              .orElseThrow(() -> new IllegalArgumentException("unknown input name: " + name));
      if (binding.kind() != Kind.FILE) {
        throw new IllegalArgumentException(
            "parameter input must be supplied in parameters: " + name);
      }
      if (!supplied.add(name)) {
        throw new IllegalArgumentException("input supplied more than once: " + name);
      }
    }
  }

  private Map<Binding, Object> resolveParameterValues(
      ComfyuiWorkflowApiBindings configured, Map<String, Object> parameters) {
    Map<Binding, Object> values = new HashMap<>();
    for (Binding binding : configured.bindings()) {
      if (binding.kind() != Kind.PARAMETER) {
        continue;
      }
      if (parameters.containsKey(binding.name())) {
        values.put(binding, coerceParameter(binding, parameters.get(binding.name())));
        continue;
      }
      ParameterOptions options = binding.parameterOptions();
      if (options != null && options.defaultValue() != null) {
        values.put(binding, options.defaultValue());
      } else if (binding.required()) {
        throw new IllegalArgumentException("required parameter is missing: " + binding.name());
      }
    }
    return values;
  }

  private List<PlannedFile> resolveFiles(
      ComfyuiWorkflowApiBindings configured, Map<String, ComfyuiWorkflowRunFileDTO> files) {
    List<PlannedFile> plannedFiles = new ArrayList<>();
    for (Binding binding : configured.bindings()) {
      if (binding.kind() != Kind.FILE) {
        continue;
      }
      ComfyuiWorkflowRunFileDTO file = files.get(binding.name());
      if (file == null) {
        if (files.containsKey(binding.name())) {
          throw new IllegalArgumentException("file input must not be null: " + binding.name());
        }
        if (binding.required()) {
          throw new IllegalArgumentException("required file is missing: " + binding.name());
        }
        continue;
      }
      UUID blobId = parseBlobId(file.getBlobId(), binding.name());
      String filename = resolveFilename(file.getFilename());
      plannedFiles.add(new PlannedFile(binding, blobId, filename));
    }
    return plannedFiles;
  }

  private static UUID parseBlobId(String blobIdText, String inputName) {
    if (!StringUtils.hasText(blobIdText)) {
      throw new IllegalArgumentException("file '" + inputName + "' blobId must not be blank");
    }
    try {
      return UUID.fromString(blobIdText.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("file '" + inputName + "' blobId must be a valid UUID", e);
    }
  }

  private Object coerceParameter(Binding binding, Object rawValue) {
    ParameterOptions options = binding.parameterOptions();
    ValueType valueType = options == null ? null : options.valueType();
    if (valueType == null) {
      return rawValue;
    }
    JsonNode value = objectMapper.valueToTree(rawValue);
    if (value == null || value.isNull()) {
      if (valueType == ValueType.JSON) {
        return value;
      }
      throw new IllegalArgumentException("parameter must not be null: " + binding.name());
    }
    return switch (valueType) {
      case STRING -> {
        if (!value.isTextual()) {
          throw invalidParameterType(binding, "string");
        }
        yield value.asText();
      }
      case INTEGER -> {
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
          throw invalidParameterType(binding, "integer");
        }
        yield value.asLong();
      }
      case NUMBER -> {
        if (!value.isNumber()) {
          throw invalidParameterType(binding, "number");
        }
        yield value.decimalValue();
      }
      case BOOLEAN -> {
        if (!value.isBoolean()) {
          throw invalidParameterType(binding, "boolean");
        }
        yield value.asBoolean();
      }
      case JSON -> value;
    };
  }

  private IllegalArgumentException invalidParameterType(Binding binding, String expected) {
    return new IllegalArgumentException("parameter '" + binding.name() + "' must be a " + expected);
  }

  private ObjectNode normalizeResult(ComfyUIJob job) {
    JsonNode outputs = job.getOutputs();
    ObjectNode root = objectMapper.createObjectNode();
    root.set("outputs", outputs == null ? objectMapper.createObjectNode() : outputs.deepCopy());
    ArrayNode files = root.putArray("files");
    if (outputs != null && outputs.isObject()) {
      for (Map.Entry<String, JsonNode> node : outputs.properties()) {
        appendOutputFiles(files, job.getId(), node.getKey(), node.getValue());
      }
    }
    return root;
  }

  private void appendOutputFiles(
      ArrayNode files, String runId, String nodeId, JsonNode nodeOutput) {
    if (nodeOutput == null || !nodeOutput.isObject()) {
      return;
    }
    for (Map.Entry<String, JsonNode> media : nodeOutput.properties()) {
      JsonNode value = media.getValue();
      if (value.isArray()) {
        for (int i = 0; i < value.size(); i++) {
          appendOutputFile(files, runId, nodeId, media.getKey(), i, value.get(i));
        }
      } else {
        appendOutputFile(files, runId, nodeId, media.getKey(), 0, value);
      }
    }
  }

  private void appendOutputFile(
      ArrayNode files, String runId, String nodeId, String mediaType, int index, JsonNode item) {
    if (item == null || !item.isObject() || !item.path("filename").isTextual()) {
      return;
    }
    ObjectNode descriptor = files.addObject();
    descriptor.put("nodeId", nodeId);
    descriptor.put("mediaType", mediaType);
    descriptor.put("index", index);
    descriptor.put("filename", item.path("filename").asText());
    descriptor.put("subfolder", item.path("subfolder").asText(""));
    descriptor.put("type", item.path("type").asText(""));
    descriptor.put(
        "downloadUrl",
        "/api/comfyui/runs/"
            + encodePathSegment(runId)
            + "/files/"
            + encodePathSegment(nodeId)
            + "/"
            + encodePathSegment(mediaType)
            + "/"
            + index);
  }

  private OutputDescriptor resolveOutputDescriptor(
      JsonNode outputs, String nodeId, String mediaType, int index) {
    JsonNode media = outputs == null ? null : outputs.path(nodeId).path(mediaType);
    JsonNode item;
    if (media != null && media.isArray()) {
      item = index < media.size() ? media.get(index) : null;
    } else {
      item = index == 0 ? media : null;
    }
    if (item == null || item.isMissingNode() || !item.isObject()) {
      throw new IllegalArgumentException("job output file not found");
    }
    String filename = item.path("filename").asText(null);
    if (!StringUtils.hasText(filename)) {
      throw new IllegalArgumentException("job output file not found");
    }
    return new OutputDescriptor(
        filename, item.path("subfolder").asText(""), item.path("type").asText(""));
  }

  private boolean isTerminal(ComfyUIJob job) {
    String status = job.getStatus();
    return job.getExecutionEndTime() != null
        || job.getExecutionError() != null
        || (status != null && TERMINAL_STATUSES.contains(status.toLowerCase(Locale.ROOT)));
  }

  private ComfyUIClient requireClient() {
    if (!settings.enabled()) {
      throw new IllegalStateException("ComfyUI runtime is disabled by SystemSettings");
    }
    ComfyUIClient client = comfyUIClientProvider.getIfAvailable();
    if (client == null) {
      throw new IllegalStateException("ComfyUI client is unavailable");
    }
    return client;
  }

  private long maxInputFileSizeBytes() {
    long maxInputFileBytes = settings.maxInputFileBytes();
    if (maxInputFileBytes < 0L) {
      throw new IllegalStateException("comfyui maxInputFileBytes must not be negative");
    }
    return maxInputFileBytes;
  }

  private Duration blockTimeout() {
    long readTimeoutMillis = settings.readTimeoutMillis();
    if (readTimeoutMillis <= 0L) {
      throw new IllegalStateException("comfyui readTimeoutMillis must be positive");
    }
    return Duration.ofMillis(readTimeoutMillis).plusSeconds(1);
  }

  private static String uploadedPath(UploadResult result) {
    if (result == null || !StringUtils.hasText(result.getName())) {
      throw new IllegalStateException("ComfyUI upload returned an invalid filename");
    }
    return StringUtils.hasText(result.getSubfolder())
        ? result.getSubfolder() + "/" + result.getName()
        : result.getName();
  }

  /** 要求上传文件名是长度受限且不含路径分隔符或控制字符的基名。 */
  private static String resolveFilename(String requestedFilename) {
    String filename = trimToNull(requestedFilename);
    if (!StringUtils.hasText(filename)
        || filename.length() > 255
        || filename.equals(".")
        || filename.equals("..")
        || filename.indexOf('/') >= 0
        || filename.indexOf('\\') >= 0) {
      throw new IllegalArgumentException("filename must be a safe basename");
    }
    for (int i = 0; i < filename.length(); i++) {
      char ch = filename.charAt(i);
      if (Character.isISOControl(ch) || ch == '"') {
        throw new IllegalArgumentException("filename contains unsupported characters");
      }
    }
    return filename;
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static String requireText(String value, String name) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }

  private static String encodePathSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static <T> T requireResult(T result, String message) {
    if (result == null) {
      throw new IllegalStateException(message);
    }
    return result;
  }

  private record PlannedFile(Binding binding, UUID blobId, String filename) {}

  private record OutputDescriptor(String filename, String subfolder, String type) {}
}
