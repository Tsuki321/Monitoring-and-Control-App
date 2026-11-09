"use strict";
const { test, before, after, beforeEach } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const { resolve } = require("node:path");
const { initializeTestEnvironment, assertFails, assertSucceeds } = require("@firebase/rules-unit-testing");
const f = require("firebase/firestore");
const r = require("firebase/database");
if (!process.env.FIRESTORE_EMULATOR_HOST || !process.env.FIREBASE_DATABASE_EMULATOR_HOST) throw Error("Emulators required");
const month = "2020-01", end = Date.parse("2020-02-01T00:00:00+08:00");
const path = `systems/longos/months/${month}`;
let env, db, second;
before(async () => {
  env = await initializeTestEnvironment({ projectId: "demo-hydrosense",
    firestore: { rules: fs.readFileSync(resolve(__dirname, "../../firestore.rules"), "utf8") },
    database: { rules: fs.readFileSync(resolve(__dirname, "../../database.rules.json"), "utf8") } });
  db = env.authenticatedContext("phone-a").firestore();
  second = env.authenticatedContext("phone-b").firestore();
});
beforeEach(async () => { await env.clearFirestore(); await env.clearDatabase(); });
after(async () => env.cleanup());
async function upload(store = db, id = "batch", overrides = {}) {
  const owner = store === db ? "phone-a" : "phone-b";
  return f.runTransaction(store, async tx => {
    const ref = f.doc(store, path), batchRef = f.doc(store, `${path}/uploads/${id}`);
    const oldBatch = await tx.get(batchRef), old = await tx.get(ref);
    if (oldBatch.exists()) return;
    tx.set(batchRef, { owner, schemaVersion: 1, month, monthEnd: end, rows: "[]", count: 1,
      receivedAt: f.serverTimestamp(), ...overrides });
    tx.set(ref, { month, monthEnd: end, lastBatch: id, uploadRevision: (old.data()?.uploadRevision || 0) + 1, updatedAt: f.serverTimestamp() });
  });
}
async function acquire(store = db) {
  return f.runTransaction(store, async tx => {
    const ref = f.doc(store, `${path}/jobs/report`), current = await tx.get(ref);
    if ((current.data()?.expiresAt || 0) > Date.now()) return false;
    tx.set(ref, { owner: store === db ? "phone-a" : "phone-b", token: store === db ? "a" : "b",
      expiresAt: Date.now() + 900000, updatedAt: f.serverTimestamp() });
    return true;
  });
}
function result(token = "a", revision = 1) {
  return { month, cutoff: end, modelVersion: "ridge-monthly-spark-v1", uploadRevision: revision, jobToken: token,
    stages: { pumice: {}, pebbles: {}, lava_rock: {}, carbon: {}, multilayer_foam: {} },
    models: { pumice: {}, pebbles: {}, lava_rock: {}, carbon: {}, multilayer_foam: {} },
    metrics: {}, coverage: .2, inputRecordCount: 5000, inputDigest: "fixture", publishedAt: f.serverTimestamp() };
}
test("signed-out clients and other system paths cannot access archives", async () => {
  const guest = env.unauthenticatedContext().firestore();
  await assertFails(f.getDoc(f.doc(guest, path)));
  await assertFails(f.getDoc(f.doc(db, "systems/other/months/2020-01")));
  await assertFails(r.get(r.ref(env.unauthenticatedContext().database(), "systems/longos/current")));
});
test("upload and receipt revision commit atomically; retries do not increment revision", async () => {
  await assertSucceeds(upload());
  await assertSucceeds(upload(second));
  assert.equal((await f.getDoc(f.doc(db, path))).data().uploadRevision, 1);
  await assertSucceeds(upload(second, "second"));
  assert.equal((await f.getDoc(f.doc(db, path))).data().uploadRevision, 2);
  await assertFails(f.updateDoc(f.doc(db, `${path}/uploads/batch`), { rows: "changed" }));
  await assertFails(f.deleteDoc(f.doc(db, `${path}/uploads/batch`)));
  await assertFails(f.updateDoc(f.doc(db, path), { uploadRevision: 99, updatedAt: f.serverTimestamp() }));
});
test("future batches, forged identities and uploads without a receipt revision are rejected", async () => {
  await assertFails(upload(db, "future", { monthEnd: Date.now() + 86400000 }));
  await assertFails(upload(db, "spoof", { owner: "phone-b" }));
  await assertFails(f.setDoc(f.doc(db, `${path}/uploads/unpaired`),
    { owner: "phone-a", schemaVersion: 1, month, monthEnd: end, rows: "[]", count: 1, receivedAt: f.serverTimestamp() }));
});
test("two phones compete for one lease; an expired lease can be recovered", async () => {
  await upload();
  const acquired = await Promise.all([acquire(), acquire(second)]);
  assert.equal(acquired.filter(Boolean).length, 1);
  const winner = acquired[0] ? db : second, loser = acquired[0] ? second : db;
  await assertFails(f.setDoc(f.doc(loser, `${path}/jobs/report`), {
    owner: acquired[0] ? "phone-b" : "phone-a", token: "steal", expiresAt: Date.now() + 10000, updatedAt: f.serverTimestamp() }));
  await assertSucceeds(f.updateDoc(f.doc(winner, `${path}/jobs/report`), { expiresAt: 0, updatedAt: f.serverTimestamp() }));
  assert.equal(await acquire(loser), true);
});
test("official dates require a live lease and stay immutable after later uploads", async () => {
  await upload();
  const report = f.doc(db, `${path}/reports/official`);
  await assertFails(f.setDoc(report, result()));
  await acquire();
  await assertFails(f.setDoc(f.doc(second, `${path}/reports/official`), result()));
  await assertSucceeds(f.setDoc(report, result()));
  await upload(second, "late");
  await assertFails(f.setDoc(report, result("a", 2)));
  await assertFails(f.deleteDoc(report));
  await assertFails(f.setDoc(f.doc(db, `${path}/analytics/summary`), { ...result(), updatedAt: f.serverTimestamp() }));
  await assertSucceeds(f.setDoc(f.doc(db, `${path}/analytics/summary`), { ...result("a", 2), updatedAt: f.serverTimestamp() }));
  assert.equal((await f.getDoc(report)).data().uploadRevision, 1);
});
test("observations use physical stage keys and cannot be replaced or detached from an archive", async () => {
  await upload();
  const obs = { eventId: "event", stageKey: "pumice", time: end - 1000, month, batch: "batch",
    features: [1,.3,.1,0], target: 1 };
  await assertSucceeds(f.setDoc(f.doc(db, "systems/longos/observations/event"), obs));
  await assertFails(f.setDoc(f.doc(db, "systems/longos/observations/event"), { ...obs, target: 2 }));
  await assertFails(f.setDoc(f.doc(db, "systems/longos/observations/missing"), { ...obs, eventId: "missing", batch: "missing" }));
});
test("live telemetry and pump controls remain available; malformed shared state is rejected", async () => {
  const live = env.authenticatedContext("phone-a").database();
  for (const key of ["sensors", "control", "status"]) {
    await assertSucceeds(r.set(r.ref(live, key), { fixture: 1 }));
    await assertSucceeds(r.get(r.ref(live, key)));
  }
  await assertFails(r.set(r.ref(live, "systems/longos/current"), { fake: true }));
  const keys = ["pumice","pebbles","lava_rock","carbon","multilayer_foam"], time = Date.now();
  const current = { sample: { id: "sample", time, sensors: { ph: 7 } }, recorder: "session", recorderUntil: time + 150000,
    models: Object.fromEntries(keys.map(k => [k, { coefficients: [1,0,0,0] }])),
    stages: Object.fromEntries(keys.map(k => [k, { installedAt: time, revision: 0, usage: 0, hours: 0, serviceAt: time, intervalComplete: true }])) };
  await assertSucceeds(r.set(r.ref(live, "systems/longos/current"), current));
  await assertFails(r.set(r.ref(live, "systems/longos/current/stages/carbon/revision"), 3));
  await assertFails(r.remove(r.ref(live, "systems/longos/current")));
});
