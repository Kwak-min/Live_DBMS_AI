import fs from "node:fs";
import path from "node:path";

const target = path.resolve(process.argv[2] ?? "docs/contract-examples.json");
const errors = [];
const check = (condition, message) => {
  if (!condition) errors.push(message);
};
const object = value =>
  value !== null && typeof value === "object" && !Array.isArray(value);
const own = (value, key) =>
  Object.prototype.hasOwnProperty.call(value, key);
const timePattern = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const reasons = new Set([
  "UNSUPPORTED",
  "WARMUP",
  "COUNTER_RESET",
  "COLLECTION_FAILED",
  "QUERY_FAILED",
]);
const metricFields = [
  "cpuUsage",
  "memoryUsage",
  "activeConnections",
  "maxConnections",
  "qps",
  "slowQueries",
  "slowQueriesDelta",
  "slowQueriesPerSecond",
  "metricWindowSeconds",
  "threadsRunning",
  "storageBytes",
  "responseTimeMs",
];
const metricEventFields = [
  "metricId",
  "databaseConfigId",
  "configVersion",
  "databaseName",
  "timestamp",
  "collectionAttemptTime",
  "lastSuccessAt",
  ...metricFields,
  "collectionStatus",
  "errorCode",
  "errorMessage",
  "unavailableMetrics",
];
const requiredMetricMappings = new Set([
  "metricSuccess->metricCollectedEvent",
  "metricMeasuredZero->metricMeasuredZeroEvent",
  "metricPartialFailure->metricPartialFailureEvent",
]);
const requiredScenarioIds = new Set([
  "duplicate-event-id",
  "late-out-of-order-event",
  "old-config-event",
  "accepted-partial-failure-resets-timers",
  "stale-boundary-before",
  "accepted-observation-fresh",
  "stale-boundary-at",
  "stale-preserves-fatal-risk",
  "policy-change-metric-only",
  "cooldown-3600-keeps-eligibility",
]);
const statusFields = [
  "databaseConfigId",
  "configVersion",
  "deleted",
  "enabled",
  "connectionStatus",
  "dataFreshness",
  "riskLevel",
  "lastAttemptAt",
  "lastSuccessAt",
  "latestMetricId",
  "openIncidentIds",
  "stateVersion",
  "updatedAt",
];

function safeInt(value, label, positive = true) {
  check(
    Number.isSafeInteger(value) && (!positive || value > 0),
    label + " must be a safe " + (positive ? "positive " : "") + "integer",
  );
  return value;
}

function number(value, label, nullable = true) {
  if (value === null && nullable) return;
  check(
    typeof value === "number" && Number.isFinite(value) && value >= 0,
    label + " must be a finite non-negative number or null",
  );
}

function time(value, label, nullable = false) {
  if (value === null && nullable) return null;
  check(
    typeof value === "string" && timePattern.test(value),
    label + " must be UTC Time",
  );
  if (typeof value === "string" && timePattern.test(value)) {
    const parsed = new Date(value);
    check(!Number.isNaN(parsed.getTime()), label + " must be a valid UTC Time");
    if (!Number.isNaN(parsed.getTime())) {
      check(parsed.toISOString() === value, label + " must be a valid UTC Time");
    }
  }
  return value;
}

function id(value, label) {
  check(
    typeof value === "string" && uuidPattern.test(value),
    label + " must be a lowercase UUID",
  );
}

function required(value, fields, label) {
  check(object(value), label + " must be an object");
  if (!object(value)) return false;
  for (const field of fields) {
    check(own(value, field), label + "." + field + " is required");
  }
  return true;
}

function validateMetric(metric, label) {
  const fields = [
    "id", "databaseConfigId", "configVersion", "timestamp",
    "collectionAttemptTime", "lastSuccessAt", ...metricFields,
    "collectionStatus", "errorCode", "errorMessage", "unavailableMetrics",
  ];
  if (!required(metric, fields, label)) return;
  safeInt(metric.id, label + ".id");
  safeInt(metric.databaseConfigId, label + ".databaseConfigId");
  safeInt(metric.configVersion, label + ".configVersion");
  time(metric.timestamp, label + ".timestamp");
  time(metric.collectionAttemptTime, label + ".collectionAttemptTime");
  time(metric.lastSuccessAt, label + ".lastSuccessAt", true);
  if (metric.lastSuccessAt !== null && metric.timestamp) {
    check(metric.lastSuccessAt <= metric.timestamp,
      label + ".lastSuccessAt cannot be later than timestamp");
  }
  for (const field of metricFields) {
    number(metric[field], label + "." + field);
  }
  if (metric.metricWindowSeconds !== null) {
    check(metric.metricWindowSeconds > 0,
      label + ".metricWindowSeconds must be > 0 when present");
  }
  check(
    ["SUCCESS", "WARMUP", "CONNECTION_FAILED", "PARTIAL_FAILURE"].includes(
      metric.collectionStatus,
    ),
    label + ".collectionStatus is invalid",
  );
  check(
    metric.errorCode === null ||
      ["AUTH_FAILED", "CONNECT_TIMEOUT", "CONNECTION_REFUSED",
       "QUERY_FAILED", "INTERNAL_ERROR", "UNKNOWN"].includes(metric.errorCode),
    label + ".errorCode is invalid",
  );
  check(
    metric.errorMessage === null || typeof metric.errorMessage === "string",
    label + ".errorMessage must be text or null",
  );
  check(
    object(metric.unavailableMetrics),
    label + ".unavailableMetrics must be an object",
  );
  if (object(metric.unavailableMetrics)) {
    for (const [field, reason] of Object.entries(metric.unavailableMetrics)) {
      check(
        metricFields.includes(field),
        label + ".unavailableMetrics." + field + " is not a metric field",
      );
      check(
        reasons.has(reason),
        label + ".unavailableMetrics." + field + " has an invalid reason",
      );
      check(
        metric[field] === null,
        label + "." + field + " must be null when unavailable",
      );
    }
    for (const field of metricFields) {
      if (metric[field] === null) {
        check(
          own(metric.unavailableMetrics, field),
          label + "." + field + " is null without unavailableMetrics reason",
        );
      }
    }
  }
  if (metric.collectionStatus === "SUCCESS") {
    check(
      metric.errorCode === null && metric.errorMessage === null,
      label + " SUCCESS must have null error fields",
    );
  }
  if (metric.collectionStatus === "PARTIAL_FAILURE") {
    check(
      metric.errorCode !== null &&
        Object.keys(metric.unavailableMetrics ?? {}).length > 0,
      label + " PARTIAL_FAILURE needs an error and unavailable fields",
    );
  }
  if (metric.collectionStatus === "CONNECTION_FAILED") {
    check(metric.errorCode !== null,
      label + " CONNECTION_FAILED needs an error code");
  }
}

function validateCommonEvent(event, label, types) {
  if (!required(event, ["schemaVersion", "eventId", "eventType", "publishedAt"], label)) return false;
  safeInt(event.schemaVersion, `${label}.schemaVersion`); check(event.schemaVersion === 1, `${label}.schemaVersion must be 1`);
  id(event.eventId, `${label}.eventId`); time(event.publishedAt, `${label}.publishedAt`);
  check(types.includes(event.eventType), `${label}.eventType is invalid`);
  return true;
}

function validateMetricEvent(event, label) {
  if (!validateCommonEvent(event, label, ["MetricCollectedEvent"])) return;
  if (!required(event, metricEventFields, label)) return;
  safeInt(event.metricId, `${label}.metricId`); check(typeof event.databaseName === "string" && event.databaseName.length > 0, `${label}.databaseName must be text`);
  validateMetric({ ...event, id: event.metricId }, label);
  check(event.timestamp <= event.publishedAt, `${label}.timestamp cannot be later than publishedAt`);
}

function validateStatus(status, label) {
  if (!required(status, statusFields, label)) return;
  safeInt(status.databaseConfigId, `${label}.databaseConfigId`); safeInt(status.configVersion, `${label}.configVersion`); safeInt(status.stateVersion, `${label}.stateVersion`);
  check(typeof status.deleted === "boolean", `${label}.deleted must be boolean`); check(typeof status.enabled === "boolean", `${label}.enabled must be boolean`);
  check(["UP", "DOWN", "UNKNOWN"].includes(status.connectionStatus), `${label}.connectionStatus is invalid`);
  check(["FRESH", "STALE", "NO_DATA", "PAUSED"].includes(status.dataFreshness), `${label}.dataFreshness is invalid`);
  check(status.riskLevel === null || ["INFO", "WARNING", "CRITICAL", "FATAL"].includes(status.riskLevel), `${label}.riskLevel is invalid`);
  time(status.lastAttemptAt, `${label}.lastAttemptAt`, true); time(status.lastSuccessAt, `${label}.lastSuccessAt`, true); time(status.updatedAt, `${label}.updatedAt`);
  if (status.latestMetricId !== null) safeInt(status.latestMetricId, `${label}.latestMetricId`);
  check(Array.isArray(status.openIncidentIds), `${label}.openIncidentIds must be an array`);
  for (const incidentId of status.openIncidentIds ?? []) id(incidentId, `${label}.openIncidentIds[]`);
}

function validateIncident(event, label) {
  if (!validateCommonEvent(event, label, ["IncidentCreatedEvent", "IncidentUpdatedEvent", "IncidentResolvedEvent"])) return;
  const fields = ["timestamp", "sourceEventId", "incidentId", "databaseConfigId", "databaseName", "ruleId", "ruleType", "severity", "status", "openedAt", "lastObservedAt", "resolvedAt", "resolutionReason", "metricName", "metricValue", "thresholdValue", "sourceMetricId", "message", "incidentVersion"];
  if (!required(event, fields, label)) return;
  time(event.timestamp, `${label}.timestamp`); time(event.openedAt, `${label}.openedAt`); time(event.lastObservedAt, `${label}.lastObservedAt`); time(event.resolvedAt, `${label}.resolvedAt`, true);
  if (event.sourceEventId !== null) id(event.sourceEventId, `${label}.sourceEventId`);
  id(event.incidentId, `${label}.incidentId`); safeInt(event.databaseConfigId, `${label}.databaseConfigId`); safeInt(event.incidentVersion, `${label}.incidentVersion`);
  check(typeof event.databaseName === "string" && event.databaseName.length > 0, `${label}.databaseName must be text`); check(typeof event.ruleId === "string" && event.ruleId.length > 0, `${label}.ruleId must be text`);
  check(["WARNING", "CRITICAL", "FATAL"].includes(event.severity), `${label}.severity is invalid`); check(["OPEN", "RESOLVED"].includes(event.status), `${label}.status is invalid`);
  if (event.eventType === "IncidentResolvedEvent") {
    check(event.status === "RESOLVED", `${label} IncidentResolvedEvent must have status RESOLVED`);
  } else {
    check(event.status === "OPEN", `${label} ${event.eventType} must have status OPEN`);
  }
  check(event.resolutionReason === null || ["RECOVERED", "POLICY_CHANGED", "MONITORING_PAUSED", "CONFIG_CHANGED", "TARGET_DELETED"].includes(event.resolutionReason), `${label}.resolutionReason is invalid`);
  check(typeof event.metricName === "string" && event.metricName.length > 0, `${label}.metricName must be text`); number(event.metricValue, `${label}.metricValue`); number(event.thresholdValue, `${label}.thresholdValue`); if (event.sourceMetricId !== null) safeInt(event.sourceMetricId, `${label}.sourceMetricId`);
  check(typeof event.message === "string" && event.message.length > 0, `${label}.message must be text`); check(event.openedAt <= event.lastObservedAt, `${label}.openedAt cannot be later than lastObservedAt`);
  check(event.timestamp >= event.openedAt, `${label}.timestamp cannot precede openedAt`);
  if (event.status === "OPEN") check(event.resolvedAt === null && event.resolutionReason === null, `${label} OPEN must not be resolved`);
  if (event.status === "RESOLVED") check(event.resolvedAt !== null && event.resolutionReason !== null, `${label} RESOLVED needs resolution fields`);
}

function validateFixtureSet(data) {
  const f = data.fixtures;
  check(object(f), "fixtures must be an object"); if (!object(f)) return;
  ["metricSuccess", "metricWarmup", "metricFailure", "metricMeasuredZero", "metricPartialFailure"].forEach(name => validateMetric(f[name], `fixtures.${name}`));
  ["metricCollectedEvent", "metricMeasuredZeroEvent", "metricPartialFailureEvent", "metricOldConfigEvent"].forEach(name => validateMetricEvent(f[name], `fixtures.${name}`));
  for (const name of [
    "incidentOpenedEvent",
    "incidentResolvedEvent",
    "connectionFailureIncidentEvent",
    "collectionStaleIncidentEvent",
  ]) {
    validateIncident(f[name], "fixtures." + name);
  }
  if (validateCommonEvent(f.collectorHeartbeatEvent, "fixtures.collectorHeartbeatEvent", ["CollectorHeartbeatEvent"])) {
    const h = f.collectorHeartbeatEvent; check(typeof h.collectorId === "string" && h.collectorId.length > 0, "fixtures.collectorHeartbeatEvent.collectorId must be text"); time(h.timestamp, "fixtures.collectorHeartbeatEvent.timestamp"); time(h.lastCycleStartedAt, "fixtures.collectorHeartbeatEvent.lastCycleStartedAt", true); time(h.lastCycleCompletedAt, "fixtures.collectorHeartbeatEvent.lastCycleCompletedAt", true); check(typeof h.cycleInProgress === "boolean", "fixtures.collectorHeartbeatEvent.cycleInProgress must be boolean");
  }
  if (validateCommonEvent(f.monitoringStatusChangedEvent, "fixtures.monitoringStatusChangedEvent", ["MonitoringStatusChangedEvent"])) validateStatus(f.monitoringStatusChangedEvent, "fixtures.monitoringStatusChangedEvent");
  if (validateCommonEvent(f.statusMessage, "fixtures.statusMessage", ["MonitoringStatusChanged"])) { check(f.statusMessage.eventType === "MonitoringStatusChanged", "fixtures.statusMessage must be the STOMP MonitoringStatusChanged mapping"); validateStatus(f.statusMessage.data, "fixtures.statusMessage.data"); check(f.statusMessage.databaseConfigId === f.statusMessage.data?.databaseConfigId, "fixtures.statusMessage target does not match data"); }
  const push = f.webPushPayload; if (required(push, ["schemaVersion", "deliveryId", "incidentId", "type", "title", "body", "url", "tag", "sentAt"], "fixtures.webPushPayload")) { safeInt(push.schemaVersion, "fixtures.webPushPayload.schemaVersion"); safeInt(push.deliveryId, "fixtures.webPushPayload.deliveryId"); id(push.incidentId, "fixtures.webPushPayload.incidentId"); time(push.sentAt, "fixtures.webPushPayload.sentAt"); check(["INCIDENT_OPENED", "SEVERITY_INCREASED", "INCIDENT_RESOLVED"].includes(push.type), "fixtures.webPushPayload.type is invalid"); check(/^\/incidents\/[0-9a-f-]{36}$/.test(push.url), "fixtures.webPushPayload.url is invalid"); }
  const error = f.validationError; if (required(error, ["code", "message", "requestId", "fieldErrors"], "fixtures.validationError")) { id(error.requestId, "fixtures.validationError.requestId"); check(typeof error.code === "string" && typeof error.message === "string" && Array.isArray(error.fieldErrors), "fixtures.validationError fields are invalid"); }
}

function fixture(data, name, label) { const value = data.fixtures?.[name]; check(object(value), `${label} references missing fixture ${name}`); return value; }
function same(a, b, fields) { return fields.every(field => JSON.stringify(a?.[field]) === JSON.stringify(b?.[field])); }

function validateMappings(data) {
  const mappings = data.mappings; check(object(mappings), "mappings must be an object"); if (!object(mappings)) return;
  check(Array.isArray(mappings.metricRestToInternal), "mappings.metricRestToInternal must be an array");
  const mappingKeys = [];
  for (const [i, mapping] of (mappings.metricRestToInternal ?? []).entries()) {
    if (!required(mapping, ["restRef", "eventRef"], `mappings.metricRestToInternal[${i}]`)) continue;
    mappingKeys.push(`${mapping.restRef}->${mapping.eventRef}`);
    const rest = fixture(data, mapping.restRef, `mappings.metricRestToInternal[${i}]`); const event = fixture(data, mapping.eventRef, `mappings.metricRestToInternal[${i}]`);
    if (!rest || !event) continue; check(event.eventType === "MetricCollectedEvent", `mappings.metricRestToInternal[${i}] event must be MetricCollectedEvent`); check(rest.id === event.metricId && rest.databaseConfigId === event.databaseConfigId && rest.configVersion === event.configVersion, `mappings.metricRestToInternal[${i}] identity does not match`); check(same(rest, event, ["databaseConfigId", "configVersion", "timestamp", "collectionAttemptTime", "lastSuccessAt", ...metricFields, "collectionStatus", "errorCode", "errorMessage", "unavailableMetrics"]), `mappings.metricRestToInternal[${i}] metric fields do not match`);
  }
  check(new Set(mappingKeys).size === mappingKeys.length, "duplicate metric mapping is not allowed");
  for (const requiredMapping of requiredMetricMappings) {
    check(mappingKeys.includes(requiredMapping), `required metric mapping ${requiredMapping} is missing`);
  }
  check(mappingKeys.length === requiredMetricMappings.size,
    "metricRestToInternal must contain exactly the required metric mappings");
  const status = mappings.statusInternalToStomp; if (!required(status, ["internalRef", "stompRef"], "mappings.statusInternalToStomp")) return;
  const internal = fixture(data, status.internalRef, "mappings.statusInternalToStomp"); const stomp = fixture(data, status.stompRef, "mappings.statusInternalToStomp"); if (!internal || !stomp) return;
  check(internal.eventType === "MonitoringStatusChangedEvent", "internal status mapping must use MonitoringStatusChangedEvent"); check(stomp.eventType === "MonitoringStatusChanged", "STOMP status mapping must use MonitoringStatusChanged"); check(internal.eventId === stomp.eventId, "internal and STOMP status eventId must be preserved"); check(internal.databaseConfigId === stomp.databaseConfigId, "internal and STOMP status target must match"); check(same(internal, stomp.data, statusFields.filter(field => field !== "databaseConfigId")), "internal and STOMP status data do not match");
}

function indexesMatch(actual, indexes, input, label) { check(Array.isArray(actual), `${label} must be an array`); const refs = (actual ?? []).map(index => input[index]?.fixtureRef); check(refs.every(Boolean), `${label} contains an invalid input index`); return refs; }
function validateEventSequence(data, scenario) {
  const input = scenario.input; const expected = scenario.expected;
  check(Array.isArray(input) && input.length > 0, `${scenario.id}.input must be a non-empty array`); if (!Array.isArray(input) || !object(expected)) return;
  for (const [i, step] of input.entries()) { check(object(step), `${scenario.id}.input[${i}] must be an object`); if (!object(step)) continue; const event = fixture(data, step.fixtureRef, `${scenario.id}.input[${i}]`); time(step.receivedAt, `${scenario.id}.input[${i}].receivedAt`); if (event) { check(typeof event.eventId === "string", `${scenario.id}.input[${i}] must reference an event`); check(step.receivedAt >= event.publishedAt, `${scenario.id}.input[${i}] receivedAt precedes publishedAt`); } if (i > 0) check(input[i - 1].receivedAt <= step.receivedAt, `${scenario.id}.input receivedAt order is invalid`); }
  const accepted = indexesMatch(expected.acceptedInputIndexes, expected.acceptedInputIndexes, input, `${scenario.id}.expected.acceptedInputIndexes`); const ignored = indexesMatch(expected.ignoredInputIndexes, expected.ignoredInputIndexes, input, `${scenario.id}.expected.ignoredInputIndexes`);
  check(new Set([...(expected.acceptedInputIndexes ?? []), ...(expected.ignoredInputIndexes ?? [])]).size === input.length, `${scenario.id} must classify every input`); check(new Set(expected.acceptedInputIndexes ?? []).size + new Set(expected.ignoredInputIndexes ?? []).size === input.length, `${scenario.id} accepted/ignored indexes overlap`);
  check(JSON.stringify(accepted) === JSON.stringify(expected.acceptedFixtureRefs), `${scenario.id}.acceptedFixtureRefs does not match input indexes`); check(JSON.stringify(ignored) === JSON.stringify(expected.ignoredFixtureRefs), `${scenario.id}.ignoredFixtureRefs does not match input indexes`);
   const current = expected.currentState;
   const currentFixture = fixture(data, current?.fixtureRef, `${scenario.id}.expected.currentState`);
   if (currentFixture) {
     check(currentFixture.metricId === current.metricId &&
       currentFixture.databaseConfigId === current.databaseConfigId &&
       currentFixture.configVersion === current.configVersion,
       `${scenario.id}.currentState identity does not match fixture`);
   }
   const lastAccepted = expected.acceptedInputIndexes?.at(-1);
   check(current?.fixtureRef === input[lastAccepted]?.fixtureRef,
     `${scenario.id}.currentState must be the last accepted input`);
  const first = fixture(data, input[0].fixtureRef, scenario.id); const second = fixture(data, input[1]?.fixtureRef, scenario.id);
  if (expected.reason === "DUPLICATE_EVENT_ID") check(first?.eventId === second?.eventId && expected.ignoredInputIndexes?.includes(1), `${scenario.id} duplicate expectation is inconsistent`);
   if (["DUPLICATE_EVENT_ID", "LATE_OR_OUT_OF_ORDER", "OLD_CONFIG_VERSION"].includes(expected.reason)) {
     check(expected.timerAction === "UNCHANGED", `${scenario.id} ignored input must leave timers unchanged`);
   }
  if (expected.reason === "LATE_OR_OUT_OF_ORDER") check(new Date(second?.timestamp) < new Date(first?.timestamp) && expected.ignoredInputIndexes?.includes(1), `${scenario.id} late expectation is inconsistent`);
  if (expected.reason === "OLD_CONFIG_VERSION") check(second?.configVersion < first?.configVersion && expected.ignoredInputIndexes?.includes(1), `${scenario.id} old-config expectation is inconsistent`);
  if (expected.reason === "ACCEPTED_INVALID_OBSERVATION") check(expected.acceptedInputIndexes?.some(index => fixture(data, input[index].fixtureRef, scenario.id)?.collectionStatus === "PARTIAL_FAILURE") && expected.timerAction === "RESET_CANDIDATE_AND_RECOVERY_TIMERS", `${scenario.id} invalid-observation expectation must use RESET_CANDIDATE_AND_RECOVERY_TIMERS`);
}

function validateScenario(data, scenario) {
  check(object(scenario) && typeof scenario.id === "string" && typeof scenario.kind === "string", "scenario must have id and kind"); if (!object(scenario)) return;
  if (scenario.kind === "event-sequence") validateEventSequence(data, scenario);
  else if (scenario.kind === "state-boundary") {
     const input = scenario.input;
     const expected = scenario.expected;
     if (!required(input, ["enabled", "activationAt", "latestAcceptedCollectionAttemptAt", "openIncidentRefs", "staleAfterSeconds", "evaluatedAt"], `${scenario.id}.input`) ||
         !required(expected, ["elapsedSeconds", "dataFreshness", "stale", "riskLevel", "basis"], `${scenario.id}.expected`)) return;
     check(input.enabled === true, `${scenario.id} must be enabled`);
     time(input.activationAt, `${scenario.id}.input.activationAt`);
     time(input.latestAcceptedCollectionAttemptAt, `${scenario.id}.input.latestAcceptedCollectionAttemptAt`, true);
     time(input.evaluatedAt, `${scenario.id}.input.evaluatedAt`);
     safeInt(input.staleAfterSeconds, `${scenario.id}.input.staleAfterSeconds`);
     check(input.staleAfterSeconds >= 30 && input.staleAfterSeconds <= 300, `${scenario.id}.staleAfterSeconds is outside contract range`);
     check(Array.isArray(input.openIncidentRefs), `${scenario.id}.openIncidentRefs must be an array`);
     if (input.latestAcceptedCollectionAttemptAt !== null) {
       check(new Date(input.latestAcceptedCollectionAttemptAt) >= new Date(input.activationAt),
         `${scenario.id} accepted observation cannot precede activationAt`);
     }
     const rank = { INFO: 1, WARNING: 2, CRITICAL: 3, FATAL: 4 };
     let highest = 0;
     for (const ref of input.openIncidentRefs ?? []) {
       const open = fixture(data, ref, scenario.id);
       if (!open) continue;
       check(open.status === "OPEN", `${scenario.id} openIncidentRefs must be OPEN`);
       check(new Date(open.publishedAt) <= new Date(input.evaluatedAt),
         `${scenario.id} incident must exist by evaluatedAt`);
       highest = Math.max(highest, rank[open.severity] ?? 0);
     }
     const basis = input.latestAcceptedCollectionAttemptAt === null
       ? "ACTIVATION_BEFORE_FIRST_ACCEPTED_OBSERVATION"
       : "LAST_ACCEPTED_COLLECTION_ATTEMPT";
     const referenceAt = input.latestAcceptedCollectionAttemptAt ?? input.activationAt;
     const elapsed = (new Date(input.evaluatedAt) - new Date(referenceAt)) / 1000;
     check(elapsed >= 0, `${scenario.id}.evaluatedAt cannot precede its freshness basis`);
     check(Math.abs(elapsed - expected.elapsedSeconds) < 0.001, `${scenario.id}.elapsedSeconds is inconsistent`);
     const stale = elapsed >= input.staleAfterSeconds;
     const freshness = stale
       ? "STALE"
       : input.latestAcceptedCollectionAttemptAt === null ? "NO_DATA" : "FRESH";
     check(expected.stale === stale && expected.dataFreshness === freshness,
       input.latestAcceptedCollectionAttemptAt !== null && !stale
         ? `${scenario.id} accepted non-stale observation must be FRESH`
         : `${scenario.id} stale boundary expectation is inconsistent`);
     check(expected.basis === basis, `${scenario.id} stale basis is inconsistent`);
     if (stale) {
       const hasStaleIncident = (input.openIncidentRefs ?? []).some(ref => {
         const incident = data.fixtures?.[ref];
         return incident?.status === "OPEN" &&
           incident.ruleId === "COLLECTION_STALE" &&
           incident.severity === "CRITICAL";
       });
       check(hasStaleIncident,
         `${scenario.id} STALE requires an OPEN COLLECTION_STALE CRITICAL incident`);
     }
     const expectedRank = expected.riskLevel === null ? 0 : rank[expected.riskLevel];
     check(expectedRank === highest, `${scenario.id} riskLevel must be the highest OPEN severity`);
  } else if (scenario.kind === "policy-change") {
    const input = scenario.input; const expected = scenario.expected; if (!required(input, ["databaseConfigId", "changedAt", "oldPolicyVersion", "newPolicyVersion", "openIncidentRefs"], `${scenario.id}.input`) || !required(expected, ["resolvedIncidentRefs", "maintainedIncidentRefs", "resolutionReason", "metricTimers", "systemIncidents", "systemTimers"], `${scenario.id}.expected`)) return;
    safeInt(input.databaseConfigId, `${scenario.id}.input.databaseConfigId`); safeInt(input.oldPolicyVersion, `${scenario.id}.input.oldPolicyVersion`); safeInt(input.newPolicyVersion, `${scenario.id}.input.newPolicyVersion`); check(input.newPolicyVersion === input.oldPolicyVersion + 1, `${scenario.id} policy version must increment`); time(input.changedAt, `${scenario.id}.input.changedAt`); check(Array.isArray(input.openIncidentRefs), `${scenario.id}.openIncidentRefs must be an array`);
    const metricRefs = [], systemRefs = []; for (const ref of input.openIncidentRefs ?? []) { const incident = fixture(data, ref, scenario.id); if (!incident) continue; check(incident.databaseConfigId === input.databaseConfigId && incident.status === "OPEN", `${scenario.id} incident identity is inconsistent`); check(new Date(incident.publishedAt) <= new Date(input.changedAt), `${scenario.id} incident must exist by changedAt`); (incident.ruleId === "CONNECTION_FAILURE" || incident.ruleId === "COLLECTION_STALE" ? systemRefs : metricRefs).push(ref); }
    check(JSON.stringify(expected.resolvedIncidentRefs) === JSON.stringify(metricRefs), `${scenario.id} must resolve metric incidents only`); check(JSON.stringify(expected.maintainedIncidentRefs) === JSON.stringify(systemRefs), `${scenario.id} must maintain system incidents`); check(expected.resolutionReason === "POLICY_CHANGED" && expected.metricTimers === "RESET" && expected.systemIncidents === "MAINTAIN_OPEN", `${scenario.id} policy expectation is inconsistent`);
    const systemClasses = new Set(systemRefs.map(ref => data.fixtures?.[ref]?.ruleId));
    check(systemClasses.size === 2 && systemClasses.has("CONNECTION_FAILURE") &&
      systemClasses.has("COLLECTION_STALE"),
      `${scenario.id} policy sample must include both system incident classes`);
    check(object(expected.systemTimers) &&
      expected.systemTimers.CONNECTION_FAILURE === "MAINTAIN" &&
      expected.systemTimers.COLLECTION_STALE === "MAINTAIN" &&
      Object.keys(expected.systemTimers).length === 2,
      `${scenario.id} system timers must maintain CONNECTION_FAILURE and COLLECTION_STALE`);
  } else if (scenario.kind === "notification-cooldown") {
    const input = scenario.input;
    const expected = scenario.expected;
    if (!required(input, ["incidentRef", "incidentVersion", "channel", "recipientId",
        "lastSuccessfulNotificationAt", "queuedAt", "mergedEscalationAt",
        "fatalEscalationAt", "cooldownSeconds", "queuedJob"], `${scenario.id}.input`) ||
        !required(expected, ["status", "eligibleAt", "expiresAt", "at600Seconds",
        "atEligibility", "atExpiry",
        "mergedEscalationDoesNotSlideEligibility", "mergedEscalationEligibleAt",
        "fatalOverride"], `${scenario.id}.expected`)) return;
    const incident = fixture(data, input.incidentRef, scenario.id);
    if (incident) id(incident.incidentId, `${scenario.id}.incidentId`);
    safeInt(input.incidentVersion, `${scenario.id}.input.incidentVersion`);
    safeInt(input.recipientId, `${scenario.id}.input.recipientId`);
    check(input.channel === "SLACK" || input.channel === "WEB_PUSH", `${scenario.id}.channel is invalid`);
    time(input.lastSuccessfulNotificationAt, `${scenario.id}.input.lastSuccessfulNotificationAt`);
    time(input.queuedAt, `${scenario.id}.input.queuedAt`);
    time(input.mergedEscalationAt, `${scenario.id}.input.mergedEscalationAt`);
    time(input.fatalEscalationAt, `${scenario.id}.input.fatalEscalationAt`);
    safeInt(input.cooldownSeconds, `${scenario.id}.input.cooldownSeconds`);
    check(input.cooldownSeconds >= 60 && input.cooldownSeconds <= 3600, `${scenario.id}.cooldownSeconds is outside contract range`);
    const job = input.queuedJob;
    if (required(job, ["jobId", "incidentId", "incidentVersion", "channel",
        "recipientId", "severity", "status", "eligibleAt", "expiresAt"],
        `${scenario.id}.input.queuedJob`)) {
      id(job.jobId, `${scenario.id}.jobId`);
      id(job.incidentId, `${scenario.id}.jobId.incidentId`);
      safeInt(job.incidentVersion, `${scenario.id}.jobId.incidentVersion`);
      safeInt(job.recipientId, `${scenario.id}.jobId.recipientId`);
      check(job.severity === "WARNING" || job.severity === "CRITICAL", `${scenario.id}.queuedJob must be non-fatal`);
      check(job.incidentId === incident?.incidentId && job.incidentVersion === input.incidentVersion &&
        job.channel === input.channel && job.recipientId === input.recipientId && job.status === "PENDING",
        `${scenario.id}.queuedJob identity/status is inconsistent`);
      time(job.eligibleAt, `${scenario.id}.queuedJob.eligibleAt`);
      time(job.expiresAt, `${scenario.id}.queuedJob.expiresAt`);
      const eligible = new Date(input.lastSuccessfulNotificationAt).getTime() + input.cooldownSeconds * 1000;
      check(job.eligibleAt === new Date(eligible).toISOString(), `${scenario.id}.eligibleAt must be persisted from cooldown`);
      check(job.expiresAt === new Date(eligible + 600000).toISOString(), `${scenario.id}.expiresAt must be eligibleAt + 600s`);
      check(expected.eligibleAt === job.eligibleAt && expected.expiresAt === job.expiresAt, `${scenario.id}.expected queue times do not match persisted job`);
    }
    time(expected.at600Seconds?.observedAt, `${scenario.id}.expected.at600Seconds.observedAt`);
    const at600 = new Date(expected.at600Seconds?.observedAt).getTime();
    const queuedAt = new Date(input.queuedAt).getTime();
    const eligibleAt = new Date(expected.eligibleAt).getTime();
    const expiresAt = new Date(expected.expiresAt).getTime();
    check(at600 === queuedAt + 600000,
      `${scenario.id}.at600Seconds must be observed at queuedAt + 600s`);
    check(at600 < eligibleAt && at600 < expiresAt,
      `${scenario.id}.at600Seconds must remain before eligibleAt and expiresAt`);
    check(expected.status === "PENDING" && expected.at600Seconds.status === "PENDING" && expected.at600Seconds.expired === false, `${scenario.id} 600s observation must remain pending`);
    time(expected.atEligibility?.observedAt, `${scenario.id}.expected.atEligibility.observedAt`);
    check(expected.atEligibility?.observedAt === expected.eligibleAt &&
      expected.atEligibility?.status === "READY" &&
      expected.atEligibility?.expired === false,
      `${scenario.id}.atEligibility must be READY at eligibleAt`);
    time(expected.atExpiry?.observedAt, `${scenario.id}.expected.atExpiry.observedAt`);
    check(expected.atExpiry?.observedAt === expected.expiresAt &&
      expected.atExpiry?.status === "CANCELLED" &&
      expected.atExpiry?.expired === true,
      `${scenario.id}.atExpiry must be CANCELLED at expiresAt`);
    check(expected.mergedEscalationDoesNotSlideEligibility === true && expected.mergedEscalationEligibleAt === expected.eligibleAt, `${scenario.id} merged escalation must preserve eligibility`);
    check(expected.fatalOverride?.status === "READY" && expected.fatalOverride.severity === "FATAL" &&
      expected.fatalOverride.eligibleAt === input.fatalEscalationAt && expected.fatalOverride.cancelsQueuedNonFatal === true,
      `${scenario.id} FATAL must supersede queued non-fatal work immediately`);
  } else check(false, `${scenario.id} has unknown kind ${scenario.kind}`);
}

let data;
try { data = JSON.parse(fs.readFileSync(target, "utf8")); } catch (error) { console.error(`INVALID: cannot parse ${target}: ${error.message}`); process.exit(1); }
check(object(data), "document must be an object");
if (object(data)) {
  check(data.contractVersion === "0.2", "contractVersion must be 0.2"); check(typeof data.description === "string" && data.description.length > 0, "description is required");
  validateFixtureSet(data); validateMappings(data); check(object(data.notificationPolicy), "notificationPolicy must be an object"); if (object(data.notificationPolicy)) { check(data.notificationPolicy.defaultCooldownSeconds === 300, "default cooldown must remain 300s"); check(data.notificationPolicy.minCooldownSeconds === 60, "minimum cooldown must remain 60s"); check(data.notificationPolicy.maxCooldownSeconds === 3600, "max cooldown must remain 3600s"); check(data.notificationPolicy.queuedJobTtlSeconds === 600, "queued job TTL must remain 600s"); }
  check(Array.isArray(data.scenarios) && data.scenarios.length > 0, "scenarios must be a non-empty array"); const scenarioIds = new Set(); for (const scenario of data.scenarios ?? []) { if (scenarioIds.has(scenario.id)) check(false, `duplicate scenario id ${scenario.id}`); scenarioIds.add(scenario.id); validateScenario(data, scenario); }
  for (const requiredScenarioId of requiredScenarioIds) {
    check(scenarioIds.has(requiredScenarioId), `required scenario id ${requiredScenarioId} is missing`);
  }
  check(scenarioIds.size === requiredScenarioIds.size,
    "scenarios must contain exactly the required scenario IDs");
  const eventIdOwners = new Map(); for (const [name, value] of Object.entries(data.fixtures ?? {})) { if (value?.eventId) { const prior = eventIdOwners.get(value.eventId); if (prior && !(new Set([prior, name]).size === 2 && new Set([prior, name]).has("monitoringStatusChangedEvent") && new Set([prior, name]).has("statusMessage"))) check(false, `eventId ${value.eventId} is duplicated by ${prior} and ${name}`); eventIdOwners.set(value.eventId, name); } }
}
if (errors.length) { console.error(`INVALID: ${target}`); for (const error of errors) console.error(`- ${error}`); process.exit(1); }
console.log(`VALID: ${target} fixtures=${Object.keys(data.fixtures).length} scenarios=${data.scenarios.length}`);
