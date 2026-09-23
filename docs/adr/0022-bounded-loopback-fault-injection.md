# ADR 0022: Bounded loopback fault injection

## Status

Accepted.

## Context

The controlled loopback backend can return ordered HTTP status and body responses, but retry behavior also depends on transport failures and response latency. The next network slice needs those cases without becoming endpoint scripting, a proxy, or general traffic interception.

## Decision

Scenario schema v5 adds an optional `fault` to each planned response. The fault is one of:

- `DELAY_RESPONSE`, with an explicit delay from 1 through 5,000 milliseconds.
- `DROP_CONNECTION`, which closes the controlled exchange before headers or a body are sent.

Faults apply only after the server has completed its bounded request read and selected the ordered response-plan entry. They do not change endpoint matching, request-contract evaluation, ADB reverse setup, loopback binding, or TLS configuration. A connection close has no delay and no delivered response body. The configured status remains recorded as the selected plan entry, while `responseDelivered=false` distinguishes it from an HTTP status observed by the client.

The exchange evidence records the configured fault and whether the response was delivered. Network evaluation requires every planned fault and response-delivery state to match alongside the existing sequence, request-contract, body-bound, and integrity checks. A disconnected response is a matching planned exchange when the scenario declares it; an unexpected connection failure remains an execution failure or mismatch according to the existing coordinator boundary.

Schemas v1 through v4 reject faults. V5 retains the single controlled `POST /orders` endpoint and its exact JSON request contract. The checked-in v5 sample drops the first connection and lets the application retry to its successful second response.

## Limits and proof boundary

This is a bounded fault surface for DroidProof's owned loopback server. It cannot delay or disconnect arbitrary application traffic, inject DNS, packet loss, bandwidth limits, TLS failures, proxy errors, server-side scripts, dynamic templates, or faults based on request data. It does not install a CA, redirect traffic, or observe endpoints that the application has not explicitly directed to the reversed loopback port.

The evidence proves the configured fault that this controlled server applied and the bounded exchange it observed. It does not prove client-visible timing, packet-level behavior, retry intent, or the absence of unrelated network activity.
