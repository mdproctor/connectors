package io.casehub.connectors.email.inbound;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.StoreClosedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.FlagTerm;

import org.eclipse.angus.mail.imap.IMAPFolder;

import io.casehub.connectors.InboundConnector;
import io.casehub.connectors.InboundMessage;
import io.casehub.connectors.InboundMessageSink;

/**
 * Pull-based inbound connector for IMAP mailboxes using IMAP IDLE (RFC 2177).
 *
 * <p>One virtual thread per account keeps a persistent IMAP connection and waits
 * for server push notifications. When the server notifies, all UNSEEN messages are
 * delivered to the sink and marked SEEN.
 *
 * <p>{@code connectorId} is always {@value #ID}. Per-account identity is in
 * {@code InboundMessage.metadata["account-id"]}.
 *
 * <h2>Delivery guarantee</h2>
 * At-least-once. If shutdown interrupts between {@code sink.receive()} and the
 * SEEN flag write, the message redelivers on next startup. Observers must be idempotent.
 *
 * <h2>Reconnection</h2>
 * Exponential backoff capped at {@code reconnectDelaySeconds}. Escalates to SEVERE
 * after 5+ consecutive failures. {@code FolderClosedException}/{@code StoreClosedException}
 * reconnect immediately at INFO — covers normal server-side IDLE timeouts.
 */
@ApplicationScoped
public class EmailInboundConnector implements InboundConnector {

    static final String ID = "email-inbound";

    private static final Logger LOG = Logger.getLogger(EmailInboundConnector.class.getName());

    private final EmailInboundAccountProvider provider;
    private final List<Store> openStores = new CopyOnWriteArrayList<>();
    private volatile boolean stopping = false;
    private ExecutorService executor;

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
        if (executor != null) return; // double-start guard
        executor = Executors.newVirtualThreadPerTaskExecutor();
        for (final EmailInboundAccount account : provider.accounts()) {
            executor.submit(() -> idleLoop(account, sink));
        }
    }

    @Override
    public void stop() {
        stopping = true;
        new ArrayList<>(openStores).forEach(store -> {
            try { store.close(); } catch (final Exception ignored) {}
        });
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void idleLoop(final EmailInboundAccount account, final InboundMessageSink sink) {
        int backoffSeconds = 1;
        int consecutiveFailures = 0;

        while (!stopping) {
            Store store = null;
            IMAPFolder folder = null;
            try {
                store = connect(account);
                openStores.add(store);
                if (stopping) {
                    // Race guard: stop() may have snapshotted openStores before we added.
                    // Do not remove here — finally handles openStores.remove(store) and closeQuietly.
                    return;
                }
                folder = (IMAPFolder) store.getFolder(account.folder());
                folder.open(Folder.READ_WRITE);
                backoffSeconds = 1;
                consecutiveFailures = 0;
                LOG.info("email-inbound: IDLE connected for account " + account.id());

                // Catch messages already in the mailbox before IDLE started
                processUnseen(folder, account, sink);

                while (!stopping) {
                    folder.idle(true); // blocks until one server notification, then returns
                    processUnseen(folder, account, sink);
                }

            } catch (final FolderClosedException | StoreClosedException e) {
                // Normal server-side IDLE timeout or server-closed connection.
                // consecutive quick disconnects will eventually fail to reconnect and hit the backoff path
                if (!stopping) {
                    LOG.info("email-inbound: IDLE session ended for account "
                            + account.id() + ", reconnecting");
                }
            } catch (final Exception e) {
                if (!stopping) {
                    consecutiveFailures++;
                    final Level level = consecutiveFailures >= 5 ? Level.SEVERE : Level.WARNING;
                    LOG.log(level, "email-inbound: connection failed for account "
                            + account.id() + " (attempt " + consecutiveFailures + "): "
                            + e.getMessage());
                    sleepQuietly(backoffSeconds * 1000L);
                    backoffSeconds = Math.min(backoffSeconds * 2, account.reconnectDelaySeconds());
                }
            } finally {
                openStores.remove(store);
                closeQuietly(folder, store);
            }
        }
    }

    private Store connect(final EmailInboundAccount account) throws Exception {
        final Properties props = buildProperties(account);
        final Session session = Session.getInstance(props);
        final Store store = session.getStore();
        store.connect(account.host(), account.username(), account.password());
        return store;
    }

    private void processUnseen(final IMAPFolder folder, final EmailInboundAccount account,
                                final InboundMessageSink sink) {
        try {
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
            LOG.log(Level.WARNING, "email-inbound: processUnseen failed for account "
                    + account.id(), e);
        }
    }

    private static Properties buildProperties(final EmailInboundAccount account) {
        final Properties props = new Properties();
        if (account.tls()) {
            props.put("mail.store.protocol", "imaps");
            props.put("mail.imaps.host", account.host());
            props.put("mail.imaps.port", String.valueOf(account.port()));
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.timeout", "300000");
            props.put("mail.imaps.connectiontimeout", "30000");
        } else {
            props.put("mail.store.protocol", "imap");
            props.put("mail.imap.host", account.host());
            props.put("mail.imap.port", String.valueOf(account.port()));
            props.put("mail.imap.timeout", "300000");
            props.put("mail.imap.connectiontimeout", "30000");
        }
        return props;
    }

    private static InboundMessage toInboundMessage(final EmailInboundAccount account,
                                                    final Message msg) {
        try {
            final ExtractionResult extracted = ContentExtractor.extract(msg);
            return new InboundMessage(
                    ID,
                    extractSenderId(msg),
                    extractChannelRef(msg, account),
                    extracted.content(),
                    extracted.attachments(),
                    resolveReceivedAt(msg),
                    buildMetadata(account, msg, extracted.attachments().size()));
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
                                              final Message msg,
                                              final int attachmentCount) {
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
        meta.put("attachment-count", String.valueOf(attachmentCount));
        return Collections.unmodifiableMap(meta);
    }

    private static void sleepQuietly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(final Folder folder, final Store store) {
        if (folder != null) {
            try { folder.close(false); } catch (final Exception ignored) {}
        }
        if (store != null) {
            try { store.close(); } catch (final Exception ignored) {}
        }
    }
}
