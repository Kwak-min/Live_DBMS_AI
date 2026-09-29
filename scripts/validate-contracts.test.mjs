import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { spawnSync } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("..", import.meta.url));
const validator = path.join(root, "scripts", "validate-contracts.mjs");
const canonicalPath = path.join(root, "docs", "contract-examples.json");
const canonical = JSON.parse(await readFile(canonicalPath, "utf8"));
let temporaryDirectory;

before(async () => {
  temporaryDirectory = await mkdtemp(path.join(tmpdir(), "validate-contracts-"));
});

after(async () => {
  await rm(temporaryDirectory, { force: true, recursive: true });
});

function run(file) {
  return spawnSync(process.execPath, [validator, file], {
    cwd: root,
    encoding: "utf8",
  });
}

function scenario(data, id) {
  const value = data.scenarios.find(candidate => candidate.id === id);
  assert.ok(value, `missing test setup scenario ${id}`);
  return value;
}

async function mutatedFile(name, mutate) {
  const data = structuredClone(canonical);
  mutate(data);
  const file = path.join(temporaryDirectory, `${name}.json`);
  await writeFile(file, `${JSON.stringify(data, null, 2)}\n`);
  return file;
}

async function assertRejected(name, mutate, diagnostic) {
  const result = run(await mutatedFile(name, mutate));
  assert.equal(result.status, 1, `${name} unexpectedly passed:\n${result.stdout}`);
  assert.match(`${result.stdout}${result.stderr}`, diagnostic);
}

test("canonical contract fixture is valid", () => {
  const result = run(canonicalPath);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /fixtures=18 scenarios=10/);
});

test("malformed JSON is rejected", async () => {
  const file = path.join(temporaryDirectory, "malformed.json");
  await writeFile(file, "{");
  const result = run(file);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /INVALID: cannot parse/);
});

for (const id of [
  "cooldown-3600-keeps-eligibility",
  "policy-change-metric-only",
  "duplicate-event-id",
]) {
  test(`required scenario ${id} cannot be omitted`, async () => {
    await assertRejected(
      `missing-${id}`,
      data => {
        data.scenarios = data.scenarios.filter(item => item.id !== id);
      },
      /required scenario id/,
    );
  });
}

test("required scenario IDs cannot be duplicated", async () => {
  await assertRejected(
    "duplicate-scenario",
    data => data.scenarios.push(structuredClone(data.scenarios[0])),
    /duplicate scenario id/,
  );
});

test("required metric mapping cannot be omitted", async () => {
  await assertRejected(
    "missing-mapping",
    data => {
      data.mappings.metricRestToInternal =
        data.mappings.metricRestToInternal.filter(
          mapping => mapping.restRef !== "metricMeasuredZero",
        );
    },
    /required metric mapping/,
  );
});

test("required metric mappings cannot be duplicated", async () => {
  await assertRejected(
    "duplicate-mapping",
    data => data.mappings.metricRestToInternal.push(
      structuredClone(data.mappings.metricRestToInternal[0]),
    ),
    /duplicate metric mapping/,
  );
});

test("minimum cooldown below 60 seconds is rejected", async () => {
  await assertRejected(
    "minimum-cooldown-59",
    data => {
      data.notificationPolicy.minCooldownSeconds = 59;
    },
    /minimum cooldown must remain 60s/,
  );
});

function makeAcceptedNonStale(data, freshness) {
  const boundary = scenario(data, "accepted-observation-fresh");
  boundary.input.latestAcceptedCollectionAttemptAt =
    "2026-09-28T04:00:30.000Z";
  boundary.expected.elapsedSeconds = 29.999;
  boundary.expected.dataFreshness = freshness;
  boundary.expected.basis = "LAST_ACCEPTED_COLLECTION_ATTEMPT";
}

test("accepted non-stale observation cannot remain NO_DATA", async () => {
  await assertRejected(
    "accepted-no-data",
    data => makeAcceptedNonStale(data, "NO_DATA"),
    /accepted non-stale observation must be FRESH/,
  );
});

test("accepted non-stale observation is FRESH", async () => {
  const result = run(await mutatedFile(
    "accepted-fresh",
    data => makeAcceptedNonStale(data, "FRESH"),
  ));
  assert.equal(result.status, 0, result.stderr);
});

test("STALE requires an OPEN COLLECTION_STALE CRITICAL incident", async () => {
  await assertRejected(
    "stale-without-incident",
    data => {
      const boundary = scenario(data, "stale-boundary-at");
      boundary.input.openIncidentRefs = [];
      boundary.expected.riskLevel = null;
    },
    /STALE requires an OPEN COLLECTION_STALE CRITICAL incident/,
  );
});

test("maximum risk sample keeps concurrent stale and failure incidents", async () => {
  await assertRejected(
    "fatal-without-stale",
    data => {
      const boundary = scenario(data, "stale-preserves-fatal-risk");
      boundary.input.openIncidentRefs = ["connectionFailureIncidentEvent"];
    },
    /must include an OPEN COLLECTION_STALE CRITICAL incident/,
  );
});

test("IncidentResolvedEvent cannot be OPEN", async () => {
  await assertRejected(
    "resolved-event-open",
    data => {
      data.fixtures.incidentResolvedEvent.status = "OPEN";
      data.fixtures.incidentResolvedEvent.resolvedAt = null;
      data.fixtures.incidentResolvedEvent.resolutionReason = null;
    },
    /IncidentResolvedEvent must have status RESOLVED/,
  );
});

test("accepted partial failure uses the exact reset action", async () => {
  await assertRejected(
    "reset-nothing",
    data => {
      scenario(data, "accepted-partial-failure-resets-timers")
        .expected.timerAction = "RESET_NOTHING";
    },
    /RESET_CANDIDATE_AND_RECOVERY_TIMERS/,
  );
});

test("accepted partial failure cannot bypass reset validation with an unknown reason", async () => {
  await assertRejected(
    "unknown-reason-reset-nothing",
    data => {
      const partial = scenario(data, "accepted-partial-failure-resets-timers");
      partial.expected.reason = "UNKNOWN";
      partial.expected.timerAction = "RESET_NOTHING";
    },
    /expected\.reason must be ACCEPTED_INVALID_OBSERVATION/,
  );
});

test("required scenario IDs cannot be rebound to another valid scenario kind", async () => {
  await assertRejected(
    "required-id-wrong-kind",
    data => {
      const replacement = structuredClone(scenario(data, "stale-boundary-before"));
      replacement.id = "duplicate-event-id";
      data.scenarios = data.scenarios.map(item =>
        item.id === "duplicate-event-id" ? replacement : item,
      );
    },
    /duplicate-event-id must have kind event-sequence/,
  );
});

test("state-boundary IDs cannot be rebound to another valid state body", async () => {
  await assertRejected(
    "state-id-wrong-body",
    data => {
      const replacement = structuredClone(scenario(data, "stale-boundary-at"));
      replacement.id = "stale-boundary-before";
      data.scenarios = data.scenarios.map(item =>
        item.id === "stale-boundary-before" ? replacement : item,
      );
    },
    /stale-boundary-before state-boundary expectation does not match required scenario/,
  );
});

test("maximum cooldown scenario cannot be reduced below 3600 seconds", async () => {
  await assertRejected(
    "max-cooldown-3599",
    data => {
      const cooldown = scenario(data, "cooldown-3600-keeps-eligibility");
      cooldown.input.cooldownSeconds = 3599;
      cooldown.input.queuedJob.eligibleAt = "2026-09-28T03:59:59.000Z";
      cooldown.input.queuedJob.expiresAt = "2026-09-28T04:09:59.000Z";
      cooldown.expected.eligibleAt = "2026-09-28T03:59:59.000Z";
      cooldown.expected.expiresAt = "2026-09-28T04:09:59.000Z";
      cooldown.expected.atEligibility.observedAt = "2026-09-28T03:59:59.000Z";
      cooldown.expected.atExpiry.observedAt = "2026-09-28T04:09:59.000Z";
      cooldown.expected.mergedEscalationEligibleAt = "2026-09-28T03:59:59.000Z";
    },
    /cooldown-3600-keeps-eligibility cooldownSeconds must be 3600/,
  );
});

test("ignored input leaves timers unchanged", async () => {
  await assertRejected(
    "ignored-timers",
    data => {
      scenario(data, "duplicate-event-id").expected.timerAction =
        "RESET_CANDIDATE_AND_RECOVERY_TIMERS";
    },
    /ignored input must leave timers unchanged/,
  );
});

test("ignored input leaves current state unchanged", async () => {
  await assertRejected(
    "ignored-current-state",
    data => {
      scenario(data, "duplicate-event-id").expected.currentState.metricId += 1;
    },
    /currentState identity does not match fixture/,
  );
});

test("600-second observation cannot drift past expiry", async () => {
  await assertRejected(
    "at-600-after-expiry",
    data => {
      scenario(data, "cooldown-3600-keeps-eligibility")
        .expected.at600Seconds.observedAt = "2026-09-28T04:10:00.001Z";
    },
    /at600Seconds/,
  );
});

test("expiry equality cancels instead of remaining pending", async () => {
  await assertRejected(
    "expiry-equality-pending",
    data => {
      const cooldown = scenario(data, "cooldown-3600-keeps-eligibility");
      cooldown.expected.at600Seconds.observedAt = cooldown.expected.expiresAt;
      cooldown.expected.at600Seconds.status = "PENDING";
      cooldown.expected.at600Seconds.expired = false;
    },
    /at600Seconds/,
  );
});

test("eligibility equality is concretely READY", async () => {
  await assertRejected(
    "eligibility-equality-pending",
    data => {
      const cooldown = scenario(data, "cooldown-3600-keeps-eligibility");
      cooldown.expected.atEligibility = {
        observedAt: cooldown.expected.eligibleAt,
        status: "PENDING",
        expired: false,
      };
    },
    /atEligibility/,
  );
});

test("expiry equality is concretely CANCELLED", async () => {
  await assertRejected(
    "expiry-equality-ready",
    data => {
      const cooldown = scenario(data, "cooldown-3600-keeps-eligibility");
      cooldown.expected.atExpiry = {
        observedAt: cooldown.expected.expiresAt,
        status: "READY",
        expired: false,
      };
    },
    /atExpiry/,
  );
});

test("policy sample keeps both system incident classes", async () => {
  await assertRejected(
    "policy-missing-stale",
    data => {
      const policy = scenario(data, "policy-change-metric-only");
      policy.input.openIncidentRefs = policy.input.openIncidentRefs.filter(
        ref => ref !== "collectionStaleIncidentEvent",
      );
      policy.expected.maintainedIncidentRefs =
        policy.expected.maintainedIncidentRefs.filter(
          ref => ref !== "collectionStaleIncidentEvent",
        );
    },
    /policy sample must include both system incident classes/,
  );
});

test("policy sample keeps both system incident timers", async () => {
  await assertRejected(
    "policy-resets-stale-timer",
    data => {
      const policy = scenario(data, "policy-change-metric-only");
      policy.expected.systemTimers = {
        CONNECTION_FAILURE: "MAINTAIN",
        COLLECTION_STALE: "RESET",
      };
    },
    /system timers/,
  );
});

test("accepted observation cannot precede activation", async () => {
  await assertRejected(
    "accepted-before-activation",
    data => {
      const boundary = scenario(data, "stale-preserves-fatal-risk");
      boundary.input.activationAt = "2026-09-28T04:00:00.000Z";
      boundary.input.latestAcceptedCollectionAttemptAt =
        "2026-09-28T03:59:00.000Z";
    },
    /accepted observation cannot precede activationAt/,
  );
});

test("referenced incidents must exist by scenario evaluation", async () => {
  await assertRejected(
    "incident-after-evaluation",
    data => {
      scenario(data, "stale-boundary-at").input.evaluatedAt =
        "2026-09-28T04:00:59.999Z";
    },
    /incident must exist by evaluatedAt/,
  );
});
