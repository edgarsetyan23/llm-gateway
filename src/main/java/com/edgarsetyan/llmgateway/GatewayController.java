package com.edgarsetyan.llmgateway;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class GatewayController {
    private final GatewayService gateway;
    public GatewayController(GatewayService gateway) { this.gateway = gateway; }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<Map<String, Object>> chat(
            @RequestHeader(value = "X-API-Key", defaultValue = "") String key,
            @RequestBody Map<String, Object> request) {
        var result = gateway.chat(key, request);
        return ResponseEntity.ok().header("X-Gateway-Cache", result.cached() ? "HIT" : "MISS")
                .header("X-Gateway-Provider", result.provider()).body(result.body());
    }

    @GetMapping("/v1/usage")
    public Map<String, Object> usage(@RequestHeader(value = "X-API-Key", defaultValue = "") String key) {
        return gateway.usage(key);
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<?> error(GatewayException e) {
        var response = ResponseEntity.status(e.status);
        if (e.status == 429) response.header("Retry-After", "60");
        return response.body(Map.of("error", Map.of("message", e.getMessage(), "status", e.status)));
    }
}
