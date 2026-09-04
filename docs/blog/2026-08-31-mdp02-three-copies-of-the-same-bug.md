---
title: "Three Copies of the Same Bug"
date: 2026-08-31
author: mdp
entry_type: note
subtype: diary
projects: [casehubio/work]
tags: [yaml, yaml-core, variable-resolution, platform, migration]
---

# Three Copies of the Same Bug

yaml-core has lived in casehub/platform for months — `VariableResolver`, `ForEachExpander`, `Truthiness`, extracted from desiredstate's YAML processing patterns. But desiredstate never actually migrated to it. The extracted module sat there with zero real consumers.

casehub-work had three YAML loaders — `WorkItemTemplateYamlLoader`, `SlaDefaultsYamlLoader`, `ProgressDefinitionYamlLoader` — each with its own copy of an `interpolate()` method. Same regex. Same `${env.*}` / `${sys.*}` resolution. Same `:-default` fallback syntax. Same silent passthrough when a variable was unresolved. Three files, zero shared code, and a behaviour that's wrong in all of them: if you typo `${env.DATABSE_URL}`, the literal string `${env.DATABSE_URL}` silently becomes your database URL. No error. No warning. Startup completes. You find out when the first query fails.

The migration to yaml-core took two changes to the platform module and nine file edits in casehub-work. yaml-core's `VariableResolver` didn't support `:-default` syntax — it throws `UnresolvedVariableException` when a variable is missing, which is the right default, but the loaders needed the fallback path. We added default-value parsing to `lookupVariable()` and two factory methods — `VariableSource.env()` and `VariableSource.systemProperty()` — so consumers don't rewire `System::getenv` every time.

The third loader was the surprise. I knew about the template and SLA loaders. `ProgressDefinitionYamlLoader` in `progress-runtime` had the same pattern — discovered during a search for remaining `interpolate` references. Same regex, same method body, same silent-passthrough bug. The kind of duplication that copy-paste creates and grep reveals.

The net result is fewer lines of code (86 added, 99 removed) and a behaviour change that's strictly better: unresolved variables fail at startup with the variable name and available prefixes in the error message. If you want an optional variable, write `${env.OPTIONAL_THING:-fallback}`. If you don't write `:-`, you get told immediately that something is wrong.

desiredstate is next. It has its own `VariableResolver` with MicroProfile Config fallback and deferred prefixes for `match.*` / `fault.*` — the origin of what became yaml-core. The student returns to the teacher.
