package com.example.demo.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@CrossOrigin
@RestController
public class ImageAnalysisController {

    @Value("${openai.api-key}")
    private String apiKey;

    @Value("${openai.model}")
    private String model;

    @Value("${openai.base-url}")
    private String baseUrl;

    @Value("${openai.system-prompt}")
    private String systemPrompt;

    private final RestTemplate restTemplate = new RestTemplate();

    private String toBase64(MultipartFile file) {
        try {
            return Base64.getEncoder().encodeToString(file.getBytes());
        } catch (Exception e) {
            throw new RuntimeException("图片读取失败", e);
        }
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String analyze(
            @RequestParam("image") MultipartFile image,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "systemPrompt", required = false) String systemPromptFromWeb,
            @RequestParam(value = "style", required = false) String style
    ) {
        String finalSystemPrompt;
        boolean jsonMode = false; // 标记是否启用 JSON 模式

        // 根据 style 选择不同的 Prompt 策略
        if ("detailed".equals(style)) {
            finalSystemPrompt = "你是一个专业的图像分析专家，请进行详细、结构化的分析。";
        } else if ("kids".equals(style)) {
            finalSystemPrompt = "你是一个儿童讲解员，请用简单、有趣的语言描述图片。";
        } else if ("education_report".equals(style)) {
            // === [PDF Step 2 & 3] 教育评估专用 Prompt ===
            jsonMode = true;
            finalSystemPrompt = """
                你是一个专业的教育心理学与艺术教育助手。请分析学生的绘画，并严格以 JSON 格式输出以下字段：
                1. "content_description": 客观描述画面画了什么（内容识别）。
                2. "stylistic_features": 描述画面的风格特征（如：抽象、几何结构、线条流畅、色彩丰富、情绪化等）。
                3. "interest_tags": 提取 3-5 个兴趣导向的标签（非诊断性）。
                4. "interest_interpretation": 基于画面特征，推测学生的潜在学习兴趣和认知倾向（例如：几何图形多可能代表逻辑推理兴趣）。
                
                请直接返回纯 JSON 对象，不要包含 Markdown 格式标记（如 ```json）。
                """;
        } else {
            finalSystemPrompt = (systemPromptFromWeb != null && !systemPromptFromWeb.isBlank())
                    ? systemPromptFromWeb
                    : systemPrompt;
        }

        // 1️⃣ Header
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 2️⃣ 图片 Base64
        String base64Image = toBase64(image);
        String imageDataUrl = "data:image/png;base64," + base64Image;

        // 3️⃣ Message Content (Text + Image)
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", prompt));
        content.add(Map.of("type", "image_url", "image_url", Map.of("url", imageDataUrl)));

        // System Message
        Map<String, Object> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", finalSystemPrompt);

        // User Message
        Map<String, Object> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", content);

        // 4️⃣ Construct Body
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", List.of(systemMessage, userMessage));

        // 关键：如果是教育报告模式，强制开启 JSON Mode，保证输出结构稳定
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        // 5️⃣ Call OpenAI API
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    baseUrl + "/v1/chat/completions",
                    request,
                    String.class
            );

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.getBody());

            String answer = root
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText();

            return answer;

        } catch (Exception e) {
            e.printStackTrace();
            return "{\"error\": \"模型调用失败: " + e.getMessage() + "\"}";
        }
    }
}