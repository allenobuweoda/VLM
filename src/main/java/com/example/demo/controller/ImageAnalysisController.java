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
            @RequestParam(value = "systemPrompt", required = false)
            String systemPromptFromWeb,
            @RequestParam(value = "style", required = false)
                    String style
    ) {
        String finalSystemPrompt;

        if("detailed".equals(style)) {
            finalSystemPrompt = "你是一个专业的图像分析专家，请进行详细、结构化的分析。";
        } else if ("kids".equals(style)) {
            finalSystemPrompt = "你是一个儿童讲解员，请用简单、有趣的语言描述图片。";

        }else {
            finalSystemPrompt =  (systemPromptFromWeb != null && !systemPromptFromWeb.isBlank())
                        ? systemPromptFromWeb
                        : systemPrompt;}
        // 1️⃣ header
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 2️⃣ 图片 base64
        String base64Image = toBase64(image);
        String imageDataUrl = "data:image/png;base64," + base64Image;

        // 3️⃣ message content（text + image）
        List<Map<String, Object>> content = new ArrayList<>();

        content.add(Map.of(
                "type", "text",
                "text", prompt
        ));

        content.add(Map.of(
                "type", "image_url",
                "image_url", Map.of("url", imageDataUrl)
        ));
// system 提示词
        Map<String, Object> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", finalSystemPrompt);


        // 4️⃣ messages
        Map<String, Object> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", content);

        // 5️⃣ body
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", List.of(systemMessage, userMessage));

        HttpEntity<Map<String, Object>> request =
                new HttpEntity<>(body, headers);

        // 6️⃣ call OpenAI
        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl + "/v1/chat/completions",
                request,
                String.class
        );

        try {
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
            return "解析模型返回失败";
        }


    }
}
