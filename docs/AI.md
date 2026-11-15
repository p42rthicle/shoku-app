# AI in Shoku

Shoku uses Claude to turn a description or a photo into log entries, read nutrition labels, list
recipe ingredients, suggest meal plans, and fill in household units ("tbsp", "slice") for foods. The
app can reach Claude in two ways. Both send the same prompts to the same models with the same
settings, and both read the answers the same way.

| | Direct mode | Backend mode |
|---|---|---|
| Used when | A Claude API key is saved in Settings | No key is saved and the build has a backend (`shoku.syncUrl` set) |
| Calls go | From the phone straight to `api.anthropic.com` | From the phone to your Catalyst function, which calls Anthropic |
| Who pays | The key's Anthropic account | The key stored on the server |
| Monthly cap and usage | Kept on the phone | Kept on the server |

A saved key always wins. With no key and no backend, AI features show "Add your Claude API key in
Settings" and everything else works as usual.

## Direct mode (public builds)

1. Create an API key at [platform.claude.com](https://platform.claude.com).
2. In Shoku, open **Settings → AI this month → Claude API key**, paste the key and tap **Save**.

The key stays on the phone. It is stored in the app's private storage, encrypted with a key held in
the Android Keystore, so a cloud backup of the app's data can't be used to read it. On a new phone,
enter the key again. **Remove** deletes it.

The app calls the Messages API through the official Anthropic Java SDK
(`com.anthropic:anthropic-java`):

- Text tasks (describe a meal, recipes, plans, units) use `claude-sonnet-5-5`. Plate photos use
  `claude-opus-5-5`.
- Every call asks for JSON that matches a schema (structured outputs), with `effort: low`, and turns
  on the server-side refusal fallback (`fallbacks: "default"`).
- The rules and your food library go in the system prompt, with the library cached, so repeat calls
  cost less.
- Household units are filled through the Message Batches API at half price. At most one new batch
  runs a day, and its answer is picked up on a later app start.

Every answer is priced with the same per-model table the backend uses. The monthly total and the cap
(default $30, set from $1 to $100 in Settings) are stored on the phone, and calls stop once the cap is
reached. The total is an estimate: check the Anthropic Console for what you are actually billed.

The code is in `android/app/src/main/java/app/shoku/data/ai/`:

- `AiTransport.kt` picks the mode for each call.
- `AiPrompts.kt` holds the prompts and schemas, copied from `ai.js`. A unit test checks they still
  match `ai.js` word for word.
- `DirectTransport.kt` handles the routes, cap and usage.
- `AnthropicSdkApi.kt` makes the SDK calls.
- `AiCost.kt` holds prices and the ledger.
- `AiSettings.kt` stores the key.

## Backend mode (self-hosting)

The backend is a Zoho Catalyst Advanced I/O function in `backend/functions/api/` (Node 22). It holds
your Anthropic key, runs the AI routes and a monthly cap, and also serves cloud sync. Sync is optional:
without a backend, the app keeps everything on the phone, and Settings shows "Sync: not set up".

1. Create a Catalyst project and install the Catalyst CLI (`npm i -g zcatalyst-cli`, `catalyst login`).
   Point `backend/.catalystrc` at your project, for example with `catalyst init` in `backend/`.
2. In the Data Store, create a table `SyncRecords` with these columns:
   - `record_key`: text, unique
   - `kind`: text
   - `record_id`: text
   - `updated_at`: bigint
   - `deleted`: text, holding `"true"` or `"false"`
   - `payload`: long text, up to 10,000 characters
   - `server_ts`: bigint

   AI spending is stored in the same table, under kinds the app ignores.
3. Create `backend/functions/api/secrets.json`. It is ignored by git, so it is never committed:

   ```json
   {
     "syncKey": "<a long random string the app will send>",
     "anthropicKey": "<your Anthropic API key>",
     "anthropicWorkspaceId": "<optional workspace id>"
   }
   ```

4. Deploy: `cd backend/functions/api && npm install`, then from `backend/` run `catalyst deploy`. Note
   the function's URL.
5. In `android/local.properties`, which is also not in git, add:

   ```properties
   shoku.syncUrl=https://<your-function-url>
   shoku.syncKey=<the same syncKey>
   ```

   Then build the app. With both values set, sync runs on open, on leave and every 12 hours. AI goes
   through the backend unless you also save a key in Settings.

Routes, all of which need the `X-Shoku-Key` header:

| Route | Purpose |
|---|---|
| `POST /ai/meal`, `/ai/label`, `/ai/plate`, `/ai/recipe`, `/ai/plan` | The AI tasks |
| `POST /ai/units/submit`, `GET /ai/units/result?id=` | The units batch |
| `GET /ai/usage`, `POST /ai/cap` | Spending and the monthly cap |
| `POST /sync/push`, `GET /sync/pull?since=`, `GET /sync/stats`, `GET /health` | Sync and health |

If you change a prompt, change it in `ai.js` and in `AiPrompts.kt`. The unit test catches the case
where only one of them changes.
