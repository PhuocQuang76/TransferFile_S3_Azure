# Assignment Extension: Publish Transfer Events to Azure Event Hub

**Builds on:** the existing S3 → Azure Blob Connector
**Technology:** Java + Spring Boot + Azure Event Hubs
**Type:** Feature extension
**Estimated effort:** ~0.5–1 day

---

## 1. Objective

Extend your existing connector so that **every time a file is transferred from S3 to Azure Blob, the connector publishes an event describing that transfer to an Azure Event Hub.**

In this task the connector acts as an **event producer**. You are *only* producing events — you are **not** building any consumer, dashboard, or notification system (see *Out of Scope* below).

---

## 2. Background / Scenario

Other teams in the company want to know, in real time, which files have been moved to Azure — for auditing, analytics, and monitoring. Instead of asking those teams to inspect your connector directly, your connector will **broadcast a "file transferred" event** onto a shared stream (Azure Event Hub). Any team can subscribe later, without your connector needing to know they exist.

This is the classic "emit an event and move on" pattern — it decouples your connector from everyone downstream.

---

## 3. High-Level Flow (what changes)

```
+-----------+     +--------------------+     +-------------------+
|  AWS S3   | --> |  Spring Boot       | --> |   Azure Blob      |
|  Bucket   |     |  Connector         |     |   Container       |
+-----------+     +---------+----------+     +-------------------+
                            |
                            | after each transfer, publish an event
                            v
                   +--------------------+
                   |  Azure Event Hub   |
                   +--------------------+
```

Your existing transfer logic stays the same. You are adding **one new step**: after each file is transferred, build an event and send it to Event Hub.

---

## 4. Functional Requirements

The connector **must**:

1. **After each file transfer**, create an event object containing the metadata listed in Section 6.
2. **Serialize the event to JSON.**
3. **Publish the event to a configured Azure Event Hub.**
4. Publish an event for **both outcomes**:
   - a **SUCCESS** event when a file is transferred successfully, and
   - a **FAILED** event when a file fails to transfer (with the error reason).
5. Read the Event Hub **connection string and hub name from configuration** (not hardcoded).
6. Ensure that a problem publishing an event **does not crash the whole transfer run** — log it and continue. (Decide and document your behavior: does the file count as transferred even if its event failed to publish?)
7. **Log** each publish attempt (which file, success/failure).

---

## 5. Non-Functional Requirements

1. **No hardcoded secrets** — the Event Hub connection string must come from `application.yml` / environment variables.
2. **Publishing should not significantly slow down the transfer** — sending an event is a small, quick operation; don't block the whole run waiting on it unnecessarily.
3. **Graceful degradation** — if Event Hub is unreachable, the transfer of files should still complete; event failures are logged, not fatal.
4. **Clean structure** — put the event-publishing logic in its own class/service (e.g. `TransferEventPublisher`), not mixed into the transfer loop.
5. **Meaningful logging** via SLF4J.

---

## 6. Event Schema

Each event must be valid JSON containing at least these fields:

| Field | Type | Description |
|-------|------|-------------|
| `transferId` | string | A unique id for this transfer (e.g. UUID) |
| `fileName` | string | The object key / file name |
| `sourceBucket` | string | The S3 bucket the file came from |
| `destinationContainer` | string | The Azure Blob container it went to |
| `fileSize` | number | Size in bytes |
| `status` | string | `SUCCESS` or `FAILED` |
| `errorMessage` | string / null | Reason, if the transfer failed (else null/omitted) |
| `durationMs` | number | How long the transfer took, in milliseconds |
| `timestamp` | string | ISO-8601 timestamp of the event |

**Example (success):**

```json
{
  "transferId": "9f2c1e7a-4b3d-4c8e-9a10-2f6b8c1d0e5a",
  "fileName": "invoices/2025/inv-1042.pdf",
  "sourceBucket": "company-incoming",
  "destinationContainer": "migrated-files",
  "fileSize": 84213,
  "status": "SUCCESS",
  "errorMessage": null,
  "durationMs": 412,
  "timestamp": "2026-09-18T10:24:53Z"
}
```

---

## 7. Technical Stack & Configuration

| Item | Requirement |
|------|-------------|
| Event Hub SDK | `com.azure:azure-messaging-eventhubs` (or Spring Cloud Azure Event Hubs) |
| JSON | Jackson (already available in Spring Boot) |
| Config | Externalized in `application.yml` |

**Suggested configuration:**

```yaml
azure:
  eventhub:
    connection-string: ${AZURE_EVENTHUB_CONNECTION_STRING}
    event-hub-name: ${AZURE_EVENTHUB_NAME}
```

> Create an Event Hubs **namespace** and an **event hub** in the Azure portal, and use a Shared Access Policy with **Send** permission for the connection string.

---

## 8. Suggested Structure (additions only)

```
service/
├── TransferService.java          // existing — call the publisher after each transfer
├── TransferEventPublisher.java   // NEW — builds JSON + sends to Event Hub
model/
└── TransferEvent.java            // NEW — the event object (fields from Section 6)
config/
└── EventHubConfig.java           // NEW — creates the Event Hub producer client bean
```

---

## 9. How to Verify It Works

You do **not** build a consumer as part of this task, but you must **prove events are arriving**. Any one of these is acceptable:

1. **Azure Portal → your Event Hub → Metrics** — show the "Incoming Messages" count rising after a transfer run. *(simplest)*
2. Use the portal's **"Data Explorer / Process data"** feature to peek at incoming events.
3. Run a **throwaway consumer snippet** just to print received events to the console. *(This is for verification only — a proper consumer service is out of scope.)*

---

## 10. Deliverables

1. Updated source code with the event publisher.
2. `application.yml` with **placeholder** Event Hub config (no real connection string).
3. Updated **README** including:
   - the event JSON schema,
   - how to configure the Event Hub connection,
   - how you verified events are arriving (with a screenshot of Event Hub metrics or consumer output).
4. Your documented decision for requirement 4.6 (what happens if a file transfers but its event fails to publish).

---

## 11. Acceptance Criteria (Definition of Done)

- [ ] After a transfer run, **one event per file** appears in Event Hub.
- [ ] Each event is **valid JSON** with all required fields from Section 6.
- [ ] Both **SUCCESS** and **FAILED** cases produce an event.
- [ ] The Event Hub connection string is **externalized** and not committed to Git.
- [ ] A failure to publish an event **does not stop** the rest of the transfer run.
- [ ] Logs clearly show each publish attempt and its result.
- [ ] Proof of arriving events is included (screenshot / consumer output).

---

## 12. Out of Scope (Do NOT build these)

To keep this task focused, **do not** build:

- ❌ Consumer services / consumer groups
- ❌ Event Hubs Capture to Blob/Data Lake
- ❌ Notification (email/Slack) services
- ❌ Dashboards or analytics on the events
- ❌ Stream Analytics jobs

A small throwaway consumer *purely to verify* events (Section 9) is fine — but it is not a deliverable.

---

## 13. Prerequisites

- A working S3 → Azure Blob connector (the base assignment).
- An **Azure Event Hubs namespace** + an **event hub** (the Basic tier is inexpensive and sufficient).
- A Shared Access Policy with **Send** permission.

> **Note:** Azurite (used to emulate Blob locally) does **not** emulate Event Hub. Use a real Event Hubs namespace for this task and verify via the portal.

---

## 14. Optional Stretch (only if finished early)

- Add a **partition key** (e.g. the file name) when sending, and explain in the README what partitioning gives you.
- Send events in a **batch** (`EventDataBatch`) instead of one-by-one, and note the trade-off.

---

**Focus:** get a single SUCCESS event landing in Event Hub first, confirm it in the portal, then handle the FAILED case and multiple files. Keep the publisher isolated and the connection string out of your code.
