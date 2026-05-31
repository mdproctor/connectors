package io.casehub.connectors.email.inbound;

import java.util.List;

import io.casehub.connectors.Attachment;

record ExtractionResult(String content, List<Attachment> attachments) {}
