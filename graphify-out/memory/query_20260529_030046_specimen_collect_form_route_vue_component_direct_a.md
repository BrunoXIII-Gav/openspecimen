---
type: "query"
date: "2026-05-29T03:00:46.528277+00:00"
question: "specimen collect form route vue component direct API REST create specimen visit"
contributor: "graphify"
source_nodes: ["OccurredVisits.vue", "Workflow.js", "Specimen.js", "AddEdit.vue", "SpecimenAddEdit"]
---

# Q: specimen collect form route vue component direct API REST create specimen visit

## Answer

Two paths exist for creating/collecting specimens for a visit. 1) WORKFLOW PATH (default): OccurredVisits.vue / Visits.vue / visits/Overview.vue all call wfSvc.addSpecimen(cp, visit) → biospecimen/services/Workflow.js:addSpecimen() → _wfInstanceSvc().createInstance({name: wfName}) where wfName is sys-collect-adhoc-specimens (or CP-configured override). This routes through window.osSvc.tmWfInstanceSvc (workflow module). 2) DIRECT REST PATH: The SpecimenAddEdit route (path: addedit) loads specimens/AddEdit.vue. On save, it calls specimenSvc.saveOrUpdate() → Specimen.js:saveOrUpdate() → http.post('specimens/') or http.put('specimens/{id}') → maps to REST endpoint POST/PUT /rest/ng/specimens/. Navigation to SpecimenAddEdit for editing uses specimenId from route params; Overview.vue also navigates to it via routerSvc.goto('SpecimenAddEdit', {cpId, cprId, visitId, specimenId: id}). Expanded from original query via vocab: specimen collect collection form route vue component api rest create visit biospecimen.

## Source Nodes

- OccurredVisits.vue
- Workflow.js
- Specimen.js
- AddEdit.vue
- SpecimenAddEdit