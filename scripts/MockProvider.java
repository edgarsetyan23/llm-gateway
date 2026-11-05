import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Local learning fixture. Run: java scripts/MockProvider.java [--fail-primary] */
class MockProvider {
    public static void main(String[] args) throws Exception {
        boolean failPrimary = java.util.Arrays.asList(args).contains("--fail-primary");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 9091), 0);
        var calls = new AtomicInteger();
        for (String path : new String[]{"/primary", "/fallback"}) {
            server.createContext(path, exchange -> {
                exchange.getRequestBody().readAllBytes();
                int status = failPrimary && path.equals("/primary") ? 503 : 200;
                String response = status == 503 ? "{\"error\":\"simulated outage\"}"
                        : "{\"model\":\"demo-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Hello from the local mock provider!\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                System.out.printf("Provider call %d: %s -> %d%n", calls.incrementAndGet(), path, status);
            });
        }
        server.start();
        System.out.println("Mock provider listening at http://127.0.0.1:9091 (Ctrl+C to stop)");
    }
}
