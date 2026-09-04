---
layout: post
title: "The Gap in Smart Home Orchestration"
date: 2026-06-05
entry_type: note
subtype: diary
projects: [casehub-parent, casehub-life, casehub-iot]
tags: [iot, home-automation, home-assistant, openhab, platform-design]
---

Started by clearing seven PLATFORM.md doc syncs — cross-dep table entries, deep-dive updates, protocol entries to the garden. Mechanical work. Done by mid-session.

The rest of the day was home automation.

I wanted to add Home Assistant and OpenHAB support to casehub-life's household management domain. The first question was where the device abstraction should live — in life, or as a standalone module. Once you think it through, it belongs in the foundation: the same HA/OpenHAB device layer could serve property management, elder care, and industrial IoT. I brought Claude in to research what already exists before designing anything.

The answer: not much. Matter standardises at the physical device layer, not at the platform API level — it gives you a common protocol for the device hardware, not a unified API over HA and OpenHAB. W3C WoT is a descriptive schema language with no production integration anywhere. No Java library provides typed `Light`, `Thermostat`, `Cover` abstractions over both platforms. The gap is confirmed and real.

The interesting design problem is the structural difference between the two platforms. HA uses one entity per capability group — `climate.living_room` has temperature, setpoint, mode, and fan mode in one entity and one event. OpenHAB uses multiple items per device — a thermostat is separate items for temperature, setpoint, and mode, grouped under an Equipment Group tag. I initially assumed we'd need a state cache to assemble OpenHAB's item-level events into coherent device events. But Claude pointed out the framing was wrong: OpenHAB's per-item events are more precise, not worse — they tell you exactly which field changed. HA requires diffing to know what changed. The right direction was to make HA produce the same precision, not to discard OpenHAB's richer signal.

`StateChangeEvent` ended up carrying both the assembled device state and a `changedCapabilities` set. Drools rules can pattern-match on `"targetTemperature" in changedCapabilities` without caring which platform fired it.

For the type model: common interface first, supplement only for genuinely unmappable fields. `ThermostatDevice` in `iot-api` maps to HA's `climate` entity and OpenHAB's semantic equipment group. Only fields with no cross-vendor equivalent go into vendor subclasses. The vocabulary is aligned with the Matter Device Type Library, which both platforms support — gives us a principled naming scheme and a natural path to a future Matter provider.

The deployment question produced the most surprising finding. I'd assumed the three-tier model — fully local, bridge, hybrid with edge/cloud split — was obvious. Surely someone had built this. Claude researched it and came back with a clear answer: no one has. The home automation market has split into local-only (HA, Hubitat, OpenHAB, Crestron) and cloud-primary (SmartThings, Google Home, Alexa) with nothing in between. No product acts as an orchestration layer above existing hubs, intelligently routes by latency sensitivity, or offers all three modes from one codebase. Node-RED comes closest but it's local-only and has none of the case management, HITL, or worker variety. The position is genuinely unoccupied.

That changed how I thought about the project. casehub-iot isn't just a technical module — it's a market position.

The application layer landed in casehub-life. Home automation IS household management, which is already a `LifeDomain`. A separate casehub-home repo would split one coherent domain and force cross-app case bridging for things like "holiday trip case → home enters holiday mode." Same application, same OpenClaw worker integration (Layer 7 applied to household devices), no new plumbing.

We created the casehub-iot repo, added CI/CD and pre-push hook, scaffolded a workspace, split the design spec into two (one for the iot foundation session, one for the life Layer 9 session), and updated PLATFORM.md and APPLICATIONS.md. I missed the hook and CI/CD initially — discovered through cleaning it up, which is now formalised as a peer repo creation protocol.
