---
type: "query"
date: "2026-05-29T03:51:51.279004+00:00"
question: "specimen requirement REST endpoint API URL controller getSpecimenRequirement"
contributor: "graphify"
source_nodes: ["SpecimenRequirementsController.java", "CollectionProtocol.js", "specimens/DetailRoot.vue"]
---

# Q: specimen requirement REST endpoint API URL controller getSpecimenRequirement

## Answer

Specimen requirement REST API is handled by SpecimenRequirementsController.java (WEB-INF/.../rest/controller/SpecimenRequirementsController.java) with base mapping @RequestMapping('specimen-requirements'). Full URL base: /rest/ng/specimen-requirements. Endpoints: GET /specimen-requirements?cpId=X&eventId=Y&includeChildReqs=true (getRequirements) → cpSvc.getSpecimenRequirments(); GET /specimen-requirements/{id} (getRequirement) → cpSvc.getSpecimenRequirement(id); POST /specimen-requirements (addRequirement); PUT /specimen-requirements/{id} (updateRequirement); DELETE /specimen-requirements/{id}; POST /specimen-requirements/{id}/aliquots; POST /specimen-requirements/{id}/derived; POST /specimen-requirements/{id}/copy; GET /specimen-requirements/{id}/specimens-count. Frontend calls in CollectionProtocol.js: getSpecimenRequirement(reqId) → http.get('specimen-requirements/' + reqId). This is what specimens/DetailRoot.vue calls via cpSvc.getSpecimenRequirement(reqId) when reqId>0. Backend service: CollectionProtocolService (injected as cpSvc). Expanded from original query via vocab: specimen requirement rest endpoint controller get resource collection protocol service.

## Source Nodes

- SpecimenRequirementsController.java
- CollectionProtocol.js
- specimens/DetailRoot.vue