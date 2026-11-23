package io.casehub.mcp.it;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
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
        assertThat(jar).as("MCP server JAR — build with: mvn -f mcp-server-connectors/pom.xml package -DskipTests -Dhttp")
                .exists();

        String javaHome = System.getProperty("java.home");
        String javaBin = Path.of(javaHome, "bin", "java").toString();
        String seedDir = dir.resolve("corpus").toString() + "/";

        serverProcess = new ProcessBuilder(javaBin,
                "-Dquarkus.http.port=" + PORT,
                "-Dquarkus.http.host=127.0.0.1",
                "-Dcasehub.ref.seed-dir=" + seedDir,
                "-jar", jar.toString())
                .directory(dir.toFile())
                .redirectErrorStream(false)
                .start();

        waitForServerReady();

        var transport = new HttpMcpTransport.Builder()
                .sseUrl("http://127.0.0.1:" + PORT + "/mcp/sse")
                .logRequests(true)
                .logResponses(true)
                .timeout(Duration.ofSeconds(30))
                .build();

        mcpClient = new DefaultMcpClient.Builder()
                .transport(transport)
                .build();
    }

    static void waitForServerReady() throws Exception {
        var stderr = new BufferedReader(new InputStreamReader(serverProcess.getErrorStream()));
        Thread stderrReader = new Thread(() -> {
            try {
                String line;
                while ((line = stderr.readLine()) != null) {
                    System.err.println("[server] " + line);
                }
            } catch (Exception e) { /* ignore */ }
        });
        stderrReader.setDaemon(true);
        stderrReader.start();

        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                var conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + PORT + "/mcp/sse")
                        .toURL().openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code < 500) return;
            } catch (Exception ignored) { /* server not ready yet */ }
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
    void toolsAreDiscovered() {
        List<ToolSpecification> tools = mcpClient.listTools();

        assertThat(tools).hasSizeGreaterThan(50);

        List<String> toolNames = tools.stream()
                .map(ToolSpecification::name)
                .toList();

        assertThat(toolNames).as("chat tools discovered")
                .anyMatch(n -> n.contains("chat"));
        assertThat(toolNames).as("calendar tools discovered")
                .anyMatch(n -> n.contains("calendar"));
        assertThat(toolNames).as("contacts tools discovered")
                .anyMatch(n -> n.contains("contacts"));
    }

    @Test
    void canExecuteToolDirectly() {
        var request = ToolExecutionRequest.builder()
                .name("connectors_chat_listChannels")
                .arguments("{}")
                .build();

        String result = mcpClient.executeTool(request);

        assertThat(result)
                .contains("family")
                .contains("household");
    }

    @Test
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
                "List the names of all available chat channels. "
                + "Return just the channel names as a comma-separated list.");

        assertThat(answer.toLowerCase())
                .contains("family")
                .contains("household");
    }
}
