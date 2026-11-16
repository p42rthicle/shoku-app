'use strict';

/*
 * Shoku backend (Catalyst Advanced I/O function).
 *
 * Sync model: every app row (food, dish, log entry, ...) is stored as one record in the
 * SyncRecords table, keyed by "<kind>:<id>", with the client's updatedAt for last-write-wins
 * and a server_ts that is unique and increasing, used as the pull cursor.
 *
 *   POST /sync/push   { records: [{ kind, id, updatedAt, deleted, payload }] }   (max 200)
 *   GET  /sync/pull?since=<server_ts>                                           (300 per page)
 *   GET  /sync/stats
 *   GET  /health
 *   POST /ai/meal | /ai/label | /ai/product | /ai/plate | /ai/recipe   (see ai.js)
 *   POST /ai/plan                                           (week plan; no app screen uses it yet)
 *   POST /ai/units/submit, GET /ai/units/result?id=      (Batch API, half price)
 *   GET  /ai/usage, POST /ai/cap { usd }                  (monthly spending cap, default $30)
 *
 * Every request must carry the shared secret in the X-Shoku-Key header. The secret lives in
 * secrets.json next to this file (not in git) and is deployed with the function.
 */

const catalyst = require('zcatalyst-sdk-node');
const ai = require('./ai');

const TABLE = 'SyncRecords';
const PAGE = 300; // ZCQL row limit per query
const LOOKUP_CHUNK = 50; // keys per IN (...) lookup
const WRITE_CHUNK = 200; // rows per insertRows/updateRows call

let secrets = {};
try {
	secrets = require('./secrets.json');
} catch (e) {
	secrets = {};
}
ai.setWorkspace(secrets.anthropicWorkspaceId);

function send(res, status, body) {
	res.writeHead(status, { 'Content-Type': 'application/json' });
	res.end(JSON.stringify(body));
}

function readBody(req) {
	return new Promise((resolve, reject) => {
		if (req.body && typeof req.body === 'object') return resolve(req.body);
		let data = '';
		req.on('data', (c) => { data += c; });
		req.on('end', () => {
			try { resolve(data ? JSON.parse(data) : {}); } catch (e) { reject(new Error('Body is not valid JSON')); }
		});
		req.on('error', reject);
	});
}

const quote = (s) => "'" + String(s).replace(/'/g, "''") + "'";
const chunks = (arr, n) => Array.from({ length: Math.ceil(arr.length / n) }, (_, i) => arr.slice(i * n, i * n + n));
const unwrap = (rows) => (rows || []).map((r) => r[TABLE]).filter(Boolean);

/** Server timestamps in microseconds plus an index, so every written row gets a distinct cursor value. */
let lastTs = 0;
function nextTs() {
	lastTs = Math.max(lastTs + 1, Date.now() * 1000);
	return lastTs;
}

async function push(app, records) {
	if (!Array.isArray(records)) throw new Error('records must be an array');
	if (records.length > WRITE_CHUNK) throw new Error(`at most ${WRITE_CHUNK} records per push`);
	const zcql = app.zcql();
	const table = app.datastore().table(TABLE);

	const incoming = new Map();
	for (const r of records) {
		if (!r || !r.kind || !r.id || typeof r.payload !== 'string') throw new Error('each record needs kind, id and payload');
		if (r.payload.length > 10000) throw new Error(`payload too large for ${r.kind}:${r.id}`);
		incoming.set(`${r.kind}:${r.id}`, r);
	}

	// Find which keys already exist.
	const existing = new Map();
	for (const keys of chunks([...incoming.keys()], LOOKUP_CHUNK)) {
		const rows = unwrap(await zcql.executeZCQLQuery(
			`SELECT ROWID, record_key, updated_at FROM ${TABLE} WHERE record_key IN (${keys.map(quote).join(',')})`,
		));
		for (const row of rows) existing.set(row.record_key, row);
	}

	const inserts = [];
	const updates = [];
	let skipped = 0;
	for (const [key, r] of incoming) {
		const fields = {
			record_key: key,
			kind: r.kind,
			record_id: r.id,
			updated_at: Number(r.updatedAt) || 0,
			deleted: r.deleted ? 'true' : 'false',
			payload: r.payload,
		};
		const old = existing.get(key);
		if (!old) {
			inserts.push({ ...fields, server_ts: nextTs() });
		} else if (Number(old.updated_at) < fields.updated_at) {
			updates.push({ ROWID: old.ROWID, ...fields, server_ts: nextTs() });
		} else {
			skipped++; // the server copy is as new or newer: last write wins
		}
	}

	for (const part of chunks(inserts, WRITE_CHUNK)) await table.insertRows(part);
	for (const part of chunks(updates, WRITE_CHUNK)) await table.updateRows(part);
	return { inserted: inserts.length, updated: updates.length, skipped };
}

async function pull(app, since) {
	const rows = unwrap(await app.zcql().executeZCQLQuery(
		`SELECT kind, record_id, updated_at, deleted, payload, server_ts FROM ${TABLE} WHERE server_ts > ${Number(since) || 0} ORDER BY server_ts ASC LIMIT 0, ${PAGE}`,
	));
	const records = rows.map((r) => ({
		kind: r.kind,
		id: r.record_id,
		updatedAt: Number(r.updated_at),
		deleted: String(r.deleted) === 'true',
		payload: r.payload,
		serverTs: Number(r.server_ts),
	}));
	const next = records.length ? records[records.length - 1].serverTs : Number(since) || 0;
	return { records, next, more: records.length === PAGE };
}

// ---------- AI spending ----------
// Kept as SyncRecords rows of kinds the app ignores: "aiusage:<YYYY-MM>", "aisettings:cap", "aibatch:<id>".

const DEFAULT_CAP_USD = 30;
const month = () => new Date().toISOString().slice(0, 7);

async function readRecord(app, key) {
	const rows = unwrap(await app.zcql().executeZCQLQuery(`SELECT ROWID, payload FROM ${TABLE} WHERE record_key = ${quote(key)}`));
	if (!rows.length) return null;
	let value = {};
	try { value = JSON.parse(rows[0].payload); } catch (e) { value = {}; }
	return { rowId: rows[0].ROWID, value };
}

async function writeRecord(app, kind, id, value, existing) {
	const fields = { record_key: `${kind}:${id}`, kind, record_id: id, updated_at: Date.now(), deleted: 'false', payload: JSON.stringify(value), server_ts: nextTs() };
	const table = app.datastore().table(TABLE);
	if (existing) await table.updateRow({ ROWID: existing.rowId, ...fields });
	else await table.insertRow(fields);
}

async function usage(app) {
	const [u, cap] = await Promise.all([readRecord(app, `aiusage:${month()}`), readRecord(app, 'aisettings:cap')]);
	return {
		month: month(),
		usd: Number(u?.value.usd) || 0,
		calls: Number(u?.value.calls) || 0,
		capUsd: Number(cap?.value.usd) || DEFAULT_CAP_USD,
		record: u,
	};
}

async function addSpend(app, usd) {
	const now = await usage(app);
	await writeRecord(app, 'aiusage', now.month, { usd: now.usd + (Number(usd) || 0), calls: now.calls + 1 }, now.record);
}

/** Refuses AI work once this month's spend reaches the cap. */
async function checkCap(app) {
	const u = await usage(app);
	if (u.usd >= u.capUsd) {
		throw new ai.HttpError(402, `This month's AI limit of $${u.capUsd} is used up ($${u.usd.toFixed(2)}). Raise it in Settings or wait for next month.`);
	}
}

async function stats(app) {
	const rows = await app.zcql().executeZCQLQuery(`SELECT kind, COUNT(ROWID) FROM ${TABLE} GROUP BY kind`);
	const out = {};
	for (const r of rows) {
		const v = r[TABLE] || {};
		out[v.kind] = Number(v['COUNT(ROWID)'] ?? Object.values(v).find((x) => /^\d+$/.test(String(x))) ?? 0);
	}
	return out;
}

module.exports = async (req, res) => {
	try {
		const url = new URL(req.url, 'https://local');
		const path = url.pathname.replace(/\/+$/, '') || '/';

		if (!secrets.syncKey || req.headers['x-shoku-key'] !== secrets.syncKey) {
			return send(res, 401, { error: 'unauthorized' });
		}
		const app = catalyst.initialize(req, { scope: 'admin' });

		if (req.method === 'GET' && path === '/health') return send(res, 200, { ok: true, time: Date.now(), ai: Boolean(secrets.anthropicKey) });
		const aiRoutes = { '/ai/meal': ai.parseMeal, '/ai/label': ai.readLabel, '/ai/product': ai.readProduct, '/ai/plate': ai.plate, '/ai/recipe': ai.recipe, '/ai/plan': ai.planWeek };
		if (req.method === 'POST' && aiRoutes[path]) {
			const body = await readBody(req);
			await checkCap(app);
			const result = await aiRoutes[path](secrets.anthropicKey, body);
			await addSpend(app, result.usage?.usd).catch((e) => console.error('spend not recorded', e));
			return send(res, 200, result);
		}
		if (req.method === 'POST' && path === '/ai/units/submit') {
			const body = await readBody(req);
			await checkCap(app);
			return send(res, 200, await ai.submitUnits(secrets.anthropicKey, body));
		}
		if (req.method === 'GET' && path === '/ai/units/result') {
			const id = url.searchParams.get('id');
			const result = await ai.unitsResult(secrets.anthropicKey, id);
			// A finished batch's cost is counted once.
			if (result.status === 'ended' && !(await readRecord(app, `aibatch:${id}`))) {
				await writeRecord(app, 'aibatch', id, { usd: result.usd }, null);
				await addSpend(app, result.usd);
			}
			return send(res, 200, result);
		}
		if (req.method === 'GET' && path === '/ai/usage') {
			const { month: m, usd, calls, capUsd } = await usage(app);
			return send(res, 200, { month: m, usd, calls, capUsd });
		}
		if (req.method === 'POST' && path === '/ai/cap') {
			const body = await readBody(req);
			const usd = Number(body.usd);
			if (!(usd >= 1 && usd <= 100)) throw new ai.HttpError(400, 'cap must be between $1 and $100');
			await writeRecord(app, 'aisettings', 'cap', { usd }, await readRecord(app, 'aisettings:cap'));
			return send(res, 200, { capUsd: usd });
		}
		if (req.method === 'POST' && path === '/sync/push') {
			const body = await readBody(req);
			return send(res, 200, await push(app, body.records));
		}
		if (req.method === 'GET' && path === '/sync/pull') return send(res, 200, await pull(app, url.searchParams.get('since')));
		if (req.method === 'GET' && path === '/sync/stats') return send(res, 200, await stats(app));
		return send(res, 404, { error: `no route for ${req.method} ${path}` });
	} catch (err) {
		console.error(err);
		return send(res, err.status && err.status >= 400 ? err.status : 500, { error: err.message || String(err) });
	}
};
