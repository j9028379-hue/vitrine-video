# D51 Free-Text Policy

## Founder rule
**The merchant supplies the content. The template JSON defines its presentation. The universal engine executes.** The response transport layer is not a truth checker, copywriter, phone-number validator, or content moderator.

## Accepted merchant content
All string fields defined by a template may contain the merchant's chosen wording. This includes short or long answers, numbers, accents, punctuation, symbols, embedded spaces and descriptive contact text. WhatsApp values such as `07 01 02 03 04`, `+229 01 70 12 34 56`, `Contact WhatsApp`, `Appelez-nous au 97`, `Disponible tous les jours`, `Mon contact : 123 ABC`, and `Bienvenue chez nous !` are all text answers and must be passed without phone-format validation.

## The only response checks
1. **Presence:** if the selected package declares a field required, an answer must be provided. An actually absent/blank required value is distinct from any non-empty, unusual value.
2. **Transport type:** form answers are strings. Non-string/corrupt JSON values are rejected instead of coerced into misleading text.
3. **Schema structure:** disallowed keys and malformed packages remain rejected. The package controls the payload key and allowed/required field identifiers.
4. **Security and media integrity:** safe ZIP paths, manifest hashes, session ownership, capture hashes, media readability, storage, and codec/preflight checks remain in force.

## Explicitly prohibited for merchant answers
Do not enforce international prefixes, phone-number patterns, digit counts, arbitrary content lengths, numeric-only pricing, commercial truth, writing quality, or a guessed meaning. Do not trim, remove internal whitespace, add prefixes, auto-correct, paraphrase, replace with placeholders, or substitute defaults during collection, mapping, validation, or RenderJob assembly.

## Template and future-template policy
The generic response mapper passes schema-aligned keys and string values unchanged. T1/T3 retain the single legacy UI alias for their shared `q-accroche` control. New templates should name the form control/key to match their JSON contract; the generic layer then forwards them without adding new template-specific response validators. JSON's scene/timing/text/font/position/animation/audio/encoding definitions remain the rendering authority.

## Presentation is separate
A renderer may wrap text to fit a JSON-defined text window. That visual layout step must not mutate the response stored in the RenderJob or become a hidden content acceptance rule. No montage/timeline changes are part of D51.