"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const m = require("../model");
const start = Date.parse("2026-10-01T00:00:00+08:00");
const sample = (id, time, runtimeB, extra = {}) => ({ id, time, sensors: { ph: 7, tds: 300, turbidity: 50, runtimeA: runtimeB, runtimeB, ...extra } });

test("prior fits are finite and field adaptation starts at eight observed outcomes", () => {
  for (const spec of m.specs) {
    const baseline = m.train(spec);
    assert.equal(baseline.syntheticSamples, 400);
    assert.ok(baseline.coefficients.every(Number.isFinite));
    assert.equal(baseline.fittedOnRealData, false);
    const rows = Array.from({ length: 8 }, (_, i) => ({ stageKey: spec.key, features: [1, .2 + i * .01, .05, .1], target: 3 }));
    assert.equal(m.train(spec, rows.slice(0, 7)).fittedOnRealData, false);
    assert.equal(m.train(spec, rows).fittedOnRealData, true);
    assert.notDeepEqual(m.train(spec, rows).coefficients, baseline.coefficients);
  }
});
test("duplicate and out-of-order source events never accrue twice", () => {
  let state = m.advance(null, sample("a", start, 900));
  state = m.advance(state, sample("b", start + 60000, 960));
  assert.equal(state.runtimeHours, 1 / 60);
  assert.deepEqual(m.advance(state, sample("b", start + 60000, 960)), state);
  assert.deepEqual(m.advance(state, sample("a", start, 900)), state);
});
test("counter resets and impossible jumps do not invent pump runtime", () => {
  const state = m.advance(null, sample("a", start, 900));
  assert.equal(m.advance(state, sample("b", start + 60000, 10)).runtimeHours, 0);
  assert.equal(m.advance(state, sample("b", start + 60000, 100000)).runtimeHours, 0);
});
test("gaps recover runtime but exclude the interval from field training", () => {
  const state = m.advance(m.advance(null, sample("a", start, 0)), sample("b", start + 3600000, 3600));
  assert.equal(state.runtimeHours, 1);
  assert.equal(state.stages.carbon.measuredHours, 0);
  assert.equal(state.stages.carbon.intervalComplete, false);
  assert.equal(state.stages.carbon.usage, 1 / 350);
});
test("maintenance uses revisions and retries do not replace a filter twice", () => {
  const state = m.advance(null, sample("a", start, 0));
  const request = { stageKey: "carbon", action: "REPLACE", expectedRevision: 0, reason: "service", condition: "EXHAUSTED", actor: "user" };
  const result = m.maintain(state, request, start + 500, "event1");
  assert.equal(result.ok, true);
  assert.equal(result.current.stages.carbon.revision, 1);
  assert.deepEqual(m.maintain(result.current, request, start + 500, "event1").current, result.current);
  assert.equal(m.maintain(result.current, request, start + 900, "event2").ok, false);
});
test("only a confirmed exhaustion with complete observations produces a training target", () => {
  let state = m.advance(null, sample("a", start, 0));
  state = m.advance(state, sample("b", start + 60000, 60));
  const request = { stageKey: "carbon", action: "REPLACE", expectedRevision: 0, reason: "service", condition: "EXHAUSTED", actor: "user" };
  assert.ok(m.maintain(state, request, start + 70000, "a").event.observation);
  assert.equal(m.maintain(state, { ...request, condition: "PREVENTIVE" }, start + 70000, "b").event.observation, undefined);
});
test("multi-phone overlapping records count each source minute once", () => {
  const payload = s => ({ sample: s, stages: {} });
  const rows = [
    { id: "a", kind: "sample", time: start, payload: payload(sample("a", start, 0)) },
    { id: "b", kind: "sample", time: start + 1000, payload: payload(sample("b", start + 1000, 1)) },
    { id: "c", kind: "sample", time: start + 60000, payload: payload(sample("c", start + 60000, 60)) },
  ];
  const summary = m.summarize([...rows, ...rows], "2026-10");
  assert.equal(summary.recordedMinutes, 2);
  assert.equal(summary.runtimeSeconds.B, 60);
  assert.ok(summary.gapMinutes > 40000);
});
test("Manila month bounds include leap days and reject invalid month keys", () => {
  const feb = m.monthBounds("2028-02");
  assert.equal((feb.end - feb.start) / m.DAY, 29);
  assert.equal(m.monthAt(feb.end - 1), "2028-02");
  assert.equal(m.monthAt(feb.end), "2028-03");
  assert.throws(() => m.monthBounds("2026-13"));
});
test("no data produces no fabricated date", () => {
  const report = m.forecast([], "2026-10", []);
  for (const stage of Object.values(report.stages)) {
    assert.equal(stage.status, "INSUFFICIENT_DATA");
    assert.equal(stage.dueDate, null);
  }
});
test("maintenance after the last sample cannot publish the replaced filter's forecast", () => {
  const state = m.advance(null, sample("a", start, 0));
  const result = m.maintain(state, { stageKey: "carbon", action: "REPLACE", expectedRevision: 0,
    reason: "new cartridge", condition: "PREVENTIVE", actor: "user" }, start + 30000, "service");
  const report = m.forecast([
    { id: "a", kind: "sample", time: start, payload: { sample: state.sample, stages: state.stages } },
    { id: "service", kind: "maintenance", time: start + 30000, payload: result.event }
  ], "2026-10", []);
  assert.equal(report.stages.carbon.installedAt, start + 30000);
  assert.equal(report.stages.carbon.dueDate, null);
});
test("forecast dates are stable for fixed archived input and change after replacement", () => {
  const rows = []; let state;
  const begin = m.monthBounds("2026-10").end - 5 * m.DAY;
  for (let i = 0; i < 7200; i++) {
    const s = sample(String(i).padStart(6, "0"), begin + i * 60000, i * 30);
    state = m.advance(state, s);
    rows.push({ id: s.id, time: s.time, kind: "sample", payload: { sample: s, stages: structuredClone(state.stages) } });
  }
  const a = m.forecast(rows, "2026-10", []), b = m.forecast(rows, "2026-10", []);
  assert.deepEqual(a, b);
  assert.match(a.stages.carbon.dueDate, /^\d{4}-\d{2}-\d{2}$/);
  assert.equal(a.stages.carbon.status, "BASELINE");
});
