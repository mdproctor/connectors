package io.casehub.connectors.email.inbound;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.FlagTerm;

import io.casehub.connectors.InboundConnector;
import io.casehub.connectors.InboundMessage;
import io.casehub.connectors.InboundMessageSink;

/**
 * Pull-based inbound connector for IMAP mailboxes.
 *
 * <p>Polls every configured account on a per-account {@link ScheduledExecutorService}
 * (single-threaded, daemon). Each poll cycle opens a fresh {@link Session} and
 * {@link Store} — no reconnect logic, no persistent connection.
 *
 * <p>{@code connectorId} is always {@value #ID}. Per-account identity is in
 * {@code InboundMessage.metadata["account-id"]}.
 *
 * <h2>Delivery guarantee</h2>
 * At-least-once. If shutdown interrupts between {@code sink.receive()} and the
 * SEEN flag write, the message will be redelivered on next startup. Observers must
 * be idempotent.
 */
@ApplicationScoped
public class EmailInboundConnector implements InboundConnector {

    static final String ID = "email-inbound";

    private static final Logger LOG = Logger.getLogger(EmailInboundConnector.class.getName());

    private final EmailInboundAccountProvider provider;
    // Initialised at construction — always safe to iterate in stop() even if start() never ran
    private final List<ScheduledExecutorService> executors = new ArrayList<>();

    @Inject
    public EmailInboundConnector(final EmailInboundAccountProvider provider) {
        this.provider = provider;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void start(final InboundMessageSink sink) {
        if (!executors.isEmpty()) return; // guard against double-start
        for (final EmailInboundAccount account : provider.accounts()) {
            final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread t = new Thread(r, "email-inbound-" + account.id() + "-poller");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleWithFixedDelay(
                    () -> pollAccount(account, sink),
                    0L,
                    account.pollIntervalSeconds(),
                    TimeUnit.SECONDS);
            executors.add(executor);
        }
    }

    @Override
    public void stop() {
        executors.forEach(ScheduledExecutorService::shutdownNow);
    }

    // Package-private — tests call this directly to avoid executor scheduling complexity.
    void pollAccount(final EmailInboundAccount account, final InboundMessageSink sink) {
        final Properties props = buildProperties(account);
        final Session session = Session.getInstance(props);
        Store store = null;
        Folder folder = null;
        try {
            store = session.getStore();
            store.connect(account.host(), account.username(), account.password());
            folder = store.getFolder(account.folder());
            folder.open(Folder.READ_WRITE);

            final Message[] unseen = folder.search(
                    new FlagTerm(new Flags(Flags.Flag.SEEN), false));

            for (final Message msg : unseen) {
                final InboundMessage inbound = toInboundMessage(account, msg);
                try {
                    sink.receive(inbound);
                } catch (final Exception e) {
                    LOG.log(Level.SEVERE, "email-inbound: sink threw for account "
                            + account.id(), e);
                } finally {
                    try {
                        msg.setFlag(Flags.Flag.SEEN, true);
                    } catch (final Exception e) {
                        LOG.log(Level.WARNING, "email-inbound: failed to mark SEEN for account "
                                + account.id(), e);
                    }
                }
            }
        } catch (final Exception e) {
            LOG.log(Level.WARNING, "email-inbound: poll failed for account "
                    + account.id() + ": " + e.getMessage(), e);
        } finally {
            closeQuietly(folder, store);
        }
    }

    private static Properties buildProperties(final EmailInboundAccount account) {
        final Properties props = new Properties();
        if (account.tls()) {
            props.put("mail.store.protocol", "imaps");
            props.put("mail.imaps.host", account.host());
            props.put("mail.imaps.port", String.valueOf(account.port()));
            props.put("mail.imaps.ssl.enable", "true");
        } else {
            props.put("mail.store.protocol", "imap");
            props.put("mail.imap.host", account.host());
            props.put("mail.imap.port", String.valueOf(account.port()));
        }
        return props;
    }

    private static InboundMessage toInboundMessage(final EmailInboundAccount account,
                                                    final Message msg) {
        try {
            return new InboundMessage(
                    ID,
                    extractSenderId(msg),
                    extractChannelRef(msg, account),
                    ContentExtractor.extractContent(msg),
                    resolveReceivedAt(msg),
                    buildMetadata(account, msg));
        } catch (final Exception e) {
            LOG.log(Level.WARNING, "email-inbound: message parse failed", e);
            return new InboundMessage(ID, "", account.username(), "",
                    Instant.now(), Map.of("account-id", account.id()));
        }
    }

    private static String extractSenderId(final Message msg) {
        try {
            final Address[] from = msg.getFrom();
            if (from != null && from.length > 0 && from[0] instanceof final InternetAddress ia) {
                final String addr = ia.getAddress();
                if (addr != null) return addr;
            }
        } catch (final Exception ignored) {}
        return "";
    }

    private static String extractChannelRef(final Message msg,
                                            final EmailInboundAccount account) {
        try {
            final Address[] to = msg.getRecipients(Message.RecipientType.TO);
            if (to != null && to.length > 0 && to[0] instanceof final InternetAddress ia) {
                final String addr = ia.getAddress();
                if (addr != null && !addr.isBlank()) return addr;
            }
        } catch (final Exception ignored) {}
        return account.username();
    }

    private static Instant resolveReceivedAt(final Message msg) {
        try {
            final Date received = msg.getReceivedDate();
            if (received != null) return received.toInstant();
            final Date sent = msg.getSentDate();
            if (sent != null) return sent.toInstant();
        } catch (final Exception ignored) {}
        return Instant.now();
    }

    static Map<String, String> buildMetadata(final EmailInboundAccount account,
                                              final Message msg) {
        final Map<String, String> meta = new LinkedHashMap<>();
        meta.put("account-id", account.id());
        try {
            final String[] msgId = msg.getHeader("Message-ID");
            if (msgId != null && msgId.length > 0 && msgId[0] != null) {
                meta.put("message-id", msgId[0].trim());
            }
            final String subject = msg.getSubject();
            if (subject != null) {
                meta.put("subject", subject);
            }
        } catch (final Exception ignored) {}
        return Collections.unmodifiableMap(meta);
    }

    private static void closeQuietly(final Folder folder, final Store store) {
        if (folder != null) {
            // false = do not expunge on close; connector marks SEEN not DELETED
            try { folder.close(false); } catch (final Exception ignored) {}
        }
        if (store != null) {
            try { store.close(); } catch (final Exception ignored) {}
        }
    }
}
