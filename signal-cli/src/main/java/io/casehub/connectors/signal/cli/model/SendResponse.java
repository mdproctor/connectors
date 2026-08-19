package io.casehub.connectors.signal.cli.model;

public record SendResponse(boolean ok, String timestamp) {

    public static SendResponse success(final String timestamp) {
        return new SendResponse(true, timestamp);
    }

    public static SendResponse failure() {
        return new SendResponse(false, null);
    }
}
