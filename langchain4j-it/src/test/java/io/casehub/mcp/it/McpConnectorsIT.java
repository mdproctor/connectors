package io.casehub.mcp.it;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test: LangChain4j + LLM against the CaseHub MCP server.
 *
 * Prerequisites:
 *   mvn -f mcp-server-connectors/pom.xml clean package -DskipTests -Dhttp
 *
 * Run (Ollama default — requires Ollama running with llama3.1:8b):
 *   mvn -f langchain4j-it/pom.xml verify
 *
 * Run with Anthropic:
 *   mvn -f langchain4j-it/pom.xml verify -Dllm.provider=anthropic -Dllm.model=claude-sonnet-4-20250514
 *
 * Configuration (system properties):
 *   llm.provider   — ollama (default) or anthropic
 *   llm.model      — model name (default: llama3.1:8b for ollama)
 *   llm.base-url   — Ollama base URL (default: http://localhost:11434)
 */
class McpConnectorsIT {

    static final int PORT = 18765;
    static McpClient mcpClient;
    static Process serverProcess;

    static Path serverDir() {
        String override = System.getProperty("mcp.server.dir");
        if (override != null) return Path.of(override);
        return Path.of(System.getProperty("user.dir"))
                   .resolve("../mcp-server-connectors/target/quarkus-app");
    }

    @BeforeAll
    static void startMcpServer() throws Exception {
        Path dir = serverDir().toAbsolutePath();
        Path jar = dir.resolve("quarkus-run.jar");
        assertThat(jar).as("MCP server JAR — build with: mvn -f mcp-server-connectors/pom.xml clean package -DskipTests -Dhttp")
                .exists();

        String javaHome = System.getProperty("java.home");
        String javaBin = Path.of(javaHome, "bin", "java").toString();

        serverProcess = new ProcessBuilder(javaBin,
                "-Dquarkus.http.port=" + PORT,
                "-Dquarkus.http.host=127.0.0.1",
                "-jar", jar.toString())
                .directory(dir.toFile())
                .start();

        drainStderr();
        waitForServerReady();

        var transport = new HttpMcpTransport.Builder()
                .sseUrl("http://127.0.0.1:" + PORT + "/mcp/sse")
                .timeout(Duration.ofSeconds(30))
                .build();

        mcpClient = new DefaultMcpClient.Builder()
                .transport(transport)
                .toolExecutionTimeout(Duration.ofSeconds(30))
                .build();
    }

    static void drainStderr() {
        Thread t = new Thread(() -> {
            try (var r = new BufferedReader(new InputStreamReader(serverProcess.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.err.println("[mcp-server] " + line);
                }
            } catch (Exception ignored) {}
        });
        t.setDaemon(true);
        t.start();
    }

    static void waitForServerReady() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                var conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + PORT + "/mcp/sse")
                        .toURL().openConnection();
                conn.setConnectTimeout(500);
                conn.setReadTimeout(500);
                conn.setRequestMethod("GET");
                conn.getResponseCode();
                conn.disconnect();
                return;
            } catch (Exception ignored) {}
            Thread.sleep(500);
        }
        throw new IllegalStateException("MCP server did not start within 30 seconds");
    }

    @AfterAll
    static void stopMcpServer() throws Exception {
        if (mcpClient != null) mcpClient.close();
        if (serverProcess != null) {
            serverProcess.destroyForcibly();
            serverProcess.waitFor();
        }
    }

    // --- Model configuration ---

    static ChatModel buildModel() {
        String provider = System.getProperty("llm.provider", "ollama");
        return switch (provider) {
            case "ollama" -> {
                String model = System.getProperty("llm.model", "llama3.1:8b");
                String baseUrl = System.getProperty("llm.base-url", "http://localhost:11434");
                yield OllamaChatModel.builder()
                        .baseUrl(baseUrl)
                        .modelName(model)
                        .timeout(Duration.ofSeconds(120))
                        .build();
            }
            case "anthropic" -> {
                String apiKey = System.getenv("ANTHROPIC_API_KEY");
                assumeTrue(apiKey != null && !apiKey.isBlank(), "ANTHROPIC_API_KEY not set");
                String model = System.getProperty("llm.model", "claude-sonnet-4-20250514");
                yield AnthropicChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(model)
                        .build();
            }
            default -> throw new IllegalArgumentException("Unknown llm.provider: " + provider);
        };
    }

    static void assumeModelAvailable() {
        String provider = System.getProperty("llm.provider", "ollama");
        if ("ollama".equals(provider)) {
            String baseUrl = System.getProperty("llm.base-url", "http://localhost:11434");
            try {
                var conn = (HttpURLConnection) URI.create(baseUrl).toURL().openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                conn.disconnect();
                assumeTrue(code == 200, "Ollama not reachable at " + baseUrl);
            } catch (Exception e) {
                assumeTrue(false, "Ollama not reachable at " + baseUrl + ": " + e.getMessage());
            }
        } else if ("anthropic".equals(provider)) {
            assumeTrue(System.getenv("ANTHROPIC_API_KEY") != null, "ANTHROPIC_API_KEY not set");
        }
    }

    interface Assistant {
        String chat(String message);
    }

    static Assistant buildAssistant(ChatModel model) {
        ToolProvider toolProvider = McpToolProvider.builder()
                .mcpClients(List.of(mcpClient))
                .build();

        return AiServices.builder(Assistant.class)
                .chatModel(model)
                .toolProvider(toolProvider)
                .build();
    }

    // --- Direct tool execution tests (no LLM needed) ---

    @Test
    void canListVoices() {
        var request = ToolExecutionRequest.builder()
                .name("connectors_voice_listVoices")
                .arguments("{}")
                .build();

        String result = mcpClient.executeTool(request);

        assertThat(result)
                .contains("af_heart")
                .contains("bm_daniel")
                .contains("kokoro");
    }

    @Test
    void canSpeakAndTranscribe() {
        var speakRequest = ToolExecutionRequest.builder()
                .name("connectors_voice_speak")
                .arguments("{\"text\": \"Hello world\"}")
                .build();

        String speakResult = mcpClient.executeTool(speakRequest);

        assertThat(speakResult).contains("audioBase64");

        String audioBase64 = speakResult.replaceAll(
                ".*\"audioBase64\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        var transcribeRequest = ToolExecutionRequest.builder()
                .name("connectors_voice_transcribe")
                .arguments("{\"audioBase64\": \"" + audioBase64 + "\"}")
                .build();

        String transcribeResult = mcpClient.executeTool(transcribeRequest);

        assertThat(transcribeResult).containsIgnoringCase("hello");
    }

    @Test
    void canListCalendars() {
        var request = ToolExecutionRequest.builder()
                .name("connectors_calendar_listCalendars")
                .arguments("{\"platform\": \"ref\"}")
                .build();

        String result = mcpClient.executeTool(request);

        assertThat(result)
                .contains("cal-jonny")
                .contains("cal-sarah")
                .contains("cal-family");
    }

    @Test
    void canGetConnectorStatus() {
        var request = ToolExecutionRequest.builder()
                .name("connectors_operations_connectorStatus")
                .arguments("{}")
                .build();

        String result = mcpClient.executeTool(request);

        assertThat(result)
                .contains("email")
                .contains("slack");
    }

    // --- LLM-driven integration examples ---
    // Each mirrors an existing playbook tutorial, re-implemented as LLM + MCP.

    @Test
    void llmCanListCalendars() {
        assumeModelAvailable();
        Assistant assistant = buildAssistant(buildModel());

        String answer = assistant.chat(
                "What calendars are available on the 'ref' platform? "
                + "Use the calendar tools to find out. "
                + "Return just the calendar IDs as a comma-separated list.");

        assertThat(answer.toLowerCase())
                .contains("cal-jonny")
                .contains("cal-sarah");
    }

    @Test
    void llmCanSearchContacts() {
        assumeModelAvailable();
        Assistant assistant = buildAssistant(buildModel());

        String answer = assistant.chat(
                "Search for 'Bob Jenkins' in contacts on the 'ref' platform "
                + "using platform ID 'ref'. Return his email address and phone number.");

        assertThat(answer.toLowerCase())
                .contains("bob@bobsplumbing.co.uk");
    }

    @Test
    void llmCanSearchNearbyRestaurants() {
        assumeModelAvailable();
        Assistant assistant = buildAssistant(buildModel());

        String answer = assistant.chat(
                "Search for restaurants near Balham, London "
                + "(latitude 51.443, longitude -0.1525, radius 2000 metres) "
                + "on the 'ref' platform. List the restaurant names you find.");

        assertThat(answer).satisfiesAnyOf(
                a -> assertThat(a).containsIgnoringCase("Pizza Express"),
                a -> assertThat(a).containsIgnoringCase("Crown"));
    }

    @Test
    void llmCanSearchProducts() {
        assumeModelAvailable();
        Assistant assistant = buildAssistant(buildModel());

        String answer = assistant.chat(
                "Search for 'headphones' products on the 'ref' commerce platform. "
                + "Tell me the name and price of what you find.");

        assertThat(answer).satisfiesAnyOf(
                a -> assertThat(a).containsIgnoringCase("Sony"),
                a -> assertThat(a).containsIgnoringCase("WH-1000XM5"),
                a -> assertThat(a).contains("299"));
    }

    @Test
    void llmCanSearchTransport() {
        assumeModelAvailable();
        Assistant assistant = buildAssistant(buildModel());

        String answer = assistant.chat(
                "Search for transport options from 'London' to 'Brighton' "
                + "on the 'ref' travel platform for date '2026-12-01'. "
                + "List the available options.");

        assertThat(answer.toLowerCase())
                .satisfiesAnyOf(
                        a -> assertThat(a).containsIgnoringCase("brighton"),
                        a -> assertThat(a).containsIgnoringCase("coach"),
                        a -> assertThat(a).containsIgnoringCase("victoria"));
    }

    @Test
    void llmCanDoVoiceRoundTrip() {
        assumeModelAvailable();
        Assistant assistant = buildAssistant(buildModel());

        String answer = assistant.chat(
                "Use the voice tools to do a round-trip test: "
                + "1. First, list the available voices to see what is registered. "
                + "2. Then, synthesise the text 'Hello from LangChain4j' to speech audio. "
                + "3. Finally, transcribe that audio back to text. "
                + "Report: what voices are available, what platform and voice the synthesis used, "
                + "and what the transcription returned.");

        assertThat(answer.toLowerCase())
                .containsIgnoringCase("hello");
    }

    // --- Multi-step LLM examples ---
    // These chain multiple tool calls, mirroring the playbook multi-step patterns.

    @Test
    void llmCanSearchAndAddToCart() {
        assumeModelAvailable();
        Assistant assistant = buildAssistant(buildModel());

        String answer = assistant.chat(
                "Do these steps on the 'ref' commerce platform: "
                + "1. Search for 'headphones' products. "
                + "2. Add the first product from the results to my cart (quantity 1). "
                + "3. View my cart. "
                + "Tell me what product is in the cart and its price.");

        assertThat(answer).satisfiesAnyOf(
                a -> assertThat(a).containsIgnoringCase("Sony"),
                a -> assertThat(a).containsIgnoringCase("WH-1000XM5"),
                a -> assertThat(a).containsIgnoringCase("cart"));
    }

    @Test
    void llmCanFindRestaurantAndGetDirections() {
        assumeModelAvailable();
        Assistant assistant = buildAssistant(buildModel());

        String answer = assistant.chat(
                "Do these steps on the 'ref' location platform: "
                + "1. Search for restaurants by category 'restaurant' near Balham "
                + "(latitude 51.443, longitude -0.1525, radius 2000 metres). "
                + "2. Get the full details of the first restaurant in the results. "
                + "3. Get walking directions from Balham station "
                + "(origin latitude 51.4432, origin longitude -0.1522) "
                + "to that restaurant's coordinates. "
                + "Tell me the restaurant name, address, and walking duration.");

        assertThat(answer).satisfiesAnyOf(
                a -> assertThat(a).containsIgnoringCase("Pizza Express"),
                a -> assertThat(a).containsIgnoringCase("Crown"),
                a -> assertThat(a).containsIgnoringCase("Balham"));
    }

    @Test
    void llmCanLookUpContactAndCheckCalendar() {
        assumeModelAvailable();
        Assistant assistant = buildAssistant(buildModel());

        String answer = assistant.chat(
                "Do these steps: "
                + "1. Search for 'Dr. Patel' in contacts on the 'ref' platform (use platformId 'ref'). "
                + "2. List the calendars available on the 'ref' calendar platform. "
                + "Tell me Dr. Patel's email and phone, and the calendar IDs.");

        assertThat(answer.toLowerCase())
                .contains("cal-jonny")
                .satisfiesAnyOf(
                        a -> assertThat(a).contains("patel"),
                        a -> assertThat(a).contains("elmstreetsurgery"));
    }
}
