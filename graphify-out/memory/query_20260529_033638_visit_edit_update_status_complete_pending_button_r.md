---
type: "query"
date: "2026-05-29T03:36:38.095578+00:00"
question: "visit edit update status complete pending button route vue"
contributor: "graphify"
source_nodes: ["visits/AddEdit.vue", "visits/Overview.vue", "Visits.vue", "OccurredVisits.vue", "Visit.js", "VisitAddEdit"]
---

# Q: visit edit update status complete pending button route vue

## Answer

Visit status edit flows through two layers: 1) EDIT FORM (VisitAddEdit route): visits/Overview.vue:129 has an Edit button (v-if=isUpdateAllowed) that calls editVisit() → routerSvc.goto('VisitAddEdit', ...). The VisitAddEdit route (router/index.js:1659) loads visits/AddEdit.vue. New visits default to status='Complete' (AddEdit.vue:58). The form uses schema visit.js which defines status options: Complete, Pending, Missed Collection. On save, saveOrUpdate() calls visitSvc.saveOrUpdate() → http.put('visits/{id}') or http.post('visits/'). 2) STATUS-DEPENDENT BUTTONS in Visits.vue and OccurredVisits.vue: Visits.vue shows collect-pending-specimens button when status == Pending or Complete; add-specimen and print-labels when status == Complete; repeat-visit when status != Pending. The 'collect pending' button when status==Pending calls wfSvc.collectVisitSpecimens(visit) which uses workflow sys-collect-visits (sets status to Complete as part of the workflow). Expanded from original query via vocab: visit edit update status complete pending button route vue addedit missed occurred.

## Source Nodes

- visits/AddEdit.vue
- visits/Overview.vue
- Visits.vue
- OccurredVisits.vue
- Visit.js
- VisitAddEdit