'use strict';

/*
 * AI routes for Shoku. The app sends a compact copy of its food library (short keys, names, units)
 * and the model answers in a fixed JSON shape (structured outputs), matching to library items where
 * it can and giving per-100 g estimates where it can't. Nothing here writes to the database.
 */

const AnthropicModule = require('@anthropic-ai/sdk');
const Anthropic = AnthropicModule.default || AnthropicModule;

// Photos need the strongest vision for portion sizes; text tasks are fine on Sonnet at half the price.
const MODEL = 'claude-opus-5-5';
const TEXT_MODEL = 'claude-sonnet-5-5';

/** USD per million tokens. Batch requests cost half. */
const PRICES = {
	'claude-opus-5-5': { input: 4, output: 20, cacheRead: 0.2, cacheWrite: 5 },
	'claude-sonnet-5-5': { input: 2, output: 10, cacheRead: 0.1, cacheWrite: 2.5 },
	'claude-haiku-5-5': { input: 0.1, output: 0.5, cacheRead: 0.01, cacheWrite: 0.125 },
};

/** Cost of one response in USD, from its usage block. Unknown models are priced as Opus to stay on the safe side. */
function costOf(model, u, batch = false) {
	const key = Object.keys(PRICES).find((m) => String(model || '').startsWith(m)) || MODEL;
	const p = PRICES[key];
	const usd = ((u.input_tokens || 0) * p.input + (u.output_tokens || 0) * p.output
		+ (u.cache_read_input_tokens || 0) * p.cacheRead + (u.cache_creation_input_tokens || 0) * p.cacheWrite) / 1e6;
	return batch ? usd / 2 : usd;
}

class HttpError extends Error {
	constructor(status, message) { super(message); this.status = status; }
}

let cachedClient = null;
let workspaceId = null;
/** Personal API keys aren't tied to a workspace, so each request names one (from secrets.json). */
function setWorkspace(id) { workspaceId = id || null; cachedClient = null; }
function client(apiKey) {
	if (!apiKey) throw new HttpError(503, 'AI is not set up yet: the server has no API key.');
	if (!cachedClient) {
		cachedClient = new Anthropic({
			apiKey,
			timeout: 25_000,
			maxRetries: 1,
			defaultHeaders: workspaceId ? { 'anthropic-workspace-id': workspaceId } : undefined,
		});
	}
	return cachedClient;
}

// ---------- Shared pieces ----------

const nullable = (schema) => ({ anyOf: [schema, { type: 'null' }] });
const num = { type: 'number' };

const ESTIMATE = {
	type: 'object',
	additionalProperties: false,
	required: ['kcal_per_100g', 'protein_per_100g', 'fibre_per_100g', 'carbs_per_100g', 'fat_per_100g', 'grams'],
	properties: {
		kcal_per_100g: num, protein_per_100g: num, fibre_per_100g: num, carbs_per_100g: num, fat_per_100g: num,
		grams: { type: 'number', description: 'Estimated grams eaten for this item' },
	},
};

const ITEM = {
	type: 'object',
	additionalProperties: false,
	required: ['said', 'match_key', 'name', 'unit', 'qty', 'confidence', 'estimate'],
	properties: {
		said: { type: 'string', description: 'The words this item came from' },
		match_key: nullable({ type: 'string', description: 'Key of the library item, e.g. "f12"' }),
		name: { type: 'string', description: 'Library name when matched, otherwise a short clear English name' },
		unit: { type: 'string', description: 'One of the matched item\'s units, or "g" when not matched' },
		qty: num,
		confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
		estimate: nullable(ESTIMATE),
	},
};

const MEAL_RESULT = {
	type: 'object',
	additionalProperties: false,
	required: ['items', 'meal'],
	properties: {
		items: { type: 'array', items: ITEM },
		meal: nullable({ type: 'string', enum: ['Breakfast', 'Lunch', 'Snacks', 'Dinner'] }),
	},
};

const LABEL_RESULT = {
	type: 'object',
	additionalProperties: false,
	required: ['readable', 'name', 'brand', 'unit', 'kcal', 'protein', 'fibre', 'carbs', 'fat', 'serving_size', 'note'],
	properties: {
		readable: { type: 'boolean', description: 'False if no nutrition table can be read' },
		name: nullable({ type: 'string' }),
		brand: nullable({ type: 'string' }),
		unit: { type: 'string', enum: ['g', 'ml'], description: 'Basis of the per-100 column' },
		kcal: nullable(num), protein: nullable(num), fibre: nullable(num), carbs: nullable(num), fat: nullable(num),
		serving_size: nullable({ type: 'number', description: 'Grams or ml in one serving, if printed' }),
		note: nullable({ type: 'string', description: 'Anything uncertain, briefly' }),
	},
};

const RECIPE_RESULT = {
	type: 'object',
	additionalProperties: false,
	required: ['name', 'servings', 'ingredients'],
	properties: {
		name: nullable({ type: 'string' }),
		servings: nullable(num),
		ingredients: { type: 'array', items: ITEM },
	},
};

const RULES = `You help one person log what they eat. They live in Germany, buy from Aldi/Lidl/Rewe, and cook a lot of North Indian home food.

Matching to their library:
- Each library line is: key | name | units | also known as. Match an item only when it is really the same food. A generic ("any brand") item is the right match when they don't name a brand or name a brand that isn't in the library.
- The unit must be one of that item's listed units. Prefer a named portion (medium, slice, tin, tbsp, bowl) when they describe one; use g or ml when they give a weight or volume.
- If nothing in the library fits, set match_key to null, use unit "g", and fill estimate with typical values per 100 g plus the grams eaten. Think of realistic home portions (a katori of dal is about 150 g, a roti about 40 g, a cup of cooked rice about 160 g).
- Never add items they didn't mention. Split combined descriptions into separate items ("2 eggs and toast with butter" is three items).
- confidence: high when the item and amount are clear, medium when one is guessed, low when both are.`;

function libraryText(lib) {
	if (!Array.isArray(lib) || lib.length === 0) return 'The library is empty.';
	return lib.slice(0, 600)
		.map((f) => [f.k, f.name, (f.units || []).join(', '), f.aka || ''].join(' | '))
		.join('\n');
}

/** One structured call. The library goes in a cached system block so repeated calls are cheap. */
async function ask(apiKey, { task, library, content, schema, maxTokens = 4000, model = TEXT_MODEL }) {
	// eslint-disable-next-line no-param-reassign
	// Stable parts first (rules, then the library) so every route shares one cached prefix;
	// the route's task goes in the user turn.
	const system = [{ type: 'text', text: RULES }];
	if (library) {
		system.push({ type: 'text', text: `Their food library:\n${libraryText(library)}`, cache_control: { type: 'ephemeral' } });
	}
	content = [{ type: 'text', text: task }, ...content];
	let res;
	try {
		res = await client(apiKey).beta.messages.create({
			model,
			max_tokens: maxTokens,
			// If the main model declines, the API retries on a fallback model inside the same call.
			betas: ['server-side-fallback-2026-07-01'],
			fallbacks: 'default',
			output_config: { effort: 'low', format: { type: 'json_schema', schema } },
			system,
			messages: [{ role: 'user', content }],
		});
	} catch (err) {
		if (err instanceof Anthropic.AuthenticationError) throw new HttpError(503, 'The server\'s AI key was rejected.');
		if (err instanceof Anthropic.RateLimitError) throw new HttpError(429, 'AI is busy or out of credit, try again in a minute.');
		if (err instanceof Anthropic.APIConnectionTimeoutError) throw new HttpError(504, 'AI took too long, try again.');
		if (err instanceof Anthropic.APIError) throw new HttpError(502, `AI error ${err.status ?? ''}: ${err.message}`);
		throw err;
	}
	if (res.stop_reason === 'refusal') throw new HttpError(422, 'The AI declined this one.');
	if (res.stop_reason === 'max_tokens') throw new HttpError(502, 'The AI answer was cut off.');
	const text = res.content.filter((b) => b.type === 'text').map((b) => b.text).join('');
	let data;
	try { data = JSON.parse(text); } catch (e) { throw new HttpError(502, 'The AI answer was not valid JSON.'); }
	const u = res.usage || {};
	return {
		data,
		usage: { input: u.input_tokens, output: u.output_tokens, cacheRead: u.cache_read_input_tokens, cacheWrite: u.cache_creation_input_tokens, model: res.model, usd: costOf(res.model, u) },
	};
}

function imageBlock(data, mediaType) {
	if (!data || typeof data !== 'string') throw new HttpError(400, 'image (base64) is required');
	if (data.length > 6_000_000) throw new HttpError(413, 'image too large; send a JPEG of about 1600 px');
	const type = ['image/jpeg', 'image/png', 'image/webp'].includes(mediaType) ? mediaType : 'image/jpeg';
	return { type: 'image', source: { type: 'base64', media_type: type, data } };
}

function image(body) { return imageBlock(body.image, body.mediaType); }

/** One or more photos of the same meal: body.images (array, up to 4) or body.image. */
function images(body) {
	const list = Array.isArray(body.images) && body.images.length ? body.images : [body.image];
	if (list.length > 4) throw new HttpError(400, 'at most 4 photos');
	return list.map((d) => imageBlock(d, body.mediaType));
}

// ---------- Routes ----------

async function parseMeal(apiKey, body) {
	if (!body.text || typeof body.text !== 'string') throw new HttpError(400, 'text is required');
	return ask(apiKey, {
		task: 'Task: turn their description of what they ate into items. If the description says which meal it was, set meal; otherwise null.',
		library: body.library,
		content: [{ type: 'text', text: body.text.slice(0, 2000) }],
		schema: MEAL_RESULT,
	});
}

async function readLabel(apiKey, body) {
	return ask(apiKey, {
		task: 'Task: read the nutrition table on this food package. Use the per 100 g / 100 ml column (not per serving). Energy in kcal (convert from kJ only if kcal is missing). Name and brand from the package if visible. German labels: Brennwert = energy, Eiweiß = protein, Ballaststoffe = fibre, Kohlenhydrate = carbs, Fett = fat. If the table is not readable, set readable false.',
		content: [image(body), { type: 'text', text: 'Read this label.' }],
		schema: LABEL_RESULT,
		maxTokens: 2000,
	});
}

async function plate(apiKey, body) {
	const photos = images(body);
	const extra = typeof body.text === 'string' && body.text.trim() ? `\nThey add: ${body.text.slice(0, 500)}` : '';
	const angles = photos.length > 1
		? ` There are ${photos.length} photos of the same meal from different angles: use them together to judge depth and portion size, and count each food once.`
		: '';
	return ask(apiKey, {
		task: 'Task: estimate what is on this plate or in this bowl. List each visible food with a realistic amount; use the plate, cutlery, glasses and bowls for scale. Match to their library when it is clearly the same dish (e.g. their own dal or rice batch). Confidence is at most medium for anything judged by eye.' + angles + extra,
		library: body.library,
		content: [...photos, { type: 'text', text: 'What is here and how much?' }],
		schema: MEAL_RESULT,
		model: MODEL,
	});
}

async function recipe(apiKey, body) {
	if (!body.text || typeof body.text !== 'string') throw new HttpError(400, 'text is required');
	return ask(apiKey, {
		task: 'Task: they describe what went into a pot for one cook (a batch). Return every ingredient with its amount for the whole pot, matched to their library where possible. Spices under 5 g can be skipped unless named with an amount. If they say how many servings or the dish name, fill those.',
		library: body.library,
		content: [{ type: 'text', text: body.text.slice(0, 3000) }],
		schema: RECIPE_RESULT,
	});
}

// ---------- Household units, filled in once through the Batch API (half price, not urgent) ----------

const UNITS_RESULT = {
	type: 'object',
	additionalProperties: false,
	required: ['foods'],
	properties: {
		foods: {
			type: 'array',
			items: {
				type: 'object',
				additionalProperties: false,
				required: ['k', 'grams_per_unit', 'portions'],
				properties: {
					k: { type: 'string' },
					grams_per_unit: nullable({ type: 'number', description: 'Only for foods counted in pieces, servings, cans or packs: grams (ml for drinks) in one. Otherwise null.' }),
					portions: {
						type: 'array',
						items: {
							type: 'object',
							additionalProperties: false,
							required: ['label', 'amount'],
							properties: {
								label: { type: 'string', description: 'Short lowercase household unit, e.g. tbsp, tsp, cup, glass, slice, katori, bowl, scoop, bar, piece, small, medium, large' },
								amount: { type: 'number', description: 'How much of the food\'s own unit (g, ml, pc, ...) one of this portion is' },
							},
						},
					},
				},
			},
		},
	},
};

const UNITS_TASK = `Task: for each food, list the household units people actually use for it, with how much of the food's own unit one of them is. Use realistic values for that kind of food (a tbsp of Nutella is about 19 g, a tbsp of oil about 13 g, a slice of toast about 25-30 g, a glass of milk about 250 ml, a katori of dal about 150 g, a scoop of whey about 30 g).
- 1 to 4 portions per food, only ones that make sense (no "cup" for chocolate bars, no "slice" for milk). Never repeat the food's own unit.
- For foods measured in pieces, servings, cans or packs, set grams_per_unit to the weight of one (ml for drinks) and usually give no portions. Otherwise grams_per_unit is null.
- Keep labels short, lowercase, English.
Each line is: key | name | own unit | brand.`;

const UNITS_CHUNK = 40;

/** Starts a batch: one request per 40 foods. Returns the batch id to poll later. */
async function submitUnits(apiKey, body) {
	const foods = Array.isArray(body.foods) ? body.foods.slice(0, 2000) : [];
	if (!foods.length) throw new HttpError(400, 'foods is required');
	const requests = [];
	for (let i = 0; i < foods.length; i += UNITS_CHUNK) {
		const part = foods.slice(i, i + UNITS_CHUNK).map((f) => [f.k, f.name, f.unit, f.brand || ''].join(' | ')).join('\n');
		requests.push({
			custom_id: `u${i / UNITS_CHUNK}`,
			params: {
				model: TEXT_MODEL,
				max_tokens: 8000,
				output_config: { effort: 'low', format: { type: 'json_schema', schema: UNITS_RESULT } },
				system: [{ type: 'text', text: UNITS_TASK }],
				messages: [{ role: 'user', content: part }],
			},
		});
	}
	try {
		const batch = await client(apiKey).messages.batches.create({ requests });
		return { batchId: batch.id, requests: requests.length };
	} catch (err) {
		if (err instanceof Anthropic.APIError) throw new HttpError(502, `AI error ${err.status ?? ''}: ${err.message}`);
		throw err;
	}
}

/** A units batch: { status } while running, then { status: 'ended', foods, failed, usd }. */
async function unitsResult(apiKey, batchId) {
	if (!batchId || !/^[A-Za-z0-9_-]+$/.test(batchId)) throw new HttpError(400, 'id is required');
	const c = client(apiKey);
	const batch = await c.messages.batches.retrieve(batchId);
	if (batch.processing_status !== 'ended') return { status: batch.processing_status };
	const foods = [];
	let usd = 0;
	let failed = 0;
	for await (const r of await c.messages.batches.results(batchId)) {
		if (r.result.type !== 'succeeded') { failed++; continue; }
		const m = r.result.message;
		usd += costOf(m.model, m.usage || {}, true);
		try {
			const text = m.content.filter((b) => b.type === 'text').map((b) => b.text).join('');
			foods.push(...JSON.parse(text).foods);
		} catch (e) { failed++; }
	}
	return { status: 'ended', foods, failed, usd };
}

// ---------- Weekly plan (not used by the app's screens yet) ----------

const PLAN_RESULT = {
	type: 'object',
	additionalProperties: false,
	required: ['templates', 'notes'],
	properties: {
		templates: {
			type: 'array',
			items: {
				type: 'object',
				additionalProperties: false,
				required: ['name', 'meal', 'weekdays', 'items'],
				properties: {
					name: { type: 'string' },
					meal: { type: 'string', enum: ['Breakfast', 'Lunch', 'Snacks', 'Dinner'] },
					weekdays: { type: 'array', items: { type: 'integer', description: '1 = Monday ... 7 = Sunday' } },
					items: {
						type: 'array',
						items: {
							type: 'object',
							additionalProperties: false,
							required: ['match_key', 'unit', 'qty'],
							properties: {
								match_key: { type: 'string', description: 'Library key, or "slot" for the sabzi/dal batch in the fridge' },
								unit: { type: 'string' },
								qty: num,
							},
						},
					},
				},
			},
		},
		notes: { type: 'string', description: 'Two or three short sentences: what changed and why' },
	},
};

const PLAN_TASK = `Task: plan their week as meal templates, built only from their library (match_key from the library; "slot" means one serving of whatever sabzi or dal batch they cooked).
Principles, in order: 1) every meal is built around a protein source from their usual ones; 2) colour and fibre: vegetables, curd, nuts and seeds; 3) supplements cover the rest, so don't plan foods to replace them. Protein powder (whey) is a food in their plan, not a supplement: keep it.
Hit the daily calorie and protein goals within about 5%. Keep it repetitive (same breakfast and lunch most days); they don't want variety for its own sake. Prefer what is in stock and foods that would otherwise run out unused. Respect the rules they give (for example days without eggs or chicken). Units must be one of the item's listed units. Every weekday 1-7 must have one template per meal they eat.`;

const MEAL_TASK = (meal) => `Task: suggest exactly 3 different options for their ${meal}, as templates built only from their library (match_key from the library; "slot" means one serving of whatever sabzi or dal batch they cooked). Leave weekdays empty.
Principles, in order: 1) built around a protein source they actually eat; 2) colour and fibre: vegetables, curd, nuts and seeds; 3) supplements cover the rest. Protein powder (whey) is a food, not a supplement.
Use what they eat often (their history) and what is at home, especially things that would run out unused. Keep each option realistic for them, close to their usual portions, and sized for this meal's share of the day (roughly a quarter to a third of the calorie goal for lunch or dinner, less for breakfast or snacks). Make the 3 options genuinely different from each other and from their current ${meal} templates. Units must be one of the item's listed units. Name each in a few plain words ("Paneer bhurji, roti, salad").`;

/** body: { goals: {kcal, protein}, rules: [string], library: [...], stock: [string], current: [string], history?: [string], meal?: string, text?: string } */
async function planWeek(apiKey, body) {
	const g = body.goals || {};
	const meal = ['Breakfast', 'Lunch', 'Snacks', 'Dinner'].includes(body.meal) ? body.meal : null;
	const history = (Array.isArray(body.history) ? body.history : []).slice(0, 60).join('; ');
	const lines = [
		`Goals per day: ${Number(g.kcal) || 1800} kcal, ${Number(g.protein) || 110} g protein.`,
		`Rules: ${(Array.isArray(body.rules) ? body.rules : []).join('; ') || 'none'}.`,
		`At home now: ${(Array.isArray(body.stock) ? body.stock : []).slice(0, 200).join('; ') || 'unknown'}.`,
		`Current plan: ${(Array.isArray(body.current) ? body.current : []).slice(0, 40).join(' | ') || 'none'}.`,
		history ? `What they eat most (last months): ${history}.` : '',
		typeof body.text === 'string' && body.text.trim() ? `They ask: ${body.text.slice(0, 1000)}` : '',
	].filter(Boolean).join('\n');
	return ask(apiKey, {
		task: meal ? MEAL_TASK(meal) : PLAN_TASK,
		library: body.library,
		content: [{ type: 'text', text: lines }],
		schema: PLAN_RESULT,
		maxTokens: 12000,
	});
}

module.exports = { planWeek, parseMeal, readLabel, plate, recipe, submitUnits, unitsResult, setWorkspace, HttpError, MODEL, costOf };
