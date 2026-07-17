package fun.fengwk.kkstudio.core.comfyui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import fun.fengwk.convention4j.comfyui.ComfyUIClient;
import fun.fengwk.convention4j.comfyui.ComfyUIJob;
import fun.fengwk.convention4j.comfyui.PromptSubmission;
import fun.fengwk.convention4j.comfyui.input.UploadResult;
import fun.fengwk.convention4j.comfyui.workflow.Workflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Mono;

import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiBindings;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiBindingsParser;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiLookupService;
import fun.fengwk.kkstudio.core.storage.S3ObjectContent;
import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowJobDTO;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowRunDTO;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowRunFileDTO;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowRunRequestDTO;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ComfyuiRuntimeService} 核心无状态映射测试。
 *
 * @author fengwk
 */
public class ComfyuiRuntimeServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private ComfyuiWorkflowApiLookupService lookupService;
  private ComfyUIClient client;
  private S3StorageService s3StorageService;
  private ComfyuiProperties properties;
  private ComfyuiRuntimeService runtimeService;

  @BeforeEach
  @SuppressWarnings("unchecked")
  public void setUp() {
    lookupService = mock(ComfyuiWorkflowApiLookupService.class);
    client = mock(ComfyUIClient.class);
    s3StorageService = mock(S3StorageService.class);
    ObjectProvider<ComfyUIClient> clientProvider = mock(ObjectProvider.class);
    ObjectProvider<S3StorageService> s3Provider = mock(ObjectProvider.class);
    when(clientProvider.getIfAvailable()).thenReturn(client);
    when(s3Provider.getIfAvailable()).thenReturn(s3StorageService);
    properties = new ComfyuiProperties();
    properties.setEnabled(true);
    properties.setReadTimeout(Duration.ofSeconds(2));
    properties.setMaxInputFileSize(DataSize.ofBytes(1024));
    runtimeService =
        new ComfyuiRuntimeService(
            lookupService, properties, clientProvider, s3Provider, objectMapper);
  }

  @Test
  public void shouldMapParametersAndS3FilesBeforeStatelessSubmit() {
    ComfyuiWorkflowApiBindings bindings =
        parseBindings(
            "{\n"
                + "  \"1\":{\"class_type\":\"TextNode\",\"inputs\":{\"text\":\"old\"}},\n"
                + "  \"2\":{\"class_type\":\"LoadImage\",\"inputs\":{\"image\":\"old.png\"}}\n"
                + "}",
            "[\n"
                + "  {\"name\":\"prompt\",\"kind\":\"parameter\",\"nodeId\":\"1\",\"inputName\":\"text\",\"valueType\":\"string\",\"required\":true},\n"
                + "  {\"name\":\"image\",\"kind\":\"file\",\"nodeId\":\"2\",\"inputName\":\"image\",\"required\":true}\n"
                + "]",
            "$.files[0]");
    when(lookupService.findEnabledBindings("image-gen")).thenReturn(Optional.of(bindings));
    when(s3StorageService.download("inputs/source.png", 1024L))
        .thenReturn(new S3ObjectContent(new byte[] {1, 2, 3}, "image/png"));
    when(client.uploadFile(eq("source.png"), any(byte[].class), eq("image/png")))
        .thenReturn(Mono.just(new UploadResult("renamed.png", "staged")));
    when(client.submit(any(Workflow.class)))
        .thenReturn(
            Mono.just(new PromptSubmission("run-1", 1D, JsonNodeFactory.instance.objectNode())));

    ComfyuiWorkflowRunRequestDTO request = new ComfyuiWorkflowRunRequestDTO();
    request.setParameters(Map.of("prompt", "hello"));
    ComfyuiWorkflowRunFileDTO file = new ComfyuiWorkflowRunFileDTO();
    file.setKey("inputs/source.png");
    request.setFiles(Map.of("image", file));

    // 捕获最终 workflow，证明文件使用 ComfyUI 实际返回路径而不是调用方文件名。
    ComfyuiWorkflowRunDTO result = runtimeService.run("image-gen", request);

    assertEquals("run-1", result.getRunId());
    assertEquals("pending", result.getStatus());
    assertEquals("$.files[0]", result.getDefaultSelector());
    ArgumentCaptor<Workflow> workflowCaptor = ArgumentCaptor.forClass(Workflow.class);
    verify(client).submit(workflowCaptor.capture());
    assertEquals("hello", workflowCaptor.getValue().getNode("1").getInput("text").asText());
    assertEquals(
        "staged/renamed.png", workflowCaptor.getValue().getNode("2").getInput("image").asText());
  }

  @Test
  public void shouldUploadMultipleFilesSequentiallyInBindingOrder() {
    when(lookupService.findEnabledBindings("merge"))
        .thenReturn(
            Optional.of(
                parseBindings(
                    "{\n"
                        + "  \"1\":{\"class_type\":\"LoadImage\",\"inputs\":{\"image\":\"a.png\"}},\n"
                        + "  \"2\":{\"class_type\":\"LoadImage\",\"inputs\":{\"image\":\"b.png\"}}\n"
                        + "}",
                    "[\n"
                        + "  {\"name\":\"first\",\"kind\":\"file\",\"nodeId\":\"1\",\"inputName\":\"image\",\"required\":true},\n"
                        + "  {\"name\":\"second\",\"kind\":\"file\",\"nodeId\":\"2\",\"inputName\":\"image\",\"required\":true}\n"
                        + "]",
                    null)));
    when(s3StorageService.download("inputs/a.png", 1024L))
        .thenReturn(new S3ObjectContent(new byte[] {1}, "image/png"));
    when(s3StorageService.download("inputs/b.png", 1024L))
        .thenReturn(new S3ObjectContent(new byte[] {2}, "image/png"));
    when(client.uploadFile(eq("a.png"), any(byte[].class), eq("image/png")))
        .thenReturn(Mono.just(new UploadResult("a.png", "")));
    when(client.uploadFile(eq("b.png"), any(byte[].class), eq("image/png")))
        .thenReturn(Mono.just(new UploadResult("b.png", "")));
    when(client.submit(any(Workflow.class)))
        .thenReturn(
            Mono.just(new PromptSubmission("run-2", 2D, JsonNodeFactory.instance.objectNode())));
    ComfyuiWorkflowRunFileDTO first = new ComfyuiWorkflowRunFileDTO();
    first.setKey("inputs/a.png");
    ComfyuiWorkflowRunFileDTO second = new ComfyuiWorkflowRunFileDTO();
    second.setKey("inputs/b.png");
    ComfyuiWorkflowRunRequestDTO request = new ComfyuiWorkflowRunRequestDTO();
    request.setFiles(Map.of("second", second, "first", first));

    runtimeService.run("merge", request);

    // 即使 request map 无序，外部副作用仍严格按 binding 顺序串行执行。
    InOrder order = inOrder(s3StorageService, client);
    order.verify(s3StorageService).download("inputs/a.png", 1024L);
    order.verify(client).uploadFile(eq("a.png"), any(byte[].class), eq("image/png"));
    order.verify(s3StorageService).download("inputs/b.png", 1024L);
    order.verify(client).uploadFile(eq("b.png"), any(byte[].class), eq("image/png"));
    order.verify(client).submit(any(Workflow.class));
  }

  @Test
  public void shouldValidateAllInputsBeforeFileSideEffects() {
    when(lookupService.findEnabledBindings("image-gen"))
        .thenReturn(
            Optional.of(
                parseBindings(
                    "{\"2\":{\"class_type\":\"LoadImage\",\"inputs\":{\"image\":\"old.png\"}}}",
                    "[{\"name\":\"image\",\"kind\":\"file\",\"nodeId\":\"2\",\"inputName\":\"image\",\"required\":true}]",
                    null)));
    ComfyuiWorkflowRunRequestDTO request = new ComfyuiWorkflowRunRequestDTO();
    request.setParameters(Map.of("unknown", "value"));

    // 未知字段必须在任何 S3/ComfyUI 副作用前被拒绝。
    assertThrows(IllegalArgumentException.class, () -> runtimeService.run("image-gen", request));
    verifyNoInteractions(s3StorageService);
    verify(client, never()).uploadFile(anyString(), any(byte[].class), anyString());
    verify(client, never()).submit(any(Workflow.class));
  }

  @Test
  public void shouldRejectEmptyFilenameDerivedFromTrailingSlashKeyBeforeSideEffects() {
    when(lookupService.findEnabledBindings("image-gen"))
        .thenReturn(
            Optional.of(
                parseBindings(
                    "{\"2\":{\"class_type\":\"LoadImage\",\"inputs\":{\"image\":\"old.png\"}}}",
                    "[{\"name\":\"image\",\"kind\":\"file\",\"nodeId\":\"2\",\"inputName\":\"image\",\"required\":true}]",
                    null)));
    ComfyuiWorkflowRunFileDTO file = new ComfyuiWorkflowRunFileDTO();
    file.setKey("inputs/");
    ComfyuiWorkflowRunRequestDTO request = new ComfyuiWorkflowRunRequestDTO();
    request.setFiles(Map.of("image", file));

    assertThrows(IllegalArgumentException.class, () -> runtimeService.run("image-gen", request));

    verifyNoInteractions(s3StorageService);
    verify(client, never()).uploadFile(anyString(), any(byte[].class), anyString());
    verify(client, never()).submit(any(Workflow.class));
  }

  @Test
  public void shouldNormalizeTerminalOutputsAndApplySelector() throws Exception {
    JsonNode outputs =
        objectMapper.readTree(
            "{\n"
                + "  \"9\": {\n"
                + "    \"images\": [\n"
                + "      {\"filename\":\"out.png\",\"subfolder\":\"final\",\"type\":\"output\"}\n"
                + "    ],\n"
                + "    \"text\": [\"done\"]\n"
                + "  }\n"
                + "}");
    when(client.getJob("run-9")).thenReturn(Mono.just(job("run-9", "completed", outputs)));

    ComfyuiWorkflowJobDTO whole = runtimeService.getJob("run-9", null);
    JsonNode root = objectMapper.valueToTree(whole.getResult());
    assertEquals("out.png", root.path("files").path(0).path("filename").asText());
    assertEquals(
        "/api/comfyui/runs/run-9/files/9/images/0",
        root.path("files").path(0).path("downloadUrl").asText());
    assertEquals("done", root.path("outputs").path("9").path("text").path(0).asText());

    // selector 只读取规范化根对象，并可返回标量 JSON 值。
    ComfyuiWorkflowJobDTO selected = runtimeService.getJob("run-9", "$.files[0].filename");
    assertEquals("out.png", objectMapper.valueToTree(selected.getResult()).asText());
  }

  @Test
  public void shouldRejectDangerousSelectorBeforeQueryingComfyUI() {
    // 禁止递归下降和 regex，避免把不受控的昂贵表达式发送到 JSONPath 引擎。
    assertThrows(
        IllegalArgumentException.class, () -> runtimeService.getJob("run-1", "$..filename"));
    assertThrows(
        IllegalArgumentException.class,
        () -> runtimeService.getJob("run-1", "$[?(@.name =~ /x/)]"));
    verify(client, never()).getJob(anyString());
  }

  @Test
  public void shouldResolveDownloadOnlyFromJobScopedDescriptor() throws Exception {
    JsonNode outputs =
        objectMapper.readTree(
            "{\"9\":{\"images\":[{\"filename\":\"safe.png\",\"subfolder\":\"final\",\"type\":\"output\"}]}}");
    when(client.getJob("run-9")).thenReturn(Mono.just(job("run-9", "completed", outputs)));
    when(client.getFile("safe.png", "final", "output")).thenReturn(Mono.just(new byte[] {4, 5}));

    // URL 只携带 node/media/index，真实 filename/subfolder/type 必须从该 job 重新解析。
    ComfyuiFileDownload download = runtimeService.downloadFile("run-9", "9", "images", 0);
    assertEquals("safe.png", download.getFilename());
    assertEquals("image/png", download.getContentType());
    assertArrayEquals(new byte[] {4, 5}, download.getBytes());

    assertThrows(
        IllegalArgumentException.class,
        () -> runtimeService.downloadFile("run-9", "9", "images", 1));
    verify(client).getFile("safe.png", "final", "output");
    verify(client, never()).getFile(eq("unsafe.png"), anyString(), anyString());
  }

  @Test
  public void shouldFailClearlyOnlyWhenDisabledRuntimeIsInvoked() {
    properties.setEnabled(false);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> runtimeService.getJob("run-1", null));

    assertEquals(
        "ComfyUI runtime is disabled (kk-studio.comfyui.enabled=false)", error.getMessage());
    verifyNoInteractions(client);
  }

  private ComfyuiWorkflowApiBindings parseBindings(
      String workflowJson, String bindingsJson, String defaultSelector) {
    return new ComfyuiWorkflowApiBindingsParser(objectMapper)
        .parse(workflowJson, bindingsJson, defaultSelector);
  }

  private static ComfyUIJob job(String id, String status, JsonNode outputs) {
    return new ComfyUIJob(
        id,
        status,
        1D,
        100L,
        200L,
        "workflow-1",
        110L,
        190L,
        outputs == null ? 0 : 1,
        outputs,
        null,
        null,
        null,
        null);
  }
}
