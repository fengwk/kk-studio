package fun.fengwk.kkstudio.harness.model.provider.impl.openai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI 协议全量 wire/终态文本的基准期望值。
 *
 * <p>全部条目由基准提交 {@code b79652fa4} 的原实现生成：请求体是完整紧凑 JSON（保留字段顺序），流结果包含 增量序列、block 顺序、replay
 * metadata、用量与错误文案。重组编码器或累积器时，任何字段写入顺序、判断顺序、 默认值或错误信息的漂移都会让对应条目失配。新增场景时先在该基准上以 {@code
 * -Djmo.golden.dumpDir=<dir>} 生成取值，再把实际文本贴入本表。
 */
final class OpenAiGoldenValues {

  private OpenAiGoldenValues() {}

  static Map<String, String> all() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put(
        "chat-assistant-fallback",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"},{\"role\":\"assistant\",\"content\":\"reasoninganswer{\\\"note\\\":\\\"diag\\\"}\",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"q\\\":\\\"x\\\"}\"}}]}]}");
    values.put(
        "chat-assistant-native-replay",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"},{\"role\":\"assistant\",\"vendor_future_field\":{\"trace_id\":\"t-1\"},\"audio\":{\"id\":\"audio_1\"},\"function_call\":{\"name\":\"legacy\",\"arguments\":\"{}\"},\"content\":\"answer\"}]}");
    values.put(
        "chat-assistant-replay-mismatch",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay payload: mismatch with durable contents");
    values.put(
        "chat-invalid-tool-schema",
        "INVALID_REQUEST|tool inputSchemaJson must be a JSON object: broken");
    values.put(
        "chat-matrix-0-0-0",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-0-0-1",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"high\",\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-0-0-2",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"none\",\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-0-0-3",
        "{\"temperature\":0.1,\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-0-1-0",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"prompt_cache_key\":\"66666666-6666-6666-6666-666666666666\",\"prompt_cache_retention\":\"in_memory\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-0-1-1",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"high\",\"max_tokens\":1024,\"prompt_cache_key\":\"66666666-6666-6666-6666-666666666666\",\"prompt_cache_retention\":\"in_memory\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-0-1-2",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"none\",\"max_tokens\":1024,\"prompt_cache_key\":\"66666666-6666-6666-6666-666666666666\",\"prompt_cache_retention\":\"in_memory\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-0-1-3",
        "{\"temperature\":0.1,\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"prompt_cache_key\":\"66666666-6666-6666-6666-666666666666\",\"prompt_cache_retention\":\"in_memory\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-0-2-0",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"prompt_cache_key\":\"77777777-7777-7777-7777-777777777777\",\"prompt_cache_retention\":\"24h\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-0-2-1",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"high\",\"max_tokens\":1024,\"prompt_cache_key\":\"77777777-7777-7777-7777-777777777777\",\"prompt_cache_retention\":\"24h\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-0-2-2",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"none\",\"max_tokens\":1024,\"prompt_cache_key\":\"77777777-7777-7777-7777-777777777777\",\"prompt_cache_retention\":\"24h\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-0-2-3",
        "{\"temperature\":0.1,\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"prompt_cache_key\":\"77777777-7777-7777-7777-777777777777\",\"prompt_cache_retention\":\"24h\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-1-0-0",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-1-0-1",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"high\",\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-1-0-2",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"none\",\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-1-0-3",
        "{\"temperature\":0.1,\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-1-1-0",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"66666666-6666-6666-6666-666666666666\",\"prompt_cache_retention\":\"in_memory\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-1-1-1",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"high\",\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"66666666-6666-6666-6666-666666666666\",\"prompt_cache_retention\":\"in_memory\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-1-1-2",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"none\",\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"66666666-6666-6666-6666-666666666666\",\"prompt_cache_retention\":\"in_memory\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-1-1-3",
        "{\"temperature\":0.1,\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"66666666-6666-6666-6666-666666666666\",\"prompt_cache_retention\":\"in_memory\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-1-2-0",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"77777777-7777-7777-7777-777777777777\",\"prompt_cache_retention\":\"24h\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-1-2-1",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"high\",\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"77777777-7777-7777-7777-777777777777\",\"prompt_cache_retention\":\"24h\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-1-2-2",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"none\",\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"77777777-7777-7777-7777-777777777777\",\"prompt_cache_retention\":\"24h\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-1-2-3",
        "{\"temperature\":0.1,\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"77777777-7777-7777-7777-777777777777\",\"prompt_cache_retention\":\"24h\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-2-0-0",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"a\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}},{\"type\":\"function\",\"function\":{\"name\":\"b\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-2-0-1",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"high\",\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"a\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}},{\"type\":\"function\",\"function\":{\"name\":\"b\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-2-0-2",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"none\",\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"a\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}},{\"type\":\"function\",\"function\":{\"name\":\"b\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-2-0-3",
        "{\"temperature\":0.1,\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"a\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}},{\"type\":\"function\",\"function\":{\"name\":\"b\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-2-1-0",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"a\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}},{\"type\":\"function\",\"function\":{\"name\":\"b\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"66666666-6666-6666-6666-666666666666\",\"prompt_cache_retention\":\"in_memory\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-2-1-1",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"high\",\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"a\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}},{\"type\":\"function\",\"function\":{\"name\":\"b\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"66666666-6666-6666-6666-666666666666\",\"prompt_cache_retention\":\"in_memory\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-2-1-2",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"none\",\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"a\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}},{\"type\":\"function\",\"function\":{\"name\":\"b\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"66666666-6666-6666-6666-666666666666\",\"prompt_cache_retention\":\"in_memory\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-2-1-3",
        "{\"temperature\":0.1,\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"a\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}},{\"type\":\"function\",\"function\":{\"name\":\"b\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"66666666-6666-6666-6666-666666666666\",\"prompt_cache_retention\":\"in_memory\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-2-2-0",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"a\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}},{\"type\":\"function\",\"function\":{\"name\":\"b\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"77777777-7777-7777-7777-777777777777\",\"prompt_cache_retention\":\"24h\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-2-2-1",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"high\",\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"a\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}},{\"type\":\"function\",\"function\":{\"name\":\"b\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"77777777-7777-7777-7777-777777777777\",\"prompt_cache_retention\":\"24h\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-2-2-2",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"none\",\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"a\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}},{\"type\":\"function\",\"function\":{\"name\":\"b\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"77777777-7777-7777-7777-777777777777\",\"prompt_cache_retention\":\"24h\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-matrix-2-2-3",
        "{\"temperature\":0.1,\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"a\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}},{\"type\":\"function\",\"function\":{\"name\":\"b\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"77777777-7777-7777-7777-777777777777\",\"prompt_cache_retention\":\"24h\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-mid-system-message",
        "INVALID_REQUEST|mid-conversation system messages are unsupported by OpenAI Chat");
    values.put(
        "chat-native-include-usage",
        "INVALID_REQUEST|protocolOptions must not declare runtime-owned field stream_options.include_usage");
    values.put(
        "chat-native-legacy-tools",
        "INVALID_REQUEST|protocolOptions function_call is unsupported; bind client tools through runtime ProviderToolDefinition");
    values.put(
        "chat-native-merge",
        "{\"temperature\":0.25,\"stream_options\":{\"chunk_include\":true,\"include_usage\":true},\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"metadata\":{\"tenant\":\"t1\"},\"model\":\"gpt-4o\",\"stream\":true,\"max_tokens\":1024,\"prompt_cache_key\":\"33333333-3333-3333-3333-333333333333\",\"prompt_cache_retention\":\"in_memory\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put("chat-native-multi-choice", "INVALID_REQUEST|protocolOptions n must be exactly 1");
    values.put(
        "chat-native-runtime-field",
        "INVALID_REQUEST|protocolOptions must not declare runtime-owned field messages");
    values.put(
        "chat-native-single-choice",
        "{\"n\":1,\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-native-tools-nonempty",
        "INVALID_REQUEST|protocolOptions tools must be empty; bind client tools through runtime ProviderToolDefinition");
    values.put(
        "chat-non-reasoning-effort",
        "{\"model\":\"gpt-4o-mini\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-plain",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-reasoning-conflict",
        "INVALID_REQUEST|protocolOptions must not declare runtime-owned field reasoning_effort/thinking");
    values.put(
        "chat-reasoning-high",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"high\",\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-reasoning-native-thinking",
        "{\"thinking\":{\"type\":\"enabled\"},\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-reasoning-off",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"reasoning_effort\":\"none\",\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-replay-conflicting-fields",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"},{\"role\":\"assistant\",\"content\":\"thought\"}]}");
    values.put(
        "chat-replay-conflicting-function",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"},{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"q\\\":\\\"x\\\"}\"}}]}]}");
    values.put(
        "chat-replay-foreign-protocol",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"},{\"role\":\"assistant\",\"content\":\"answer\"}]}");
    values.put(
        "chat-replay-payload-error-annotations-only",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay annotations");
    values.put(
        "chat-replay-payload-error-array-arguments",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay payload: tool call function arguments must be a JSON object");
    values.put(
        "chat-replay-payload-error-blank-audio-id",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay audio");
    values.put(
        "chat-replay-payload-error-non-array-reasoning-details",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay payload: illegal reasoning_details type");
    values.put(
        "chat-replay-payload-error-non-string-refusal",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay payload: illegal refusal type");
    values.put(
        "chat-replay-plain-durable",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"},{\"role\":\"assistant\",\"content\":\"answer\"}]}");
    values.put(
        "chat-replay-redacted",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"},{\"role\":\"assistant\",\"content\":\"\"}]}");
    values.put(
        "chat-replay-shape-blank-tool-arguments",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay payload: tool call function arguments must be a non-blank string");
    values.put(
        "chat-replay-shape-blank-tool-call-id",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay payload: tool call id must be a non-blank string");
    values.put(
        "chat-replay-shape-blank-tool-name",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay payload: tool call function name must be a non-blank string");
    values.put(
        "chat-replay-shape-missing-tool-call-type",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay payload: tool call type must be function");
    values.put(
        "chat-replay-shape-missing-tool-function",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay payload: tool call function must be a JSON object");
    values.put(
        "chat-replay-shape-non-array-tool-calls",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay payload: illegal tool_calls type");
    values.put(
        "chat-replay-shape-non-object-audio",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay audio");
    values.put(
        "chat-replay-shape-non-object-tool-call",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay payload: tool call must be a JSON object");
    values.put(
        "chat-replay-shape-tool-call-index",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay tool call index");
    values.put(
        "chat-replay-text-mismatch",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay payload: mismatch with durable contents");
    values.put(
        "chat-replay-thinking-mismatch",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay payload: reasoning_content mismatch with durable contents");
    values.put(
        "chat-replay-tool-count-mismatch",
        "INVALID_REQUEST|invalid OpenAI chat assistant replay payload: mismatch with durable contents");
    values.put("chat-stream-after-done", "INVALID_RESPONSE|semantic payload received after [DONE]");
    values.put(
        "chat-stream-conflicting-tail",
        "INVALID_RESPONSE|conflicting finish_reason after finalized choice");
    values.put(
        "chat-stream-filtered",
        "{\"protocolEvents\":[],\"events\":[{\"kind\":\"text\",\"text\":\"blocked\"},{\"kind\":\"toolCall\",\"index\":0,\"id\":\"c1\",\"name\":\"lookup\",\"argumentsJson\":\"{}\"}],\"blocks\":[{\"kind\":\"text\",\"text\":\"blocked\",\"blockData\":null}],\"stopReason\":\"FILTERED\",\"usage\":{\"inputTokens\":2,\"outputTokens\":1,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"cacheWriteByTtl\":[],\"reasoningTokens\":null,\"providerTotalTokens\":null},\"requestId\":null,\"serviceTier\":null,\"rawUsageJson\":\"{\\\"prompt_tokens\\\":2,\\\"completion_tokens\\\":1}\",\"toolCallDiagnostics\":[]}");
    values.put(
        "chat-stream-incomplete-tool-on-complete",
        "INVALID_RESPONSE|invalid tool call arguments JSON");
    values.put(
        "chat-stream-invalid-choice-index",
        "INVALID_RESPONSE|choice index must be a non-negative integer");
    values.put(
        "chat-stream-invalid-usage-field", "INVALID_RESPONSE|invalid usage field: prompt_tokens");
    values.put(
        "chat-stream-length-truncated",
        "{\"protocolEvents\":[],\"events\":[{\"kind\":\"toolCall\",\"index\":0,\"id\":\"c1\",\"name\":\"lookup\",\"argumentsJson\":\"{\\\"q\\\":\"}],\"blocks\":[{\"kind\":\"text\",\"text\":\"\",\"blockData\":null}],\"stopReason\":\"LENGTH\",\"usage\":{\"inputTokens\":4,\"outputTokens\":2,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"cacheWriteByTtl\":[],\"reasoningTokens\":null,\"providerTotalTokens\":null},\"requestId\":null,\"serviceTier\":null,\"rawUsageJson\":\"{\\\"prompt_tokens\\\":4,\\\"completion_tokens\\\":2}\",\"toolCallDiagnostics\":[{\"callIndex\":0,\"id\":\"c1\",\"name\":\"lookup\",\"partialArguments\":\"{\\\"q\\\":\",\"message\":\"tool call truncated due to max tokens\"}]}");
    values.put("chat-stream-missing-done", "INVALID_RESPONSE|stream completed without [DONE]");
    values.put(
        "chat-stream-missing-usage",
        "INVALID_RESPONSE|missing complete usage: OpenAI Chat model gpt-4o");
    values.put(
        "chat-stream-non-string-finish-reason", "INVALID_RESPONSE|finish_reason must be a string");
    values.put(
        "chat-stream-reasoning",
        "{\"protocolEvents\":[],\"events\":[{\"kind\":\"thinking\",\"text\":\"think\"},{\"kind\":\"text\",\"text\":\"answer\"},{\"kind\":\"text\",\"text\":\"nope\"}],\"blocks\":[{\"kind\":\"thinking\",\"text\":\"think\",\"blockData\":{\"protocol\":\"OPENAI_CHAT\",\"affinity\":{\"providerType\":\"OPENAI\",\"providerName\":\"openai\",\"modelId\":\"gpt-4o\"},\"signature\":null,\"encryptedContent\":null,\"itemId\":null,\"itemType\":null,\"contentType\":null,\"refusal\":false,\"thought\":null,\"itemStart\":false,\"nativeFields\":{\"reasoning_details\":[{\"type\":\"summary\"}]}}},{\"kind\":\"text\",\"text\":\"answer\",\"blockData\":{\"protocol\":\"OPENAI_CHAT\",\"affinity\":{\"providerType\":\"OPENAI\",\"providerName\":\"openai\",\"modelId\":\"gpt-4o\"},\"signature\":null,\"encryptedContent\":null,\"itemId\":null,\"itemType\":null,\"contentType\":null,\"refusal\":false,\"thought\":null,\"itemStart\":false,\"nativeFields\":{}}},{\"kind\":\"text\",\"text\":\"nope\",\"blockData\":{\"protocol\":\"OPENAI_CHAT\",\"affinity\":{\"providerType\":\"OPENAI\",\"providerName\":\"openai\",\"modelId\":\"gpt-4o\"},\"signature\":null,\"encryptedContent\":null,\"itemId\":null,\"itemType\":null,\"contentType\":null,\"refusal\":true,\"thought\":null,\"itemStart\":false,\"nativeFields\":{}}},{\"kind\":\"opaque\",\"blockType\":\"openai_chat_message\",\"blockData\":{\"protocol\":\"OPENAI_CHAT\",\"affinity\":{\"providerType\":\"OPENAI\",\"providerName\":\"openai\",\"modelId\":\"gpt-4o\"},\"signature\":null,\"encryptedContent\":null,\"itemId\":null,\"itemType\":null,\"contentType\":null,\"refusal\":false,\"thought\":null,\"itemStart\":false,\"nativeFields\":{\"vendor_future_field\":{\"trace_id\":\"t-1\"}}}}],\"stopReason\":\"COMPLETE\",\"usage\":{\"inputTokens\":3,\"outputTokens\":2,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"cacheWriteByTtl\":[],\"reasoningTokens\":null,\"providerTotalTokens\":5},\"requestId\":\"c-2\",\"serviceTier\":null,\"rawUsageJson\":\"{\\\"prompt_tokens\\\":3,\\\"completion_tokens\\\":2,\\\"total_tokens\\\":5}\",\"toolCallDiagnostics\":[]}");
    values.put(
        "chat-stream-semantic-tail",
        "INVALID_RESPONSE|semantic delta received after finalized choice");
    values.put(
        "chat-stream-text-tool",
        "{\"protocolEvents\":[],\"events\":[{\"kind\":\"text\",\"text\":\"Hel\"},{\"kind\":\"text\",\"text\":\"lo\"},{\"kind\":\"toolCall\",\"index\":0,\"id\":\"call_1\",\"name\":\"lookup\",\"argumentsJson\":\"{\\\"q\\\":\"},{\"kind\":\"toolCall\",\"index\":0,\"id\":null,\"name\":null,\"argumentsJson\":\"\\\"x\\\"}\"}],\"blocks\":[{\"kind\":\"text\",\"text\":\"Hello\",\"blockData\":{\"protocol\":\"OPENAI_CHAT\",\"affinity\":{\"providerType\":\"OPENAI\",\"providerName\":\"openai\",\"modelId\":\"gpt-4o\"},\"signature\":null,\"encryptedContent\":null,\"itemId\":null,\"itemType\":null,\"contentType\":null,\"refusal\":false,\"thought\":null,\"itemStart\":false,\"nativeFields\":{}}},{\"kind\":\"toolCall\",\"call\":{\"id\":\"call_1\",\"name\":\"lookup\",\"argumentsJson\":\"{\\\"q\\\":\\\"x\\\"}\",\"historyAction\":null},\"blockData\":{\"protocol\":\"OPENAI_CHAT\",\"affinity\":{\"providerType\":\"OPENAI\",\"providerName\":\"openai\",\"modelId\":\"gpt-4o\"},\"signature\":null,\"encryptedContent\":null,\"itemId\":null,\"itemType\":null,\"contentType\":null,\"refusal\":false,\"thought\":null,\"itemStart\":false,\"nativeFields\":{}}}],\"stopReason\":\"COMPLETE\",\"usage\":{\"inputTokens\":8,\"outputTokens\":5,\"cacheReadTokens\":2,\"cacheWriteTokens\":0,\"cacheWriteByTtl\":[],\"reasoningTokens\":1,\"providerTotalTokens\":15},\"requestId\":\"c-1\",\"serviceTier\":\"default\",\"rawUsageJson\":\"{\\\"prompt_tokens\\\":10,\\\"completion_tokens\\\":5,\\\"total_tokens\\\":15,\\\"prompt_tokens_details\\\":{\\\"cached_tokens\\\":2},\\\"completion_tokens_details\\\":{\\\"reasoning_tokens\\\":1}}\",\"toolCallDiagnostics\":[]}");
    values.put(
        "chat-stream-tool-function-not-object",
        "INVALID_RESPONSE|tool call function must be an object");
    values.put(
        "chat-stream-unsupported-finish-reason",
        "INVALID_RESPONSE|unsupported finish_reason: weird");
    values.put("chat-stream-usage-not-object", "INVALID_RESPONSE|usage must be a JSON object");
    values.put(
        "chat-tool-result-unsupported-block",
        "INVALID_REQUEST|TOOL message contains unsupported block: ProviderImageBlock");
    values.put(
        "chat-tools-cache-long",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}},{\"type\":\"function\",\"function\":{\"name\":\"search\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"22222222-2222-2222-2222-222222222222\",\"prompt_cache_retention\":\"24h\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-tools-cache-none",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-tools-cache-short",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}}],\"prompt_cache_key\":\"11111111-1111-1111-1111-111111111111\",\"prompt_cache_retention\":\"in_memory\",\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":\"Hello\"}]}");
    values.put(
        "chat-user-audio",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Transcribe\"},{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"BBBB\",\"format\":\"wav\"}}]}]}");
    values.put(
        "chat-user-audio-url",
        "INVALID_REQUEST|OpenAI chat audio requires base64 data; downloading or fabricating from URL is not supported");
    values.put(
        "chat-user-audio-url-before-video", "INVALID_REQUEST|OpenAI chat does not support VIDEO");
    values.put(
        "chat-user-document-type", "INVALID_REQUEST|unsupported document media type: text/plain");
    values.put(
        "chat-user-parts",
        "{\"model\":\"gpt-4o\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"max_tokens\":1024,\"messages\":[{\"role\":\"system\",\"content\":\"You are helpful.\"},{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Describe\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}},{\"type\":\"text\",\"text\":\"and\"},{\"type\":\"file\",\"file\":{\"file_data\":\"data:application/pdf;base64,JVBERi0xLjQK\",\"filename\":\"pdf_file\"}}]}]}");
    values.put("chat-user-video", "INVALID_REQUEST|OpenAI chat does not support VIDEO");
    values.put(
        "responses-assistant-fallback",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"tools\":[{\"type\":\"function\",\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"],\"additionalProperties\":false},\"strict\":true}],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]},{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"reasoninganswer{\\\"note\\\":\\\"diag\\\"}\"}]},{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"lookup\",\"arguments\":\"{\\\"q\\\":\\\"x\\\"}\"},{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"tail\"}]}]}");
    values.put(
        "responses-assistant-native-replay",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"tools\":[{\"type\":\"function\",\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"],\"additionalProperties\":false},\"strict\":true}],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]},{\"status\":\"completed\",\"id\":\"rs_1\",\"type\":\"reasoning\",\"encrypted_content\":\"enc_1\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"thought\"}]},{\"status\":\"completed\",\"role\":\"assistant\",\"id\":\"msg_1\",\"type\":\"message\",\"content\":[{\"annotations\":[{\"type\":\"url_citation\"}],\"type\":\"output_text\",\"text\":\"answer\"}]},{\"status\":\"completed\",\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"lookup\",\"arguments\":\"{\\\"q\\\":\\\"x\\\"}\"}]}");
    values.put(
        "responses-cache-short",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"55555555-5555-5555-5555-555555555555\",\"prompt_cache_retention\":\"in_memory\"}");
    values.put(
        "responses-matrix-0-0-0",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}]}");
    values.put(
        "responses-matrix-0-0-1",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"reasoning\":{\"effort\":\"high\",\"summary\":\"auto\"},\"include\":[\"reasoning.encrypted_content\"],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}]}");
    values.put(
        "responses-matrix-0-0-2",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"reasoning\":{\"effort\":\"none\"},\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}]}");
    values.put(
        "responses-matrix-0-0-3",
        "{\"include\":[\"message.output_text.logprobs\"],\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}]}");
    values.put(
        "responses-matrix-0-1-0",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"88888888-8888-8888-8888-888888888888\",\"prompt_cache_retention\":\"in_memory\"}");
    values.put(
        "responses-matrix-0-1-1",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"reasoning\":{\"effort\":\"high\",\"summary\":\"auto\"},\"include\":[\"reasoning.encrypted_content\"],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"88888888-8888-8888-8888-888888888888\",\"prompt_cache_retention\":\"in_memory\"}");
    values.put(
        "responses-matrix-0-1-2",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"reasoning\":{\"effort\":\"none\"},\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"88888888-8888-8888-8888-888888888888\",\"prompt_cache_retention\":\"in_memory\"}");
    values.put(
        "responses-matrix-0-1-3",
        "{\"include\":[\"message.output_text.logprobs\"],\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"88888888-8888-8888-8888-888888888888\",\"prompt_cache_retention\":\"in_memory\"}");
    values.put(
        "responses-matrix-0-2-0",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"99999999-9999-9999-9999-999999999999\",\"prompt_cache_retention\":\"24h\"}");
    values.put(
        "responses-matrix-0-2-1",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"reasoning\":{\"effort\":\"high\",\"summary\":\"auto\"},\"include\":[\"reasoning.encrypted_content\"],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"99999999-9999-9999-9999-999999999999\",\"prompt_cache_retention\":\"24h\"}");
    values.put(
        "responses-matrix-0-2-2",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"reasoning\":{\"effort\":\"none\"},\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"99999999-9999-9999-9999-999999999999\",\"prompt_cache_retention\":\"24h\"}");
    values.put(
        "responses-matrix-0-2-3",
        "{\"include\":[\"message.output_text.logprobs\"],\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"99999999-9999-9999-9999-999999999999\",\"prompt_cache_retention\":\"24h\"}");
    values.put(
        "responses-matrix-1-0-0",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"tools\":[{\"type\":\"function\",\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"],\"additionalProperties\":false},\"strict\":true}],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}]}");
    values.put(
        "responses-matrix-1-0-1",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"reasoning\":{\"effort\":\"high\",\"summary\":\"auto\"},\"include\":[\"reasoning.encrypted_content\"],\"tools\":[{\"type\":\"function\",\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"],\"additionalProperties\":false},\"strict\":true}],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}]}");
    values.put(
        "responses-matrix-1-0-2",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"reasoning\":{\"effort\":\"none\"},\"tools\":[{\"type\":\"function\",\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"],\"additionalProperties\":false},\"strict\":true}],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}]}");
    values.put(
        "responses-matrix-1-0-3",
        "{\"include\":[\"message.output_text.logprobs\"],\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"tools\":[{\"type\":\"function\",\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"],\"additionalProperties\":false},\"strict\":true}],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}]}");
    values.put(
        "responses-matrix-1-1-0",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"tools\":[{\"type\":\"function\",\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"],\"additionalProperties\":false},\"strict\":true}],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"88888888-8888-8888-8888-888888888888\",\"prompt_cache_retention\":\"in_memory\"}");
    values.put(
        "responses-matrix-1-1-1",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"reasoning\":{\"effort\":\"high\",\"summary\":\"auto\"},\"include\":[\"reasoning.encrypted_content\"],\"tools\":[{\"type\":\"function\",\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"],\"additionalProperties\":false},\"strict\":true}],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"88888888-8888-8888-8888-888888888888\",\"prompt_cache_retention\":\"in_memory\"}");
    values.put(
        "responses-matrix-1-1-2",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"reasoning\":{\"effort\":\"none\"},\"tools\":[{\"type\":\"function\",\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"],\"additionalProperties\":false},\"strict\":true}],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"88888888-8888-8888-8888-888888888888\",\"prompt_cache_retention\":\"in_memory\"}");
    values.put(
        "responses-matrix-1-1-3",
        "{\"include\":[\"message.output_text.logprobs\"],\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"tools\":[{\"type\":\"function\",\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"],\"additionalProperties\":false},\"strict\":true}],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"88888888-8888-8888-8888-888888888888\",\"prompt_cache_retention\":\"in_memory\"}");
    values.put(
        "responses-matrix-1-2-0",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"tools\":[{\"type\":\"function\",\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"],\"additionalProperties\":false},\"strict\":true}],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"99999999-9999-9999-9999-999999999999\",\"prompt_cache_retention\":\"24h\"}");
    values.put(
        "responses-matrix-1-2-1",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"reasoning\":{\"effort\":\"high\",\"summary\":\"auto\"},\"include\":[\"reasoning.encrypted_content\"],\"tools\":[{\"type\":\"function\",\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"],\"additionalProperties\":false},\"strict\":true}],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"99999999-9999-9999-9999-999999999999\",\"prompt_cache_retention\":\"24h\"}");
    values.put(
        "responses-matrix-1-2-2",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"reasoning\":{\"effort\":\"none\"},\"tools\":[{\"type\":\"function\",\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"],\"additionalProperties\":false},\"strict\":true}],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"99999999-9999-9999-9999-999999999999\",\"prompt_cache_retention\":\"24h\"}");
    values.put(
        "responses-matrix-1-2-3",
        "{\"include\":[\"message.output_text.logprobs\"],\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"tools\":[{\"type\":\"function\",\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"],\"additionalProperties\":false},\"strict\":true}],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"99999999-9999-9999-9999-999999999999\",\"prompt_cache_retention\":\"24h\"}");
    values.put("responses-output-budget", "INVALID_REQUEST|max_output_tokens must be at least 16");
    values.put(
        "responses-plain",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}]}");
    values.put(
        "responses-reasoning-high",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"reasoning\":{\"effort\":\"high\",\"summary\":\"auto\"},\"include\":[\"reasoning.encrypted_content\"],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}]}");
    values.put(
        "responses-reasoning-native",
        "{\"reasoning\":{\"summary\":\"concise\",\"verbosity\":\"low\",\"effort\":\"low\"},\"include\":[\"message.output_text.logprobs\",\"reasoning.encrypted_content\"],\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}]}");
    values.put(
        "responses-reasoning-off",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"reasoning\":{\"effort\":\"none\"},\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}]}");
    values.put(
        "responses-replay-empty-reasoning-placeholder",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}]}");
    values.put(
        "responses-replay-shape-blank-message-id",
        "INVALID_REQUEST|replay message id must be non-blank string");
    values.put(
        "responses-replay-shape-blank-message-phase",
        "INVALID_REQUEST|replay message phase must be non-blank string");
    values.put(
        "responses-replay-shape-blank-reasoning-encrypted",
        "INVALID_REQUEST|replay reasoning encrypted_content must be non-blank string");
    values.put(
        "responses-replay-shape-function-call-blank-name",
        "INVALID_REQUEST|function_call missing non-blank string name");
    values.put(
        "responses-replay-shape-function-call-invalid-arguments",
        "INVALID_REQUEST|function_call arguments must be a JSON object");
    values.put(
        "responses-replay-shape-function-call-missing-arguments",
        "INVALID_REQUEST|function_call missing string arguments");
    values.put(
        "responses-replay-shape-function-call-missing-identity",
        "INVALID_REQUEST|function_call missing call_id or id");
    values.put(
        "responses-replay-shape-message-content-missing-type",
        "INVALID_REQUEST|replay message content block must have string type");
    values.put(
        "responses-replay-shape-message-content-not-object",
        "INVALID_REQUEST|replay message content block must be an object");
    values.put(
        "responses-replay-shape-output-text-missing-text",
        "INVALID_REQUEST|replay output_text block must have string text");
    values.put(
        "responses-replay-shape-reasoning-summary-missing-text",
        "INVALID_REQUEST|replay reasoning summary block must have string text");
    values.put(
        "responses-replay-shape-reasoning-summary-not-object",
        "INVALID_REQUEST|replay reasoning summary block must be an object");
    values.put(
        "responses-replay-shape-reasoning-summary-wrong-type",
        "INVALID_REQUEST|replay reasoning summary block type must be 'summary_text'");
    values.put(
        "responses-replay-shape-refusal-missing-refusal",
        "INVALID_REQUEST|replay refusal block must have string refusal");
    values.put(
        "responses-replay-text-mismatch",
        "INVALID_REQUEST|replay text content mismatch with durable message content");
    values.put(
        "responses-replay-thinking-mismatch",
        "INVALID_REQUEST|replay thinking content mismatch with durable message thinking");
    values.put(
        "responses-replay-tool-count-mismatch",
        "INVALID_REQUEST|replay tool calls count mismatch with durable tool calls");
    values.put(
        "responses-replay-unknown-item",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]},{\"type\":\"web_search_call\",\"id\":\"ws_1\",\"action\":{\"query\":\"x\"}}]}");
    values.put(
        "responses-runtime-effort-conflict",
        "INVALID_REQUEST|protocolOptions conflicts with the runtime-owned request field: reasoning.effort");
    values.put(
        "responses-stateful-field",
        "INVALID_REQUEST|protocolOptions conflicts with the stateless replay invariant: previous_response_id");
    values.put(
        "responses-stream-after-terminal", "semantic event received after terminal response");
    values.put(
        "responses-stream-complete-missing-arguments",
        "INVALID_RESPONSE|incomplete tool call missing arguments");
    values.put(
        "responses-stream-duplicate-item-identity",
        "INVALID_RESPONSE|duplicate output item has a different identity");
    values.put(
        "responses-stream-failed-event",
        "TRANSIENT|{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}}}");
    values.put(
        "responses-stream-incomplete",
        "{\"protocolEvents\":[],\"events\":[{\"kind\":\"toolCall\",\"index\":0,\"id\":\"call_2\",\"name\":\"lookup\",\"argumentsJson\":null},{\"kind\":\"toolCall\",\"index\":0,\"id\":null,\"name\":null,\"argumentsJson\":\"{\\\"q\\\":\\\"P\"}],\"blocks\":[],\"stopReason\":\"LENGTH\",\"usage\":{\"inputTokens\":7,\"outputTokens\":3,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"cacheWriteByTtl\":[],\"reasoningTokens\":null,\"providerTotalTokens\":null},\"requestId\":\"resp_2\",\"serviceTier\":null,\"rawUsageJson\":\"{\\\"input_tokens\\\":7,\\\"output_tokens\\\":3}\",\"toolCallDiagnostics\":[{\"callIndex\":0,\"id\":\"call_2\",\"name\":\"lookup\",\"partialArguments\":\"{\\\"q\\\":\\\"P\",\"message\":\"tool call arguments truncated or malformed\"}]}");
    values.put(
        "responses-stream-incomplete-usage",
        "INVALID_RESPONSE|missing complete usage: OpenAI Responses model gpt-5.4-mini");
    values.put(
        "responses-stream-invalid-reasoning-content",
        "INVALID_RESPONSE|reasoning content must be an array");
    values.put(
        "responses-stream-missing-usage",
        "INVALID_RESPONSE|missing complete usage: OpenAI Responses model gpt-5.4-mini");
    values.put(
        "responses-stream-output-not-array",
        "INVALID_RESPONSE|response output must be a JSON array");
    values.put(
        "responses-stream-premature-eof",
        "INVALID_RESPONSE|OpenAI Responses stream ended unexpectedly before complete message received (premature EOF)");
    values.put(
        "responses-stream-tool",
        "{\"protocolEvents\":[],\"events\":[{\"kind\":\"text\",\"text\":\"Hel\"},{\"kind\":\"thinking\",\"text\":\"think\"},{\"kind\":\"toolCall\",\"index\":0,\"id\":\"call_1\",\"name\":\"lookup\",\"argumentsJson\":null},{\"kind\":\"toolCall\",\"index\":0,\"id\":null,\"name\":null,\"argumentsJson\":\"{\\\"q\\\":\"}],\"blocks\":[{\"kind\":\"thinking\",\"text\":\"think\",\"blockData\":{\"protocol\":\"OPENAI_RESPONSES\",\"affinity\":{\"providerType\":\"OPENAI_RESPONSES\",\"providerName\":\"openai_test\",\"modelId\":\"gpt-5.4-mini\"},\"signature\":null,\"encryptedContent\":\"enc_1\",\"itemId\":\"rs_1\",\"itemType\":\"reasoning\",\"contentType\":\"summary_text\",\"refusal\":false,\"thought\":null,\"itemStart\":true,\"nativeFields\":{\"summaryPartFields\":{},\"itemFields\":{\"status\":\"completed\"}}}},{\"kind\":\"text\",\"text\":\"Hello\",\"blockData\":{\"protocol\":\"OPENAI_RESPONSES\",\"affinity\":{\"providerType\":\"OPENAI_RESPONSES\",\"providerName\":\"openai_test\",\"modelId\":\"gpt-5.4-mini\"},\"signature\":null,\"encryptedContent\":null,\"itemId\":\"msg_1\",\"itemType\":\"message\",\"contentType\":\"output_text\",\"refusal\":false,\"thought\":null,\"itemStart\":true,\"nativeFields\":{\"itemFields\":{\"status\":\"completed\",\"role\":\"assistant\"},\"contentFields\":{\"annotations\":[{\"type\":\"url_citation\"}]}}}},{\"kind\":\"toolCall\",\"call\":{\"id\":\"call_1\",\"name\":\"lookup\",\"argumentsJson\":\"{\\\"q\\\":\\\"x\\\"}\",\"historyAction\":null},\"blockData\":{\"protocol\":\"OPENAI_RESPONSES\",\"affinity\":{\"providerType\":\"OPENAI_RESPONSES\",\"providerName\":\"openai_test\",\"modelId\":\"gpt-5.4-mini\"},\"signature\":null,\"encryptedContent\":null,\"itemId\":\"fc_1\",\"itemType\":\"function_call\",\"contentType\":null,\"refusal\":false,\"thought\":null,\"itemStart\":true,\"nativeFields\":{\"itemFields\":{\"status\":\"completed\"}}}}],\"stopReason\":\"COMPLETE\",\"usage\":{\"inputTokens\":8,\"outputTokens\":5,\"cacheReadTokens\":2,\"cacheWriteTokens\":0,\"cacheWriteByTtl\":[],\"reasoningTokens\":1,\"providerTotalTokens\":15},\"requestId\":\"resp_1\",\"serviceTier\":\"default\",\"rawUsageJson\":\"{\\\"input_tokens\\\":10,\\\"output_tokens\\\":5,\\\"total_tokens\\\":15,\\\"input_tokens_details\\\":{\\\"cached_tokens\\\":2},\\\"output_tokens_details\\\":{\\\"reasoning_tokens\\\":1}}\",\"toolCallDiagnostics\":[]}");
    values.put(
        "responses-stream-tool-done-only",
        "{\"protocolEvents\":[],\"events\":[],\"blocks\":[{\"kind\":\"toolCall\",\"call\":{\"id\":\"call_3\",\"name\":\"lookup\",\"argumentsJson\":\"{\\\"q\\\":\\\"x\\\"}\",\"historyAction\":null},\"blockData\":{\"protocol\":\"OPENAI_RESPONSES\",\"affinity\":{\"providerType\":\"OPENAI_RESPONSES\",\"providerName\":\"openai_test\",\"modelId\":\"gpt-5.4-mini\"},\"signature\":null,\"encryptedContent\":null,\"itemId\":\"fc_3\",\"itemType\":\"function_call\",\"contentType\":null,\"refusal\":false,\"thought\":null,\"itemStart\":true,\"nativeFields\":{\"itemFields\":{\"status\":\"completed\"}}}}],\"stopReason\":\"COMPLETE\",\"usage\":{\"inputTokens\":4,\"outputTokens\":2,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"cacheWriteByTtl\":[],\"reasoningTokens\":null,\"providerTotalTokens\":null},\"requestId\":\"resp_3\",\"serviceTier\":null,\"rawUsageJson\":\"{\\\"input_tokens\\\":4,\\\"output_tokens\\\":2}\",\"toolCallDiagnostics\":[]}");
    values.put(
        "responses-stream-tool-duplicate-added",
        "{\"protocolEvents\":[],\"events\":[{\"kind\":\"toolCall\",\"index\":0,\"id\":\"call_6\",\"name\":\"lookup\",\"argumentsJson\":null}],\"blocks\":[{\"kind\":\"toolCall\",\"call\":{\"id\":\"call_6\",\"name\":\"lookup\",\"argumentsJson\":\"{\\\"q\\\":\\\"z\\\"}\",\"historyAction\":null},\"blockData\":{\"protocol\":\"OPENAI_RESPONSES\",\"affinity\":{\"providerType\":\"OPENAI_RESPONSES\",\"providerName\":\"openai_test\",\"modelId\":\"gpt-5.4-mini\"},\"signature\":null,\"encryptedContent\":null,\"itemId\":null,\"itemType\":\"function_call\",\"contentType\":null,\"refusal\":false,\"thought\":null,\"itemStart\":true,\"nativeFields\":{\"itemFields\":{}}}}],\"stopReason\":\"COMPLETE\",\"usage\":{\"inputTokens\":3,\"outputTokens\":2,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"cacheWriteByTtl\":[],\"reasoningTokens\":null,\"providerTotalTokens\":null},\"requestId\":\"resp_6\",\"serviceTier\":null,\"rawUsageJson\":\"{\\\"input_tokens\\\":3,\\\"output_tokens\\\":2}\",\"toolCallDiagnostics\":[]}");
    values.put(
        "responses-stream-tool-terminal-only",
        "{\"protocolEvents\":[],\"events\":[],\"blocks\":[{\"kind\":\"toolCall\",\"call\":{\"id\":\"call_4\",\"name\":\"lookup\",\"argumentsJson\":\"{\\\"q\\\":\\\"y\\\"}\",\"historyAction\":null},\"blockData\":{\"protocol\":\"OPENAI_RESPONSES\",\"affinity\":{\"providerType\":\"OPENAI_RESPONSES\",\"providerName\":\"openai_test\",\"modelId\":\"gpt-5.4-mini\"},\"signature\":null,\"encryptedContent\":null,\"itemId\":null,\"itemType\":\"function_call\",\"contentType\":null,\"refusal\":false,\"thought\":null,\"itemStart\":true,\"nativeFields\":{\"itemFields\":{\"status\":\"completed\"}}}}],\"stopReason\":\"COMPLETE\",\"usage\":{\"inputTokens\":4,\"outputTokens\":2,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"cacheWriteByTtl\":[],\"reasoningTokens\":null,\"providerTotalTokens\":null},\"requestId\":\"resp_4\",\"serviceTier\":null,\"rawUsageJson\":\"{\\\"input_tokens\\\":4,\\\"output_tokens\\\":2}\",\"toolCallDiagnostics\":[]}");
    values.put(
        "responses-stream-tool-truncated-identity",
        "{\"protocolEvents\":[],\"events\":[{\"kind\":\"toolCall\",\"index\":0,\"id\":\"call_5\",\"name\":null,\"argumentsJson\":null}],\"blocks\":[],\"stopReason\":\"LENGTH\",\"usage\":{\"inputTokens\":2,\"outputTokens\":1,\"cacheReadTokens\":0,\"cacheWriteTokens\":0,\"cacheWriteByTtl\":[],\"reasoningTokens\":null,\"providerTotalTokens\":null},\"requestId\":\"resp_5\",\"serviceTier\":null,\"rawUsageJson\":\"{\\\"input_tokens\\\":2,\\\"output_tokens\\\":1}\",\"toolCallDiagnostics\":[{\"callIndex\":0,\"id\":\"call_5\",\"name\":null,\"partialArguments\":\"\",\"message\":\"tool call truncated before identity received\"}]}");
    values.put(
        "responses-tools-cache",
        "{\"tools\":[{\"type\":\"web_search\"},{\"type\":\"mcp\",\"require_approval\":\"never\",\"server_label\":\"s\"},{\"type\":\"function\",\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"],\"additionalProperties\":false},\"strict\":true}],\"background\":false,\"temperature\":0.4,\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Hello\"}]}],\"prompt_cache_key\":\"44444444-4444-4444-4444-444444444444\",\"prompt_cache_retention\":\"24h\"}");
    values.put(
        "responses-unsupported-tool",
        "INVALID_REQUEST|protocolOptions tools[0] must be a supported hosted tool; bind client tools through runtime ProviderToolDefinition");
    values.put(
        "responses-user-parts",
        "{\"model\":\"gpt-5.4-mini\",\"stream\":true,\"store\":false,\"instructions\":\"You are helpful.\",\"max_output_tokens\":1024,\"tools\":[{\"type\":\"function\",\"name\":\"lookup\",\"description\":\"Look things up.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"],\"additionalProperties\":false},\"strict\":true}],\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Describe\"},{\"type\":\"input_image\",\"image_url\":\"https://example.com/i.png\",\"detail\":\"auto\"},{\"type\":\"input_file\",\"file_data\":\"data:application/pdf;base64,JVBERi0xLjQK\",\"filename\":\"document.pdf\"}]},{\"type\":\"function_call_output\",\"call_id\":\"call_1\",\"output\":[{\"type\":\"input_text\",\"text\":\"result\"},{\"type\":\"input_text\",\"text\":\"{\\\"n\\\":1}\"},{\"type\":\"input_image\",\"image_url\":\"https://example.com/r.png\",\"detail\":\"auto\"}]}]}");
    return Map.copyOf(values);
  }
}
