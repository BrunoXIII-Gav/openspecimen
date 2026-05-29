---
type: "query"
date: "2026-05-29T04:25:02.298868+00:00"
question: "specimen label required validation field schema mandatory frontend"
contributor: "graphify"
source_nodes: ["specimen.js", "SpecimenServiceImpl.java", "Specimen.java"]
---

# Q: specimen label required validation field schema mandatory frontend

## Answer

Specimen label validation operates at two layers. FRONTEND (specimen.js schema L18-26): The label field has 'validations.required.messageCode: specimens.label_req' — always declared required. BUT visibility and interactivity are controlled by: showWhen: '!!specimen.label || cp.manualSpecLabelEnabled || !specimen.labelFmt' (shows if label already has value, OR manual entry enabled, OR no auto-format configured); disableWhen: '!cp.manualSpecLabelEnabled && !!specimen.labelFmt' (disabled if format exists and manual entry not allowed). So frontend only shows the required validation when the field is visible/editable. BACKEND validation (SpecimenServiceImpl.java L1116-1120): label is blank → required only if: cp.isManualSpecLabelEnabled() OR labelTmpl is blank, AND specimen.isCollected(). If both conditions fail (auto-format exists and manual disabled), blank label is NOT an error — it gets auto-filled by setLabelIfEmpty(). Error: SpecimenErrorCode.LABEL_REQUIRED. Additionally MANUAL_LABEL_NOT_ALLOWED error thrown if user provides a label but cp.manualSpecLabelEnabled=false and labelTmpl exists. Backend also throws LABEL_REQUIRED in setLabelIfEmpty() if auto-generation produces blank result. Expanded from original query via vocab: specimen label required validation field schema mandatory validate disable manual show error.

## Source Nodes

- specimen.js
- SpecimenServiceImpl.java
- Specimen.java