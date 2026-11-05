package com.edgarsetyan.llmgateway;

class GatewayException extends RuntimeException {
    final int status;
    GatewayException(int status, String message) { super(message); this.status = status; }
}
