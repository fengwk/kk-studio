package fun.fengwk.kkstudio.core.comfyui;

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
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;

import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiBindings;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiBindings.Binding;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiBindings.Kind;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiBindings.ParameterOptions;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiBindings.ValueType;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiLookupService;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiSelectorValidator;
import fun.fengwk.kkstudio.core.storage.S3ObjectContent;
import fun.fengwk.kkstudio.core.storage.S3ObjectKeyNormalizer;
import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowCancelDTO;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowJobDTO;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowRunDTO;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowRunFileDTO;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowRunRequestDTO;

import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 基于持久工作流配置的无状态 ComfyUI 运行服务。
 *
 * <p>运行期不持久化任何 run / task；{@code runId} 直接等于 ComfyUI prompt / job id。S3 文件输入走 {@link
 * S3StorageService#download(String, long)} 实现大小上限保护；选择器走 {@link
 * ComfyuiWorkflowApiSelectorValidator} 静态规则 + JsonPath compile 校验。
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
  private final ComfyuiProperties properties;
  private final ObjectProvider<ComfyUIClient> comfyUIClientProvider;
  private final ObjectProvider<S3StorageService> s3StorageServiceProvider;
  private final ObjectMapper objectMapper;

  public ComfyuiRuntimeService(
      ComfyuiWorkflowApiLookupService workflowApiLookupService,
      ComfyuiProperties properties,
      ObjectProvider<ComfyUIClient> comfyUIClientProvider,
      ObjectProvider<S3StorageService> s3StorageServiceProvider,
      ObjectMapper objectMapper) {
    this.workflowApiLookupService =
        Objects.requireNonNull(
            workflowApiLookupService, "workflowApiLookupService must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.comfyUIClientProvider =
        Objects.requireNonNull(comfyUIClientProvider, "comfyUIClientProvider must not be null");
    this.s3StorageServiceProvider =
        Objects.requireNonNull(
            s3StorageServiceProvider, "s3StorageServiceProvider must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  public ComfyuiWorkflowRunDTO run(String apiName, ComfyuiWorkflowRunRequestDTO request) {
    ComfyUIClient client = requireClient();
    ComfyuiWorkflowApiBindings configured =
        workflowApiLookupService
            .findEnabledBindings(apiName)
            .orElseThrow(
                () ->
                    new IllegalArgumentException("enabled ComfyUI workflow not found: " + apiName));
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
      S3StorageService s3StorageService = requireS3StorageService();
      long maxSizeBytes = maxInputFileSizeBytes();
      for (PlannedFile plannedFile : plannedFiles) {
        S3ObjectContent objectContent = s3StorageService.download(plannedFile.key(), maxSizeBytes);
        String contentType =
            firstNonBlank(
                plannedFile.contentType(), objectContent.getContentType(), DEFAULT_CONTENT_TYPE);
        UploadResult uploadResult =
            requireResult(
                client
                    .uploadFile(plannedFile.filename(), objectContent.getBytes(), contentType)
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

  public ComfyuiWorkflowCancelDTO cancel(String runId) {
    boolean cancelled =
        Boolean.TRUE.equals(
            requireResult(
                requireClient().cancelJob(requireText(runId, "runId")).block(blockTimeout()),
                "ComfyUI cancel returned no result"));
    return ComfyuiWorkflowCancelDTO.builder().runId(runId).cancelled(cancelled).build();
  }

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
      String key = S3ObjectKeyNormalizer.normalize(file.getKey());
      String filename = resolveFilename(file.getFilename(), key);
      String contentType = normalizeContentType(file.getContentType());
      plannedFiles.add(new PlannedFile(binding, key, filename, contentType));
    }
    return plannedFiles;
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
      Iterator<Map.Entry<String, JsonNode>> nodes = outputs.fields();
      while (nodes.hasNext()) {
        Map.Entry<String, JsonNode> node = nodes.next();
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
    Iterator<Map.Entry<String, JsonNode>> mediaFields = nodeOutput.fields();
    while (mediaFields.hasNext()) {
      Map.Entry<String, JsonNode> media = mediaFields.next();
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
    if (!properties.isEnabled()) {
      throw new IllegalStateException(
          "ComfyUI runtime is disabled (kk-studio.comfyui.enabled=false)");
    }
    ComfyUIClient client = comfyUIClientProvider.getIfAvailable();
    if (client == null) {
      throw new IllegalStateException("ComfyUI client is unavailable");
    }
    return client;
  }

  private S3StorageService requireS3StorageService() {
    S3StorageService service = s3StorageServiceProvider.getIfAvailable();
    if (service == null) {
      throw new IllegalStateException(
          "S3 storage is unavailable; enable kk-studio.storage.s3 for ComfyUI file inputs");
    }
    return service;
  }

  private long maxInputFileSizeBytes() {
    if (properties.getMaxInputFileSize() == null
        || properties.getMaxInputFileSize().toBytes() < 0L) {
      throw new IllegalStateException("kk-studio.comfyui.max-input-file-size must not be negative");
    }
    return properties.getMaxInputFileSize().toBytes();
  }

  private Duration blockTimeout() {
    Duration readTimeout = properties.getReadTimeout();
    if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
      throw new IllegalStateException("kk-studio.comfyui.read-timeout must be positive");
    }
    return readTimeout.plusSeconds(1);
  }

  private static String uploadedPath(UploadResult result) {
    if (result == null || !StringUtils.hasText(result.getName())) {
      throw new IllegalStateException("ComfyUI upload returned an invalid filename");
    }
    return StringUtils.hasText(result.getSubfolder())
        ? result.getSubfolder() + "/" + result.getName()
        : result.getName();
  }

  private static String resolveFilename(String requestedFilename, String key) {
    String filename = trimToNull(requestedFilename);
    if (filename == null) {
      int slash = key.lastIndexOf('/');
      filename = slash < 0 ? key : key.substring(slash + 1);
    }
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

  private static String normalizeContentType(String contentType) {
    String normalized = trimToNull(contentType);
    if (normalized == null) {
      return null;
    }
    if (normalized.length() > 255) {
      throw new IllegalArgumentException("contentType is too long");
    }
    try {
      return MimeTypeUtils.parseMimeType(normalized).toString();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("contentType must be a valid media type", e);
    }
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
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

  private record PlannedFile(Binding binding, String key, String filename, String contentType) {}

  private record OutputDescriptor(String filename, String subfolder, String type) {}
}
