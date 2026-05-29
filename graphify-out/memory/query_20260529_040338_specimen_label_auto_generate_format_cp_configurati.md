---
type: "query"
date: "2026-05-29T04:03:38.625520+00:00"
question: "specimen label auto generate format CP configuration label format setting"
contributor: "graphify"
source_nodes: ["CollectionProtocol.java", "Specimen.java", "SpecimenRequirement.java", "label-settings.js", "Specimen.js"]
---

# Q: specimen label auto generate format CP configuration label format setting

## Answer

Specimen label auto-generation uses a 3-tier format resolution. Priority order: 1) SR-level labelFormat (SpecimenRequirement.labelFormat — per-requirement override); 2) CP-level format: cp.specimenLabelFormat (primary), cp.derivativeLabelFormat, cp.aliquotLabelFormat; 3) Workflow settings fallback: LabelSettingsUtil.getLabelFormat() checks 'labelSettings' workflow. CP label format fields are configured in label-settings-addedit.js schema (cp.specimenLabelFmt, cp.derivativeLabelFmt, cp.aliquotLabelFmt). Format strings use %TOKEN% syntax. Available tokens: %PPI% (PPID), %CP_CODE%, %SYS_UID% (global unique ID), %EVENT_LABEL%, %EVENT_CODE%, %EVENT_DATE%, %PSPEC_LABEL% (parent specimen label), %PR_SPEC_LABEL% (primary specimen label), %PSPEC_COUNTER% (child counter), %PRIMARY_SPEC_COUNTER%, %PPI_UID%, %CP_PPI_UID%, %PPI_YOC_UID%, %YR_OF_COLL%, %YR_OF_COLL2%, %TODAY_DATE%, %SP_TYPE%, %SP_TYPE_ABBR_UID%, %BARCODE%, %VISIT_NAME%, %VISIT_UID%, %SR_CODE%, %SPEC_CP_UID%, %PSPEC_UID%, %PSPEC_GREEK_SEQ%, %VISIT_GREEK_SEQ%, %REG_GREEK_SEQ%. Label generated in Specimen.setLabelIfEmpty() → getLabelTmpl() → LabelGenerator.generateLabel(). For aliquots without format, fallback is parentLabel + '_' + childCount. manualSpecLabelEnabled on CP allows user to override auto-generated label. Frontend: Specimen.js getLabelFormat() loads workflow labelSettings rules for conditional format per specimen type. Expanded from original query via vocab: specimen label auto generate format fmt pattern barcode configuration setting.

## Source Nodes

- CollectionProtocol.java
- Specimen.java
- SpecimenRequirement.java
- label-settings.js
- Specimen.js