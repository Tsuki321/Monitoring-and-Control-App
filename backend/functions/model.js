"use strict";

// Versioned port of the existing Kotlin ridge model. No measured accuracy is implied by the prior.
const VERSION = "ridge-monthly-v1";
const DAY = 86400000;
const specs = [
  { key: "pumice", ratedHours: 15000, ratedDays: 1825, profile: "particulate", turb: .7, tds: .1, action: "RINSE" },
  { key: "pebbles", ratedHours: 30000, ratedDays: 3650, profile: "particulate", turb: .35, tds: .05, action: "RINSE" },
  { key: "lava_rock", ratedHours: 30000, ratedDays: 3650, profile: "particulate", turb: .6, tds: .1, action: "RINSE" },
  { key: "carbon", ratedHours: 350, ratedDays: 42, profile: "adsorption", turb: .2, tds: 1, action: "REPLACE" },
  { key: "multilayer_foam", ratedHours: 1500, ratedDays: 180, profile: "foam", turb: 1, tds: .05, action: "RINSE" },
];
const finite = (x, fallback = 0) => typeof x === "number" && Number.isFinite(x) ? x : fallback;
const clamp = (x, lo, hi) => Math.max(lo, Math.min(hi, finite(x)));
function features(s) { return [1, clamp(s.tds / 1000, 0, 1), clamp(s.turbidity / 3000, 0, 1), clamp(Math.abs(s.ph - 7), 0, 7)]; }
function valid(s) { return Number.isFinite(s.ph) && s.ph >= 0 && s.ph <= 14 && Number.isFinite(s.tds) && s.tds >= 0 && Number.isFinite(s.turbidity) && s.turbidity >= 0 && s.turbidity < 2999; }
function counter(value) { return typeof value === "number" && Number.isSafeInteger(value) && value >= 0; }
function load(b, x) { return clamp(b.reduce((v, c, i) => v + c * x[i], 0), .25, 4); }

function ridge(rows, targets) {
  if (rows.length < 4 || targets.length !== rows.length) throw new Error("Insufficient training rows");
  const a = Array.from({ length: 4 }, (_, i) => Array.from({ length: 5 }, (_, j) => j === i ? .01 : 0));
  rows.forEach((x, r) => x.forEach((v, i) => {
    x.forEach((w, j) => { a[i][j] += v * w; }); a[i][4] += v * targets[r];
  }));
  for (let i = 0; i < 4; i++) {
    let pivot = i;
    for (let j = i + 1; j < 4; j++) if (Math.abs(a[j][i]) > Math.abs(a[pivot][i])) pivot = j;
    [a[i], a[pivot]] = [a[pivot], a[i]];
    if (Math.abs(a[i][i]) < 1e-12) throw new Error("Singular fit");
    for (let j = i + 1; j < 4; j++) {
      const f = a[j][i] / a[i][i];
      for (let k = i; k <= 4; k++) a[j][k] -= f * a[i][k];
    }
  }
  const b = [0, 0, 0, 0];
  for (let i = 3; i >= 0; i--) {
    let v = a[i][4]; for (let j = i + 1; j < 4; j++) v -= a[i][j] * b[j];
    b[i] = v / a[i][i];
  }
  if (!b.every(Number.isFinite)) throw new Error("Non-finite fit");
  return b;
}

function synthetic(spec, index) {
  let state = 0x5EED0000n + BigInt(index);
  const next = () => { state = BigInt.asUintN(64, state * 6364136223846793005n + 1442695040888963407n); return Number(state >> 11n) / 9007199254740992; };
  for (let i = 0; i < 4; i++) next();
  const normal = () => Math.sqrt(-2 * Math.log(Math.max(next(), 1e-12))) * Math.cos(2 * Math.PI * next());
  const resistance = r => { const p = Math.max(.5 * (1 - clamp(.18 * r, 0, .9)), .02); return (1 - p) ** 2 / p ** 3; };
  const rows = [], targets = [];
  for (let i = 0; i < 400; i++) {
    const tds = 40 + 860 * next() ** 1.8, turbidity = .5 + 599.5 * next() ** 3.5;
    const ph = next() < .15 ? 5 + 4.5 * next() : clamp(7 + normal() * .5, 5, 9.5);
    const r = (spec.turb * turbidity / 50 + spec.tds * tds / 300) / (spec.turb + spec.tds);
    const cake = resistance(r) / resistance(1);
    const base = spec.profile === "adsorption" ? Math.max(r, .001) ** (1 - 1 / 2.2)
      : spec.profile === "foam" ? .65 * r ** .8 + .35 * cake : cake;
    const truth = base * (1 + .28 * Math.abs(ph - 7) ** 1.6);
    targets.push(clamp(truth + normal() * (.03 + .12 * truth), .25, 4));
    rows.push(features({ tds, turbidity, ph }));
  }
  return { rows, targets };
}
const priors = specs.map(synthetic);
function train(spec, observations = []) {
  const prior = priors[specs.findIndex(s => s.key === spec.key)];
  const rows = prior.rows.map(x => [...x]), targets = [...prior.targets];
  const clean = observations.filter(o => o.stageKey === spec.key && o.features?.length === 4 && o.features.every(Number.isFinite) && Number.isFinite(o.target));
  if (clean.length >= 8) clean.forEach(o => { rows.push(o.features.map(v => v * 5)); targets.push(clamp(o.target, .25, 4) * 5); });
  const coefficients = ridge(rows, targets);
  const mean = prior.targets.reduce((a, b) => a + b, 0) / 400;
  const ss = prior.targets.reduce((a, y) => a + (y - mean) ** 2, 0);
  const residual = prior.targets.reduce((a, y, i) => a + (y - prior.rows[i].reduce((v, x, j) => v + x * coefficients[j], 0)) ** 2, 0);
  return { coefficients, syntheticSamples: 400, realObservations: clean.length,
    fittedOnRealData: clean.length >= 8, syntheticFitR2: 1 - residual / ss };
}
const baseline = Object.fromEntries(specs.map(s => [s.key, train(s)]));
function newStage(time, revision = 0) {
  return { installedAt: time, revision, usage: 0, hours: 0, measuredHours: 0,
    sums: [0, 0, 0], rinses: 0, intervalComplete: true };
}

/** Idempotent source ordering; gaps retain measured runtime but never fabricate water readings. */
function advance(current, sample) {
  if (current?.sample && (sample.time < current.sample.time ||
      (sample.time === current.sample.time && sample.id <= current.sample.id))) return current;
  const c = structuredClone(current || {});
  const previous = c.sample;
  c.stages ||= Object.fromEntries(specs.map(s => [s.key, newStage(sample.time)]));
  c.models ||= baseline;
  c.runtimeHours = finite(c.runtimeHours); c.wallHours = finite(c.wallHours);
  const elapsed = previous ? (sample.time - previous.time) / 3600000 : 0;
  const before = previous?.sensors?.runtimeB, after = sample.sensors.runtimeB;
  let hours = 0;
  const countersValid = counter(before) && counter(after) && after >= before;
  if (countersValid) hours = (after - before) / 3600;
  const plausible = hours <= elapsed + 5 / 3600;
  if (!plausible) hours = 0;
  const measured = countersValid && plausible && elapsed > 0 && elapsed <= 3 / 60 && valid(sample.sensors);
  if (previous) { c.runtimeHours += hours; c.wallHours += Math.max(elapsed, 0); }
  const x = features(sample.sensors);
  for (const spec of specs) {
    const stage = c.stages[spec.key] ||= newStage(sample.time);
    // The interval crossing a service event has no measured counter at the service instant.
    // Re-anchor this stage instead of assigning pre-service runtime to the new media.
    if (previous && stage.serviceAt > previous.time) continue;
    if (previous && (!measured || !countersValid || !plausible)) stage.intervalComplete = false;
    stage.usage += hours * (measured ? load(c.models[spec.key].coefficients, x) : 1) / spec.ratedHours;
    stage.hours += hours;
    if (measured) { stage.measuredHours += hours; stage.sums = stage.sums.map((v, i) => v + hours * x[i + 1]); }
  }
  c.sample = sample;
  return c;
}

function maintain(current, request, time, id) {
  if (!current?.stages?.[request.stageKey]) return { ok: false, message: "Wait for shared sensor data" };
  if (current.applied?.[id]) return { ok: true, current, event: current.applied[id] };
  const spec = specs.find(s => s.key === request.stageKey), old = current.stages[request.stageKey];
  if (old.revision !== request.expectedRevision) return { ok: false, message: "Filter changed on another phone. Refresh and try again." };
  if (!["REPLACE", "RINSE"].includes(request.action) || (request.action === "RINSE" && spec.action !== "RINSE"))
    return { ok: false, message: "Invalid maintenance action" };
  const c = structuredClone(current);
  const event = { id, time, stageKey: spec.key, action: request.action, actor: request.actor,
    reason: request.reason, condition: request.condition, before: old, revision: old.revision + 1 };
  // Confirmed end-of-life is an observed label. Model-predicted wear alone is not a label.
  if (request.action === "REPLACE" && request.condition === "EXHAUSTED" && old.intervalComplete && old.rinses === 0 && old.hours > 0 && old.measuredHours >= .95 * old.hours) {
    event.observation = { stageKey: spec.key, features: [1, ...old.sums.map(v => v / old.measuredHours)],
      target: clamp(spec.ratedHours / old.hours, .25, 4), eventId: id, time };
  }
  c.stages[spec.key] = request.action === "REPLACE" ? { ...newStage(time, old.revision + 1), serviceAt: time }
    : { ...old, usage: old.usage * .3, rinses: old.rinses + 1, revision: old.revision + 1, serviceAt: time };
  c.applied ||= {}; c.applied[id] = event;
  return { ok: true, current: c, event };
}

function monthAt(time) { return new Date(time + 8 * 3600000).toISOString().slice(0, 7); }
function monthBounds(month) {
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) throw new Error("Invalid month");
  const [year, m] = month.split("-").map(Number);
  return { start: Date.UTC(year, m - 1, 1) - 8 * 3600000, end: Date.UTC(year, m, 1) - 8 * 3600000 };
}

function summarize(rows, month) {
  const { start, end } = monthBounds(month);
  const unique = [...new Map(rows.filter(r => r.time >= start && r.time < end).map(r => [r.id, r])).values()];
  // One deterministic record per source minute, independent of how many phones saw it.
  const minutes = new Map();
  for (const row of unique.filter(r => r.kind === "sample").sort((a, b) => a.time - b.time || a.id.localeCompare(b.id))) {
    const minute = Math.floor(row.time / 60000); if (!minutes.has(minute)) minutes.set(minute, row);
  }
  const samples = [...minutes.values()].sort((a, b) => a.time - b.time);
  const metrics = {};
  for (const key of ["ph", "tds", "turbidity", "tankLevel", "tankDistanceMm"]) {
    const values = samples.map(r => r.payload.sample.sensors[key]).filter(x => typeof x === "number" && Number.isFinite(x));
    metrics[key] = values.length ? { count: values.length, min: Math.min(...values), max: Math.max(...values), mean: values.reduce((a, b) => a + b, 0) / values.length } : null;
  }
  const runtime = { A: 0, B: 0 }; let gapMinutes = 0, anchor = start;
  samples.forEach((r, i) => {
    gapMinutes += Math.max(0, (r.time - anchor) / 60000 - 1); anchor = r.time;
    if (!i) return;
    const previous = samples[i - 1];
    for (const pump of ["A", "B"]) {
      const a = previous.payload.sample.sensors[`runtime${pump}`], b = r.payload.sample.sensors[`runtime${pump}`];
      if (counter(a) && counter(b) && b >= a && b - a <= (r.time - previous.time) / 1000 + 5) runtime[pump] += b - a;
    }
  });
  gapMinutes += Math.max(0, (end - anchor) / 60000 - (samples.length ? 1 : 0));
  const events = unique.filter(r => r.kind !== "sample");
  return { samples, events, metrics, runtimeSeconds: runtime, recordedMinutes: samples.length,
    coverage: samples.length / ((end - start) / 60000), gapMinutes,
    eventCounts: Object.fromEntries(["alert", "pump", "control", "command", "maintenance"].map(k => [k, events.filter(r => r.kind === k).length])) };
}

function forecast(rows, month, observations) {
  const summary = summarize(rows, month), { end } = monthBounds(month);
  const latest = summary.samples.at(-1);
  const models = Object.fromEntries(specs.map(s => [s.key, train(s, observations)]));
  const stages = {};
  for (const spec of specs) {
    const stage = latest?.payload.stages?.[spec.key];
    const first = summary.samples[0];
    const span = latest && first ? (latest.time - first.time) / 1000 : 0;
    const duty = span > 0 ? clamp(summary.runtimeSeconds.B / span, 0, 1) : 0;
    const sufficient = stage && valid(latest.payload.sample.sensors) && counter(latest.payload.sample.sensors.runtimeB)
      && summary.samples.length >= 60 && summary.coverage >= .1 && end - latest.time <= DAY && duty >= .001;
    const x = summary.metrics.ph && summary.metrics.tds && summary.metrics.turbidity
      ? features({ ph: summary.metrics.ph.mean, tds: summary.metrics.tds.mean, turbidity: summary.metrics.turbidity.mean }) : [1, 0, 0, 0];
    const factor = load(models[spec.key].coefficients, x);
    const hoursLeft = stage ? Math.max(0, 1 - stage.usage) * spec.ratedHours / factor : 0;
    const due = sufficient ? Math.min(latest.time + hoursLeft / (24 * duty) * DAY, stage.installedAt + spec.ratedDays * DAY) : null;
    stages[spec.key] = { revision: stage?.revision ?? -1, installedAt: stage?.installedAt ?? 0,
      dueDate: due === null ? null : new Date(due + 8 * 3600000).toISOString().slice(0, 10),
      action: stage && stage.installedAt + spec.ratedDays * DAY <= (due ?? end) ? "REPLACE" : spec.action,
      status: sufficient ? (models[spec.key].fittedOnRealData ? "FIELD_ADAPTED" : "BASELINE") : "INSUFFICIENT_DATA" };
  }
  return { modelVersion: VERSION, month, cutoff: end, stages, models,
    coverage: summary.coverage, recordedMinutes: summary.recordedMinutes, gapMinutes: summary.gapMinutes,
    metrics: summary.metrics, runtimeSeconds: summary.runtimeSeconds, eventCounts: summary.eventCounts };
}
module.exports = { VERSION, DAY, specs, features, valid, ridge, train, baseline, advance, maintain, monthAt, monthBounds, summarize, forecast };
