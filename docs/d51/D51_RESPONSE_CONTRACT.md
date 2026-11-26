# D51 Response Contract — form → Android → RenderJob

## Contract

For each merchant field, the value is an opaque UTF-8 string from the input. Transport may JSON-escape characters for encoding, but the decoded string delivered to the engine is preserved. No semantic, phone, commercial-truth, minimum-content-length, maximum-content-length, or regex validation is run on merchant answers.

| Web input / key | T1 job key | T3 job key | D51 behavior |
|---|---|---|---|
| `q-nom` / `nom` | `nom` | `nom` | exact entered string |
| `q-produit` / `produit` | `produit` | `produit` | exact entered string |
| `q-prix` / `prix` | `prix` | `prix` | exact entered string |
| `q-accroche` / bridge compatibility key `demonstration` | `accroche` | `demonstration` | alias only; value unchanged |
| `q-quartier` / `quartier` | `quartier` | `quartier` | exact entered string |
| `q-whatsapp` / `whatsapp` | `whatsapp` | `whatsapp` | arbitrary text; no telephone parsing or whitespace removal |
| Any future schema-aligned answer key | same key | same key | pass-through; package schema controls allowed/required keys |

## Presence versus content

- `form/questions.json` and the render-job `required` list determine whether a response is mandatory.
- A missing/null/empty or whitespace-only value for a required field raises `MISSING_REQUIRED_RESPONSE`. For a non-empty required string, formatting is not judged.
- Optional fields may be empty.
- A malformed non-string JSON value is rejected as `RESPONSE_VALUE_NOT_TEXT`; unknown keys are rejected when the package declares `additionalProperties: false`. These are transport/schema-integrity checks, not checks of the answer's meaning.
- No merchant string is changed to pass validation. Returned response maps and assembled job values retain the exact original string, including accents, punctuation, spaces and length.

## Legacy schema handling

Old packages may carry string `minLength`, `maxLength`, and `pattern` metadata for merchant answers. The universal parser deliberately does not compile or load those response-content constraints; required keys, string transport type, allowed-key structure, and capture integrity remain. D51's bundled T1 form/job JSON was also updated to remove these obsolete content rules so a standards-based schema consumer will not reject the free-text payload. T3 package JSON remains unchanged; the app applies the same no-content-format-validation policy to its strings.

## Render boundary

`TemplateJobAssembler` emits the typed string map under the package-selected payload key (`merchant_payload` for T1). `PreFlightValidator` validates trusted session/media/render prerequisites, not the semantics or format of commercial answers. The universal engine then uses the supplied response map against the template's JSON text bindings and presentation recipe. Layout/wrapping remains a presentation concern; it does not modify the stored/submitted RenderJob answer.