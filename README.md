# mcp-client-basic

Spring Boot service that integrates with an n8n workflow by calling n8n webhooks and using an OpenAI model (via Spring AI) to decide which Drive file to process.

## What it does

- **Fetches files**: calls an n8n webhook to get a list of Google Drive files.
- **Chooses a file**: asks an LLM to pick the best matching file for the requested `fileName`.
- **Triggers processing**: calls an n8n webhook with the selected `file_id`.

## Requirements

- **Java**: 17
- **n8n**: running and reachable (default `http://localhost:5678`)
- **OpenAI API key**: set `OPENAI_API_KEY` (required for file selection)

## Configuration

Configuration lives in `src/main/resources/application.properties`.

- **HTTP server**
  - `server.port` (default `8082`)
- **n8n webhook URLs**
  - `n8n.base-url` (default `http://localhost:5678`)
  - `n8n.webhook.list-files-path` (default `/webhook/list-files`)
  - `n8n.webhook.process-video-path` (default `/webhook/process-file`)
- **OpenAI**
  - `spring.ai.openai.api-key` (reads `OPENAI_API_KEY`)
  - `spring.ai.openai.chat.options.model` (currently `gpt-5.4`)

## Run locally

```bash
export OPENAI_API_KEY="..."
./gradlew bootRun
```

Service starts on `http://localhost:8082`.

## API

### `POST /api/mcp/tools/process-drive-files`

Request body:

```json
{ "fileName": "some-file.pdf" }
```

- If `fileName` is omitted/blank, the service asks the LLM to “list all available files”.

Response body:

```json
{ "response": "..." }
```

Example:

```bash
curl -X POST "http://localhost:8082/api/mcp/tools/process-drive-files" \
  -H "Content-Type: application/json" \
  -d '{"fileName":"resume.pdf"}'
```

## n8n webhook expectations

The service expects:

- **List-files webhook** (`n8n.base-url` + `n8n.webhook.list-files-path`):
  - Returns a JSON array of file objects containing at least `id` and `name`.
- **Process-file webhook** (`n8n.base-url` + `n8n.webhook.process-video-path`):
  - Accepts JSON like:

```json
{ "file_id": "..." }
```

## Tests

```bash
./gradlew test
```
