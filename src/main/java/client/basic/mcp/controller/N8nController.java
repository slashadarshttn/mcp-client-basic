package client.basic.mcp.controller;

import client.basic.mcp.dto.McpClientResponseDto;
import client.basic.mcp.dto.N8nRequestDto;
import client.basic.mcp.service.N8nService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class N8nController {

    private final N8nService n8nService;

    public N8nController(N8nService n8nService) {
        this.n8nService = n8nService;
    }

    @PostMapping("/api/mcp/tools/process-drive-files")
    public ResponseEntity<McpClientResponseDto> processDriveFiles(@RequestBody N8nRequestDto n8nRequestDto) {
        return new ResponseEntity<>(n8nService.mcpToolsCallbacksN8n(n8nRequestDto), HttpStatus.OK);
    }
}
