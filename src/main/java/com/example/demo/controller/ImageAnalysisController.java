package com.example.demo.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.List;

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

    /**
     * 图片处理核心方法：
     * 1. 读取图片
     * 2. 如果图片太大（长宽超过1024），自动进行等比缩小
     * 3. 统一转换为 JPEG 格式（体积更小）
     * 4. 返回 Base64 字符串
     */
    private String compressAndToBase64(MultipartFile file) {
        try {
            // 1. 读取原始图片
            BufferedImage originalImage = ImageIO.read(file.getInputStream());
            if (originalImage == null) {
                // 如果不是图片，直接返回原始字节（兜底）
                return Base64.getEncoder().encodeToString(file.getBytes());
            }

            // 2. 设定最大尺寸 (VLM模型通常不需要太高分辨率，1024够用了)
            int maxDim = 1024;
            int originalWidth = originalImage.getWidth();
            int originalHeight = originalImage.getHeight();

            // 3. 检查是否需要压缩
            // 如果原本就小于 1024px 且文件小于 1MB，直接用原图
            if (originalWidth <= maxDim && originalHeight <= maxDim && file.getSize() < 1024 * 1024) {
                return Base64.getEncoder().encodeToString(file.getBytes());
            }

            // 4. 计算缩放后的尺寸
            int newWidth = originalWidth;
            int newHeight = originalHeight;

            if (originalWidth > maxDim || originalHeight > maxDim) {
                if (originalWidth > originalHeight) {
                    newWidth = maxDim;
                    newHeight = (int) (originalHeight * ((double) maxDim / originalWidth));
                } else {
                    newHeight = maxDim;
                    newWidth = (int) (originalWidth * ((double) maxDim / originalHeight));
                }
            }

            // 5. 创建新图片并绘制（实现压缩）
            BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resizedImage.createGraphics();
            // 使用平滑缩放算法
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
            g.dispose();

            // 6. 输出为 JPEG 字节流
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(resizedImage, "jpg", baos);

            return Base64.getEncoder().encodeToString(baos.toByteArray());

        } catch (Exception e) {
            throw new RuntimeException("图片处理失败: " + e.getMessage(), e);
        }
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String analyze(
            @RequestParam("image") MultipartFile image,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "systemPrompt", required = false) String systemPromptFromWeb,
            @RequestParam(value = "style", required = false) String style,
            @RequestParam(value = "needStyle", required = false, defaultValue = "false") Boolean needStyle,
            @RequestParam(value = "needEmotion", required = false, defaultValue = "false") Boolean needEmotion
    ) {
        // --- 1. Prompt 构建逻辑 ---
        String basePersona;
        if ("detailed".equals(style)) {
            basePersona = "你是一个专业的图像分析专家，请进行详细、结构化的分析。";
        } else if ("kids".equals(style)) {
            basePersona = "你是一个亲切的儿童讲解员，请用简单、有趣的语言描述图片。";
        } else {
            basePersona = (systemPromptFromWeb != null && !systemPromptFromWeb.isBlank())
                    ? systemPromptFromWeb
                    : systemPrompt;
            if (basePersona == null || basePersona.isBlank()) {
                basePersona = "你是一个有用的图像分析助手。";
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(basePersona).append("\n");

        if (Boolean.TRUE.equals(needStyle)) {
            sb.append("\n【任务一：艺术风格分析】\n");
            sb.append("- 请判断图片的视觉风格（如：写实摄影、极简主义、赛博朋克、印象派油画等）。\n");
        }

        if (Boolean.TRUE.equals(needEmotion)) {
            sb.append("\n【任务二：情绪底色解读】\n");
            sb.append("- 请深入分析画面传递的核心情绪（如：孤独、宁静、焦虑、欢愉）。\n");
        }

        sb.append("\n最后，请回答用户的具体问题：").append(prompt);
        String finalSystemPrompt = sb.toString();


        // --- 2. 构建 HTTP 请求 ---
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // ★★★ 关键修改：使用压缩后的 Base64 ★★★
        String base64Image = compressAndToBase64(image);
        // ★★★ 关键修改：前缀改为 image/jpeg ★★★
        String imageDataUrl = "data:image/jpeg;base64," + base64Image;

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", prompt));
        content.add(Map.of("type", "image_url", "image_url", Map.of("url", imageDataUrl)));

        Map<String, Object> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", finalSystemPrompt);

        Map<String, Object> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", content);

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", List.of(systemMessage, userMessage));

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        // --- 3. 调用 API ---
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
            // 返回具体的错误信息，方便排查
            return "模型调用失败: " + e.getMessage();
        }
    }
}