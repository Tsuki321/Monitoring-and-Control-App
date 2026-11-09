"use strict";
const { initializeApp } = require("firebase-admin/app");
const { getDatabase } = require("firebase-admin/database");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { onValueWritten, onValueCreated } = require("firebase-functions/v2/database");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { createHash } = require("node:crypto");
const model = require("./model");
initializeApp();
const REGION = "asia-southeast1", SYSTEM = "longos";
const db = getDatabase("https://database-for-hydrosense-default-rtdb.asia-southeast1.firebasedatabase.app");
const store = getFirestore();
const hash = value => createHash("sha256").update(value).digest("hex");
const root = () => db.ref(`systems/${SYSTEM}`);
const options = ref => ({ ref, region: REGION, retry: true });
function parse(value) { try { return typeof value === "string" ? JSON.parse(value) : value; } catch { return null; } }

exports.stampTelemetry = onValueWritten(options("/sensors"), async event => {
  const raw = parse(event.data.after.val()); if (!raw || typeof raw !== "object") return;
  const sensors = {};
  for (const key of ["ph", "tds", "turbidity", "tankDistanceMm", "tankLevel", "tankWarning", "rainDetected", "runtimeA", "runtimeB"]) {
    const value = raw[key];
    if (value !== undefined && value !== null) {
      const number = typeof value === "boolean" ? (value ? 1 : 0) : Number(value);
      if (Number.isFinite(number)) sensors[key] = number;
    }
  }
  const sample = { id: hash(event.id), time: Date.parse(event.time), sensors };
  await root().child("current").transaction(current => model.advance(current, sample));
});

for (const [name, path, target] of [["stampPumpStatus", "/status", "latestStatus"], ["stampControl", "/control", "latestControl"]]) {
  exports[name] = onValueWritten(options(path), async event => {
    const value = event.data.after.val(); if (value === null) return;
    const update = { id: hash(event.id), time: Date.parse(event.time), value };
    await root().child(target).transaction(current => !current || current.time < update.time ||
      (current.time === update.time && current.id < update.id) ? update : undefined);
  });
}

exports.applyMaintenance = onValueCreated(options(`/systems/${SYSTEM}/requests/{id}`), async event => {
  const request = event.data.val(), id = event.params.id, time = Date.parse(event.time);
  let outcome;
  await root().child("current").transaction(current => {
    outcome = model.maintain(current, request, time, id);
    return outcome.ok ? outcome.current : undefined;
  });
  const updates = { [`results/${id}`]: { ok: outcome.ok, message: outcome.message || "Maintenance saved", time } };
  if (outcome.ok) updates[`maintenance/${id}`] = outcome.event;
  await root().update(updates);
});

/** Validate and normalize uploaded batches. The source batch is retained for audit/recovery. */
exports.ingestMonthlyBatch = onDocumentCreated({ document: "systems/{system}/months/{month}/uploads/{batch}", region: REGION, retry: true }, async event => {
  if (event.params.system !== SYSTEM) return;
  const { month } = event.params;
  const input = event.data.data(), { start, end } = model.monthBounds(month);
  if (end > Date.now()) throw new Error("Current month upload rejected");
  let rows;
  try { rows = JSON.parse(input.rows); if (!Array.isArray(rows) || rows.length > 100) throw new Error("Invalid batch"); }
  catch (error) { await event.data.ref.update({ status: "rejected", error: error.message }); return; }
  const monthRef = store.doc(`systems/${SYSTEM}/months/${month}`);
  const normalized = [];
  for (const row of rows) {
    if (typeof row.id !== "string" || !Number.isSafeInteger(row.time) || row.time < start || row.time >= end ||
      !["sample", "alert", "pump", "control", "command", "maintenance"].includes(row.kind)) continue;
    const payload = parse(row.payload);
    if (!payload || typeof payload !== "object") continue;
    if (row.kind === "sample" && (!payload.sample || payload.sample.time !== row.time || typeof payload.sample.id !== "string" || !payload.sample.sensors)) continue;
    if (row.kind === "maintenance") {
      // Clients cannot manufacture training labels; only server-accepted maintenance is used.
      const accepted = (await root().child(`maintenance/${row.id.replace(/^maintenance-/, "")}`).get()).val();
      if (!accepted || accepted.time !== row.time) continue;
      normalized.push({ id: row.id, time: row.time, kind: row.kind, payload: accepted });
      continue;
    }
    normalized.push({ id: row.id, time: row.time, kind: row.kind, payload });
  }
  await store.runTransaction(async tx => {
    const batchState = await tx.get(event.data.ref);
    if (batchState.get("status") === "ingested") return;
    const refs = normalized.map(r => monthRef.collection("records").doc(hash(r.id)));
    const existing = refs.length ? await tx.getAll(...refs) : [];
    normalized.forEach((row, i) => { if (!existing[i].exists) tx.create(refs[i], { ...row, receivedAt: FieldValue.serverTimestamp() }); });
    for (const row of normalized) {
      const observation = row.kind === "maintenance" ? row.payload.observation : null;
      if (observation) tx.set(store.doc(`systems/${SYSTEM}/observations/${hash(row.id)}`), observation);
    }
    tx.set(monthRef, { month, dirty: true, lastIngestedAt: FieldValue.serverTimestamp() }, { merge: true });
    tx.update(event.data.ref, { status: "ingested", accepted: normalized.length, rejected: rows.length - normalized.length });
  });
});

/** Reads all pages; no silent query truncation. */
async function readCollection(query) {
  const rows = []; let last;
  do {
    let page = query.orderBy("__name__").limit(500); if (last) page = page.startAfter(last);
    const snapshot = await page.get();
    rows.push(...snapshot.docs.map(d => d.data()));
    last = snapshot.size === 500 ? snapshot.docs.at(-1) : null;
  } while (last);
  return rows;
}

exports.closeMonthlyReports = onSchedule({ schedule: "every 6 hours", timeZone: "Asia/Manila", region: REGION,
  timeoutSeconds: 540, memory: "1GiB", retryCount: 3 }, async () => {
  const currentMonth = model.monthAt(Date.now());
  const previousMonth = model.monthAt(model.monthBounds(currentMonth).start - 1);
  // An empty closed month must publish an explicit insufficient-data result too.
  const current = (await root().child("current").get()).val();
  if (current?.sample && Object.values(current.stages || {}).some(s => s.installedAt < model.monthBounds(currentMonth).start)) {
    await store.doc(`systems/${SYSTEM}/months/${previousMonth}`).set({ month: previousMonth }, { merge: true });
  }
  const months = await store.collection(`systems/${SYSTEM}/months`).get();
  for (const doc of months.docs.sort((a, b) => a.id.localeCompare(b.id))) {
    const month = doc.id, { end } = model.monthBounds(month);
    if (month >= currentMonth) continue;
    const state = doc.data();
    const rows = await readCollection(doc.ref.collection("records"));
    if (state.dirty) {
      const summary = model.summarize(rows, month);
      await doc.ref.collection("analytics").doc("summary").set({ metrics: summary.metrics, coverage: summary.coverage,
        runtimeSeconds: summary.runtimeSeconds, eventCounts: summary.eventCounts, recordedMinutes: summary.recordedMinutes,
        gapMinutes: summary.gapMinutes, updatedAt: FieldValue.serverTimestamp() });
      // Only clear the dirty flag if no batch arrived during processing.
      await store.runTransaction(async tx => {
        const now = await tx.get(doc.ref);
        if (now.get("lastIngestedAt")?.isEqual(state.lastIngestedAt)) tx.update(doc.ref, { dirty: false });
      });
    }
    const reportRef = doc.ref.collection("reports").doc("official");
    let report = (await reportRef.get()).data();
    // 24-hour collection window after month-end; late data updates history, not the published forecast.
    if (!report && Date.now() >= end + model.DAY) {
      const observations = await readCollection(store.collection(`systems/${SYSTEM}/observations`));
      const training = observations.filter(o => o.time < end).sort((a, b) => a.eventId.localeCompare(b.eventId));
      report = { ...model.forecast(rows, month, training), publishedAt: Date.now(),
        inputRecordCount: rows.length, inputDigest: hash(JSON.stringify(rows)),
        observationIds: training.map(o => o.eventId), observationDigest: hash(JSON.stringify(training)) };
      await store.runTransaction(async tx => { if (!(await tx.get(reportRef)).exists) tx.create(reportRef, report); });
      report = (await reportRef.get()).data();
    }
    if (report) {
      // Repair mirrors after a crash; never let an older month's retry replace a newer report.
      await root().child("official").transaction(old => !old || old.month < report.month ? report : undefined);
      await root().child("current").transaction(old => {
        if (!old || (old.modelMonth && old.modelMonth >= report.month)) return;
        return { ...old, models: report.models, modelMonth: report.month };
      });
    }
  }
});
