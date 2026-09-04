---
layout: post
title: "P1D Was Never Invalid"
date: 2026-05-21
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-engine]
tags: [java, yaml, validation]
---

The issue description was confident: "`Duration.parse()` throws `DateTimeParseException` for any non-PT-prefixed format (e.g. `P1D`)." We were adding validation to `CaseDefinitionYamlMapper.convertHumanTask` — the method that converts YAML `humanTask` binding schemas to API model objects — and the issue gave `"P1D"` as the test case for the invalid-format path.

`"P1D"` parses fine. Java's `Duration.parse()` accepts the full ISO-8601 extended duration format, day components included. `"P1D"` returns 86400 seconds without complaint.

Writing the test first caught it immediately: the assertion failed with "Expecting code to raise a throwable" — no exception, just a parsed value. The fix was to use a genuinely invalid format; `"1h"` fails immediately, and it's the natural mistake an author makes when writing a YAML workflow definition.

The confusion is easy to trace. `Duration.toString()` always produces `PT`-prefixed output — `PT24H`, `PT1H30M`. It looks like only `PT`-prefixed strings are valid inputs. The Javadoc says otherwise, but that detail is easy to miss.

The actual change adds three checks to `convertHumanTask`: detect when both `title` and `templateRef` are set (which the JSON Schema's `oneOf` rejects, but Jackson silently deserialises both), catch and re-wrap bad `expiresIn` formats with the bad value in the message, and reject zero or negative durations. All three throw `IllegalArgumentException`. The caller — `convertBinding` — already catches `IllegalArgumentException` and prepends the binding name, so none of this needed a new catch block.

```java
try {
    duration = Duration.parse(schema.getExpiresIn());
} catch (DateTimeParseException e) {
    throw new IllegalArgumentException(
        "invalid expiresIn '" + schema.getExpiresIn()
            + "' - must be ISO-8601 duration (e.g. PT24H, PT1H30M)", e);
}
if (duration.isNegative() || duration.isZero()) {
    throw new IllegalArgumentException(
        "expiresIn must be positive, got '" + schema.getExpiresIn() + "'");
}
```

One detail from code review: the original error messages used em-dashes (`—`). That's technically fine but can encode unexpectedly in log output or JSON responses depending on locale. Switched to ` - `. Worth knowing before reaching for an em-dash in an exception message.
