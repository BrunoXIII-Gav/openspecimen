---
type: "query"
date: "2026-05-29T04:14:36.311870+00:00"
question: "specimenLabelFmt label format settings CP edit vue route label-settings"
contributor: "graphify"
source_nodes: ["LabelFormats.vue", "CpDetail.Settings.LabelFormats", "label-settings.js", "label-settings-addedit.js", "CollectionProtocol.js"]
---

# Q: specimenLabelFmt label format settings CP edit vue route label-settings

## Answer

The CP label format settings are edited via the LabelFormats.vue component at route CpDetail.Settings.LabelFormats (path: /cps/:cpId/detail/settings/label-formats, router/index.js:1387-1389). The component is at ui/src/biospecimen/cps/LabelFormats.vue. It has inline edit/view toggle (no separate addedit route): clicking Edit button sets editMode=true, showing an os-form with addEditLayout from label-settings-addedit.js schema. Fields: cp.specimenLabelFmt (primary specimens), cp.derivativeLabelFmt, cp.aliquotLabelFmt, plus misc settings (print settings, setQtyToZero, etc). The form also shows misc-settings.js fields in a separate section. On save: update() calls cpSvc.saveOrUpdate(cp) → PUT /rest/ng/collection-protocols/{id}. Emits 'cp-saved' event on success. Parent: CpDetail.Settings (path: settings, Settings.vue), which is a child of CpDetail (path: cps/:cpId/detail). Label schemas: label-settings.js (view), label-settings-addedit.js (edit layout), misc-settings.js (misc settings section). Expanded from original query via vocab: label format fmt setting edit route addedit schema collection protocol.

## Source Nodes

- LabelFormats.vue
- CpDetail.Settings.LabelFormats
- label-settings.js
- label-settings-addedit.js
- CollectionProtocol.js