---
type: "query"
date: "2026-05-29T03:49:23.571852+00:00"
question: "specimens DetailRoot specimenId -1 new specimen blank prefill cpId cprId visitId"
contributor: "graphify"
source_nodes: ["specimens/DetailRoot.vue", "specimens/AddEdit.vue"]
---

# Q: specimens DetailRoot specimenId -1 new specimen blank prefill cpId cprId visitId

## Answer

specimens/DetailRoot.vue behavior for specimenId=-1 (new specimen): 1) created() has two branches: specimenId>0 calls _loadSpecimen(), reqId>0 calls _loadRequirement(). For specimenId=-1 with no reqId, NEITHER branch runs. loaded stays false. 2) Template v-if='viewKey && loaded': viewKey returns 'unknown' (truthy) but loaded=false, so the router-view never mounts. AddEdit.vue never renders for specimenId=-1 without reqId. 3) PREFILL only happens via _toSpecimen(req) when reqId>0: sets specimen.cpId=cpr.cpId, specimen.cprId=cpr.id, specimen.visitId=visit.id, specimen.eventId, specimen.visitName, specimen.status='Pending', plus requirement fields (type, specimenClass, anatomicSite, etc). 4) For truly ad-hoc new specimens (specimenId=-1, no reqId), the non-workflow path is non-functional - the form never renders. This path is only reached as a fallback when window.osSvc.tmWfInstanceSvc is absent. With the workflow module present, addSpecimen() calls createInstance() and navigates to the workflow instance, bypassing DetailRoot entirely. 5) ParticipantsListItemSpecimenRoot (router/index.js:1600) does pass reqId via route.query.reqId; SpecimenRoot (line 1672) does not. Expanded from original query via vocab: specimen detail root load create cpid cpr visit event req new init.

## Source Nodes

- specimens/DetailRoot.vue
- specimens/AddEdit.vue