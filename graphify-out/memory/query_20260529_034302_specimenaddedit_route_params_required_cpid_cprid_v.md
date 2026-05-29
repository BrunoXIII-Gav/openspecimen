---
type: "query"
date: "2026-05-29T03:43:02.350903+00:00"
question: "SpecimenAddEdit route params required cpId cprId visitId specimenId props"
contributor: "graphify"
source_nodes: ["SpecimenAddEdit", "specimens/AddEdit.vue", "specimens/DetailRoot.vue", "router/index.js"]
---

# Q: SpecimenAddEdit route params required cpId cprId visitId specimenId props

## Answer

SpecimenAddEdit requires 4 URL path params (all mandatory): cpId, cprId, visitId, specimenId. Full URL path is /cp-view/:cpId/participants/:cprId/visit/:visitId/specimen/:specimenId/addedit. All callers use routerSvc.goto('SpecimenAddEdit', {cpId, cprId, visitId, specimenId}). For new specimens specimenId=-1; for existing specimens specimenId=actual ID. Optional query param: eventId. Component props are injected via provide/inject chain: AddEdit.vue declares props=['cpr','visit','specimen']; specimen provided by specimens/DetailRoot.vue; visit by visits/DetailRoot.vue; cpr by participants/DetailRoot.vue. cpId is not a direct prop — it is accessed through cpr.cpId or via cpViewCtx (injected from CpView.vue). Expanded from original query via vocab: specimen route params required cpid cpr visit props param path resolver addedit.

## Source Nodes

- SpecimenAddEdit
- specimens/AddEdit.vue
- specimens/DetailRoot.vue
- router/index.js