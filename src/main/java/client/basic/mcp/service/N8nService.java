package client.basic.mcp.service;

import client.basic.mcp.dto.McpClientResponseDto;
import client.basic.mcp.dto.N8nRequestDto;

public interface N8nService {

    McpClientResponseDto mcpToolsCallbacksN8n(N8nRequestDto n8nRequestDto);
}
