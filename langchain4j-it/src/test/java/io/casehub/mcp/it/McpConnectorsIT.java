package io.casehub.mcp.it;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: LangChain4j + Claude against the CaseHub MCP server.
 *
 * Prerequisites:
 *   mvn -f mcp-server-connectors/pom.xml clean package -DskipTests -Dhttp
 *
 * Run:
 *   mvn -f langchain4j-it/pom.xml verify
 *
 * The LLM test requires ANTHROPIC_API_KEY and is skipped without it.
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

    @Test
    @Disabled("LangChain4j beta5 NPEs on $ref in JSON Schema — contacts tools use $defs/LabelledValue")
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
    void llmCanUseToolsToAnswerQuestion() {
        ChatModel model = AnthropicChatModel.builder()
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .modelName("claude-sonnet-4-20250514")
                .build();

        ToolProvider toolProvider = McpToolProvider.builder()
                .mcpClients(List.of(mcpClient))
                .build();

        interface Assistant {
            String chat(String message);
        }

        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .toolProvider(toolProvider)
                .build();

        String answer = assistant.chat(
                "What calendars are available on the 'ref' platform? "
                + "Return just the calendar IDs as a comma-separated list.");

        assertThat(answer.toLowerCase())
                .contains("cal-jonny")
                .contains("cal-sarah");
    }
}
