package client.basic.mcp.service.impl;

import client.basic.mcp.dto.McpClientResponseDto;
import client.basic.mcp.dto.N8nRequestDto;
import client.basic.mcp.service.N8nService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Simplified N8N MCP service that:
 * 1. Uses OpenAI LLM to decide which N8N tool to call
 * 2. Calls N8N webhooks directly via RestTemplate (no MCP client library needed)
 */
@Service
public class N8nServiceImpl implements N8nService {

    private static final Logger log = LoggerFactory.getLogger(N8nServiceImpl.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${n8n.base-url:http://localhost:5678}")
    private String n8nBaseUrl;

    @Value("${n8n.webhook.list-files-path:/webhook/list-files}")
    private String listFilesPath;

    @Value("${n8n.webhook.process-video-path:/webhook/process-file}")
    private String processVideoPath;

    public N8nServiceImpl(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = new ObjectMapper();
        this.restTemplate = createRestTemplate();
    }

    /**
     * RestTemplate only for n8n webhooks. Uses {@link SimpleClientHttpRequestFactory} (URLConnection),
     * not {@code JdkClientHttpRequestFactory} / {@code java.net.http.HttpClient}.
     */
    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        return new RestTemplate(factory);
    }

    @Override
    public McpClientResponseDto mcpToolsCallbacksN8n(N8nRequestDto n8nRequestDto) {
        try {
            // Step 1: Get file list from N8N
            String fileList = callListFiles();
            log.info("Files from Google Drive: {}", fileList);

            // Step 2: Ask LLM to decide what to do
            String llmDecision = askLlmForDecision(n8nRequestDto, fileList);
            log.info("LLM decision: {}", llmDecision);

            // Step 3: Parse LLM response and extract file_id
            String fileId = extractFileIdFromLlmResponse(llmDecision, fileList);

            McpClientResponseDto response = new McpClientResponseDto();

            if (StringUtils.hasText(fileId)) {
                // Step 4: Call process_video webhook with the file_id
                String processResult = callProcessVideo(fileId);
                log.info("Process video result: {}", processResult);
                response.setResponse("File processing started for file_id: " + fileId + ". " + processResult);
            } else {
                response.setResponse("LLM response: " + llmDecision);
            }

            return response;

        } catch (Exception e) {
            log.error("Error processing request", e);
            McpClientResponseDto errorResponse = new McpClientResponseDto();
            errorResponse.setResponse("Error: " + e.getMessage());
            return errorResponse;
        }
    }

    /**
     * Calls the list-files webhook to get all files from Google Drive
     */
    private String callListFiles() {
        String url = n8nBaseUrl + listFilesPath;
        String body = "{}";

        // Print equivalent curl command for Postman/terminal testing
        log.info("======= CURL COMMAND =======");
        log.info("curl --location --request POST '{}' --header 'Content-Type: application/json' --data-raw '{}'", url, body);
        log.info("============================");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        log.info("Sending POST to: {} | Body: {}", url, body);
        long startTime = System.currentTimeMillis();

        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("list-files response status: {} | Time taken: {}ms", response.getStatusCode(), elapsed);
        log.info("list-files response body: {}", response.getBody());
        return response.getBody();
    }

    /**
     * Calls the process-video webhook with the given file_id
     */
    private String callProcessVideo(String fileId) throws Exception {
        String url = n8nBaseUrl + processVideoPath;
        String body = objectMapper.writeValueAsString(Map.of("file_id", fileId));

        // Print equivalent curl command for Postman/terminal testing
        log.info("======= CURL COMMAND =======");
        log.info("curl --location --request POST '{}' --header 'Content-Type: application/json' --data-raw '{}'", url, body);
        log.info("============================");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        log.info("Sending POST to: {} | Body: {}", url, body);
        long startTime = System.currentTimeMillis();

        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("process-video response status: {} | Time taken: {}ms", response.getStatusCode(), elapsed);
        log.info("process-video response body: {}", response.getBody());
        return response.getBody();
    }

    /**
     * Asks the LLM to decide which file to process based on user request and available files
     */
    private String askLlmForDecision(N8nRequestDto dto, String fileList) {
        String systemPrompt = """
        You are a file processing assistant. You receive a list of files that have been retrieved from Google Drive.
        
        Here are the available files:
        %s
        
        Your job:
        1. Find the file that best matches the user's request
        2. Return ONLY a JSON response in this exact format: {"file_id": "THE_FILE_ID", "file_name": "THE_FILE_NAME"}
        3. If no file matches, return: {"file_id": null, "message": "No matching file found"}
        
        IMPORTANT: Return ONLY the JSON, no other text.
        """.formatted(fileList);

        String userMessage;
        if (dto != null && StringUtils.hasText(dto.getFileName())) {
            userMessage = "Process the file: " + dto.getFileName().trim();
        } else {
            userMessage = "List all available files";
        }

        Prompt prompt = new Prompt(
                List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage)),
                OpenAiChatOptions.builder().temperature(0.0).build()
        );

        ChatResponse chatResponse = chatClient.prompt(prompt).call().chatResponse();

        if (chatResponse != null && chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null) {
            return chatResponse.getResult().getOutput().getText();
        }
        return "{}";
    }

    /**
     * Extracts file_id from the LLM's JSON response
     */
    private String extractFileIdFromLlmResponse(String llmResponse, String fileList) {
        try {
            // Clean up LLM response (remove markdown code blocks if present)
            String cleaned = llmResponse.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }

            JsonNode node = objectMapper.readTree(cleaned);
            JsonNode fileIdNode = node.get("file_id");
            if (fileIdNode != null && !fileIdNode.isNull()) {
                return fileIdNode.asText();
            }
        } catch (Exception e) {
            log.warn("Could not parse LLM response as JSON: {}. Trying to extract file_id from file list.", llmResponse);
            return tryExtractFileIdFromFileList(llmResponse, fileList);
        }
        return null;
    }

    /**
     * Fallback: tries to find a file_id by matching file names in the response
     */
    private String tryExtractFileIdFromFileList(String llmResponse, String fileList) {
        try {
            JsonNode files = objectMapper.readTree(fileList);
            if (files.isArray()) {
                for (JsonNode file : files) {
                    String name = file.has("name") ? file.get("name").asText() : "";
                    String id = file.has("id") ? file.get("id").asText() : "";
                    if (StringUtils.hasText(name) && llmResponse.toLowerCase().contains(name.toLowerCase())) {
                        return id;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse file list for fallback extraction", e);
        }
        return null;
    }
}
