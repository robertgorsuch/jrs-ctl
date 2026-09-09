# ADR-0001: No GraalVM native-image in v1

Status: accepted · Date: 2026-09-08 · Spec: §1.3, §14 Phase 7

## Context
Draft 1.0 listed a native-image build as an experimental Phase 7 deliverable alongside jlink.

## Decision
Ship a jlink runtime image only. Do not build native-image in v1.

## Consequences
jlink already removes the JDK prerequisite. native-image with sqlite-jdbc, Jackson, Javalin/Jetty and reflection-heavy libraries requires extensive reachability metadata for no operator benefit. Revisit when startup time becomes an operator complaint.
