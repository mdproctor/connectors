---
layout: post
title: "SLAs, signals, and a connector library"
date: 2026-04-27
type: phase-update
entry_type: note
subtype: diary
projects: [quarkus-work, casehub-connectors]
tags: [business-hours, notifications, spi, cdi, connectors]
excerpt: "Business-hours SLA and outbound notifications ship, and a notification routing detail that starts as a one-line decision grows into casehub-connectors — a separate repository born from the question of where channel adapters should live."
---

Three epics this session: business-hours SLA, outbound notifications, and something
that started as a detail and became its own repository.

## 48 hours means 48 business hours

The design question for business-hours deadlines is where the SPI lives and how
implementations get selected.

We put `BusinessCalendar` in `quarkus-work-api` — pure Java, zero deps, reusable by
CaseHub. The matching `HolidayCalendar` SPI follows the same rule. Three built-in
holiday sources: a static config list, an iCal feed fetched at startup, and whatever
an application provides. The selection mechanism is the interesting part.

The natural reach for "overridable default" in CDI is `@Alternative @Priority(1)` on
the overriding bean — which means the application has to know about Quarkus ArC's
priority system. We used `@Produces @DefaultBean` instead, on a producer method in
the library:

```java
@Produces
@DefaultBean
@ApplicationScoped
public HolidayCalendar produce() {
    return config.businessHours().holidayIcalUrl()
            .filter(url -> !url.isBlank())
            .map(ICalHolidayCalendar::new)
            .orElseGet(() -> new ConfigHolidayCalendar(config));
}
```

Any `@ApplicationScoped HolidayCalendar` the application provides wins automatically.
No annotation needed on the application side. It's a Quarkus ArC feature —
`io.quarkus.arc.DefaultBean`, not in the CDI spec — which is why we didn't reach for
it immediately. We tried `jakarta.enterprise.inject.DefaultBean` first and the
compiler promptly told us that class doesn't exist.

## Notifications without a framework

The notifications architecture is a CDI observer that fires after a WorkItem transition
commits, loads matching rules from the database, dispatches each channel asynchronously
on a virtual-thread executor. The observer uses `AFTER_SUCCESS` so a rolled-back
transition produces no notification. The channels implement a `NotificationChannel` SPI;
the dispatcher discovers them all via CDI injection.

The question that opened up: where does the actual HTTP code live? Slack and Teams are
both just `POST {json}` to a webhook URL. We had the implementations inline in the
notifications module. Then I asked whether there was already something in the Quarkus
ecosystem for this.

Claude searched and found: `camel-quarkus-slack` exists, but Camel is a freight train
for an outbound webhook. `quarkus-slack` in Quarkiverse is a Bolt framework — for
building Slack bots, not sending messages. No Teams support anywhere. No unified
outbound notification connector library.

## casehub-connectors

I decided the HTTP logic shouldn't live inside quarkus-work at all. A library that
sends messages to Slack, Teams, Twilio SMS, and WhatsApp is genuinely reusable — CaseHub
engine will want it, Claudony will want it, and any Quarkus application could use it
independently.

We created `casehubio/connectors` as its own repository. The `Connector` SPI
is four lines:

```java
public interface Connector {
    String id();
    void send(ConnectorMessage message);
}
```

Four HTTP-based connectors — Slack, Teams, Twilio SMS, WhatsApp — all using
`java.net.http.HttpClient`, zero additional dependencies. Email goes in a separate
module because `quarkus-mailer` is a Quarkus extension that stalls at augmentation
time if no SMTP is configured.

`quarkus-work-notifications` now depends on `casehub-connectors` and uses
`SlackConnector` and `TeamsConnector` directly. The notification channels are thin
adapters: translate `WorkItemLifecycleEvent` into `ConnectorMessage`, delegate delivery.
The HTTP logic belongs in the connector library.
