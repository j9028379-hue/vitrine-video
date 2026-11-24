# D50 Data Binding Report

## Field mapping

| Existing mockup field | Collected source | T1 merchant-payload key | T3 key (preserved) |
|---|---|---|---|
| Business name (`q-nom`) | `input.value` | `nom` | `nom` |
| Product/service (`q-produit`) | `input.value` | `produit` | `produit` |
| Price (`q-prix`) | `input.value` | `prix` | `prix` |
| Tagline/message (`q-accroche`) | `input.value`, bridge historical key `demonstration` | `accroche` | `demonstration` |
| Neighborhood (`q-quartier`) | `input.value` | `quartier` | `quartier` |
| WhatsApp (`q-whatsapp`) | `input.value`; T1 whitespace normalization only | `whatsapp` | `whatsapp` |

Example tested T1 output: `nom=Étal Kandi`, `produit=Jus de gingembre`, `prix=750 FCFA`, `accroche=Frais et préparé ici`, `quartier=Centre-ville`, `whatsapp=+2290197000000`.

## Data path and validation

`android-bridge.js` → `VitrineAndroid.saveResponses()` → `MockupResponseMapper.parseFromWebJson(..., templateId)` → session response state/checkpoint → `buildVerifiedRenderInputs()` / `RealSessionGuard` → `TemplateJobAssembler` → schema-shaped `merchant_payload` → `VitrineRenderEngine.renderPackage()`.

The D50 bridge change removes browser `checkValidity()` as a gate on *saving the collected answers*; it does not waive browser navigation rules or package/native render validation. Required fields, patterns, lengths, capture ownership, session checks and preflight validation remain enforced. Saving answers allows the validator to report which value needs correction rather than seeing an empty native response map.

The Playwright regression test entered the values above, verified bridge output, then supplied a browser-invalid phone value and verified the available answers were still persisted. App unit coverage verified mapping and assembled `merchant_payload` against the actual embedded T1 package schema. No rendered MP4 was produced in this validation run.

## Commerce suggestion correction

The generic Commerce product placeholder that listed unrelated sectors was cleared in the dynamic T1/general Commerce branch. Actual merchant-entered product text remains the data source. Labels, screens and layout were not redesigned.