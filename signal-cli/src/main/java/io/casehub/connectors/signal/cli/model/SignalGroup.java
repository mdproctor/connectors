package io.casehub.connectors.signal.cli.model;

import java.util.List;

public record SignalGroup(String id, String name, String description, List<String> members) {}
