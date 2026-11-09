"use strict";
const { test, before, after } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const { resolve } = require("node:path");
const { initializeTestEnvironment, assertFails, assertSucceeds } = require("@firebase/rules-unit-testing");
const clientFS = require("firebase/firestore");
const clientDB = require("firebase/database");
const model = require("../model");
const PROJECT = "demo-hydrosense";
if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_DATABASE_EMULATOR_HOST) throw new Error("Emulators required; never run against production");
process.env.GCLOUD_PROJECT = PROJECT;
process.env.HYDROSENSE_DATABASE_URL = `https://${PROJECT}-default-rtdb.firebaseio.com`;
process.env.FIREBASE_CONFIG = JSON.stringify({ projectId: PROJECT, databaseURL: process.env.HYDROSENSE_DATABASE_URL });
const functions = require("../index");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getDatabase } = require("firebase-admin/database");
const { deleteApp, getApps } = require("firebase-admin/app");
const database = getDatabase(process.env.HYDROSENSE_DATABASE_URL), store = getFirestore();
let env;
const month = "2020-01", { start, end } = model.monthBounds(month);
const sensor = (id, time, seconds) => ({ id, time, sensors: { ph: 7, tds: 300, turbidity: 50, runtimeA: seconds, runtimeB: seconds } });
const initial = model.advance(null, sensor("source", start, 0));
const row = { id: "sample-source", time: start, kind: "sample", payload: JSON.stringify({ sample: initial.sample, stages: initial.stages }) };
async function batch(id, rows, overrides = {}) {
  const ref = store.doc(`systems/longos/months/${month}/uploads/${id}`);
  await ref.set({ owner: "member", schemaVersion: 1, month, monthEnd: end, rows: JSON.stringify(rows), count: rows.length,
    receivedAt: FieldValue.serverTimestamp(), ...overrides });
  await functions.ingestMonthlyBatch.run({ params: { system: "longos", month, batch: id }, data: await ref.get() });
  return ref;
}
before(async () => {
  env = await initializeTestEnvironment({ projectId: PROJECT,
    firestore: { rules: fs.readFileSync(resolve(__dirname, "../../firestore.rules"), "utf8") },
    database: { rules: JSON.stringify({ rules: JSON.parse(fs.readFileSync(resolve(__dirname, "../../database.rules.fragment.json"), "utf8")) }) } });
  await store.doc("systems/longos/members/member").set({ enabled: true });
  await database.ref("systemMembers/longos/member").set(true);
});
after(async () => { await env.cleanup(); await Promise.all(getApps().map(deleteApp)); });

test("rules deny outsiders and client forecast/state writes", async () => {
  const outsider = env.authenticatedContext("outsider"), member = env.authenticatedContext("member");
  await assertFails(clientFS.getDoc(clientFS.doc(outsider.firestore(), "systems/longos/months/2020-01/reports/official")));
  await assertFails(clientFS.setDoc(clientFS.doc(member.firestore(), "systems/longos/months/2020-01/reports/official"), { fake: true }));
  await assertFails(clientDB.set(clientDB.ref(member.database(), "systems/longos/current"), { fake: true }));
  await assertFails(clientDB.get(clientDB.ref(outsider.database(), "systems/longos/current")));
});
test("member can upload a closed batch, but cannot rewrite it or impersonate another uploader", async () => {
  const db = env.authenticatedContext("member").firestore();
  const data = { owner: "member", schemaVersion: 1, month, monthEnd: end, rows: JSON.stringify([row]), count: 1, receivedAt: clientFS.serverTimestamp() };
  await assertSucceeds(clientFS.setDoc(clientFS.doc(db, `systems/longos/months/${month}/uploads/client`), data));
  await assertFails(clientFS.setDoc(clientFS.doc(db, `systems/longos/months/${month}/uploads/client`), data));
  await assertFails(clientFS.setDoc(clientFS.doc(db, `systems/longos/months/${month}/uploads/spoof`), { ...data, owner: "outsider" }));
  await assertFails(clientFS.setDoc(clientFS.doc(db, `systems/longos/months/${month}/uploads/future`), { ...data, monthEnd: Date.now() + model.DAY }));
});
test("duplicate rows and function retries ingest once", async () => {
  const ref = await batch("duplicates", [row, row]);
  await functions.ingestMonthlyBatch.run({ params: { system: "longos", month, batch: "duplicates" }, data: await ref.get() });
  const records = await store.collection(`systems/longos/months/${month}/records`).get();
  assert.equal(records.size, 1);
  assert.equal((await ref.get()).get("status"), "ingested");
  assert.equal((await ref.get()).get("rejected"), 0);
});
test("malformed batch is rejected without losing the source upload", async () => {
  const ref = await batch("invalid", [], { rows: "not json" });
  assert.equal((await ref.get()).get("status"), "rejected");
  const nulls = await batch("nulls", [null, { id: "bad", kind: "sample", time: start, payload: "{}" }]);
  assert.equal((await nulls.get()).get("rejected"), 2);
});
test("server maintenance transactions reject stale revisions and survive retry", async () => {
  await database.ref("systems/longos/current").set(initial);
  const req = { actor: "member", stageKey: "carbon", action: "REPLACE", expectedRevision: 0, reason: "new cartridge", condition: "PREVENTIVE", requestedAt: start + 1000 };
  const client = env.authenticatedContext("member").database();
  await assertSucceeds(clientDB.set(clientDB.ref(client, "systems/longos/requests/request1"), req));
  const event = { params: { id: "request1" }, time: new Date(start + 1000).toISOString(), data: await database.ref("systems/longos/requests/request1").get() };
  await functions.applyMaintenance.run(event); await functions.applyMaintenance.run(event);
  assert.equal((await database.ref("systems/longos/current/stages/carbon/revision").get()).val(), 1);
  await database.ref("systems/longos/requests/request2").set(req);
  await functions.applyMaintenance.run({ ...event, params: { id: "request2" }, data: await database.ref("systems/longos/requests/request2").get() });
  assert.equal((await database.ref("systems/longos/results/request2/ok").get()).val(), false);
});
test("late uploads update historical analytics without rewriting the official report", async () => {
  await functions.closeMonthlyReports.run({ scheduleTime: new Date().toISOString() });
  const ref = store.doc(`systems/longos/months/${month}/reports/official`);
  const original = (await ref.get()).data(); assert.ok(original);
  const later = model.advance(initial, sensor("later", start + 60000, 60));
  await batch("late", [{ id: "sample-later", time: start + 60000, kind: "sample", payload: JSON.stringify({ sample: later.sample, stages: later.stages }) }]);
  await functions.closeMonthlyReports.run({ scheduleTime: new Date().toISOString() });
  assert.deepEqual((await ref.get()).data(), original);
  const summary = (await store.doc(`systems/longos/months/${month}/analytics/summary`).get()).data();
  assert.equal(summary.recordedMinutes, 2);
});
