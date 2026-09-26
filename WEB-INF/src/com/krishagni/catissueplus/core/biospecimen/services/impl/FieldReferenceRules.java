package com.krishagni.catissueplus.core.biospecimen.services.impl;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

import org.apache.commons.lang3.StringUtils;

import com.krishagni.catissueplus.core.biospecimen.domain.Participant;
import com.krishagni.catissueplus.core.biospecimen.domain.Specimen;
import com.krishagni.catissueplus.core.biospecimen.domain.Visit;
import com.krishagni.catissueplus.core.common.errors.CommonErrorCode;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.common.events.RequestEvent;
import com.krishagni.catissueplus.core.de.services.FormService;

import edu.common.dynamicextensions.domain.nui.Container;
import edu.common.dynamicextensions.domain.nui.Control;
import edu.common.dynamicextensions.domain.nui.SubFormControl;
import krishagni.catissueplus.beans.FormContextBean;

/** The fields a reference may read are an allowlist, never an arbitrary bean path. */
public final class FieldReferenceRules {
	public static final String FORM_START = "__form_start__";

	public static final Set<String> CPR_FIELDS = Set.of(
		"ppid", "registrationDate", "site", "externalSubjectId", "participant.firstName",
		"participant.middleName", "participant.lastName", "participant.emailAddress",
		"participant.phoneNumber", "participant.birthDate", "participant.gender",
		"participant.empi", "participant.uid", "participant.vitalStatus", "participant.deathDate");

	public static final Set<String> VISIT_FIELDS = Set.of(
		"name", "status", "visitDate", "site", "clinicalDiagnoses", "clinicalStatus",
		"cohort", "surgicalPathologyNumber", "comments");

	public static final Set<String> SPECIMEN_FIELDS = Set.of(
		"label", "barcode", "additionalLabel", "lineage", "status", "type", "specimenClass",
		"initialQty", "availableQty", "concentration", "parentLabel", "collectionEvent.time",
		"receivedEvent.time", "pathologicalStatus", "tissueSite", "comments");

	private FieldReferenceRules() { }

	public static boolean isReferenceableControlType(String type) {
		return type != null && !Set.of("fileUpload", "signature", "subForm", "label", "pageBreak")
			.contains(type);
	}

	public static void validate(Long cpId, Map<String, Object> data,
		com.krishagni.catissueplus.core.de.repository.DaoFactory deDaoFactory, FormService formSvc) {
		if (data == null || !(data.get("rules") instanceof List<?> rules) || rules.size() > 200) {
			invalid("rules");
		}
		if (data.get("version") != null && !(data.get("version") instanceof Number n && n.intValue() == 1)) {
			invalid("version");
		}
		data.put("version", 1);
		Set<String> ids = new HashSet<>();

		for (Object item : (List<?>) data.get("rules")) {
			if (!(item instanceof Map<?, ?>)) invalid("rule");
			Map<?, ?> rule = (Map<?, ?>) item;
			String id = str(rule.get("id"));
			if (id.isEmpty()) {
				id = UUID.randomUUID().toString();
				set(rule, "id", id);
			} else {
				try { UUID.fromString(id); } catch (IllegalArgumentException e) { invalid("id"); }
			}
			if (!ids.add(id)) invalid("id");
			String target = str(rule.get("target"));
			String source = str(rule.get("source"));
			String field = str(rule.get("field"));
			String caption = str(rule.get("caption"));
			String group = str(rule.get("group"));
			String afterField = str(rule.get("afterField"));
			if (!("participant".equals(target) && "participant".equals(source)) &&
				!("visit".equals(target) && "participant".equals(source)) &&
				!("specimen".equals(target) && Set.of("participant", "visit", "parent", "primary").contains(source))) {
				invalid("source/target");
			}
			if (StringUtils.isBlank(caption) || caption.length() > 120 || StringUtils.isBlank(field)) invalid("field/caption");
			if (group.length() > 120) invalid("group");
			String policy = str(rule.get("recordPolicy"));
			if (!policy.isEmpty() && !Set.of("latest", "latestComplete", "all").contains(policy)) invalid("recordPolicy");

			Long targetForm = id(rule.get("targetFormId"));
			Long sourceForm = id(rule.get("sourceFormId"));
			String targetFormName = str(rule.get("targetFormName"));
			String sourceFormName = str(rule.get("sourceFormName"));
			if (!targetFormName.isEmpty()) {
				targetForm = resolveFormId(targetFormName, deDaoFactory);
				set(rule, "targetFormId", targetForm);
			} else if (targetForm != null && deDaoFactory.getFormDao().getFormById(targetForm) == null) {
				invalid("targetFormId");
			}
			if (!sourceFormName.isEmpty()) {
				sourceForm = resolveFormId(sourceFormName, deDaoFactory);
				set(rule, "sourceFormId", sourceForm);
			} else if (sourceForm != null && deDaoFactory.getFormDao().getFormById(sourceForm) == null) {
				invalid("sourceFormId");
			}
			if ("participant".equals(target) && targetForm == null) invalid("targetFormId");
			if (targetForm != null) checkContext(cpId, targetForm, target, deDaoFactory);
			if (!afterField.isEmpty() && !FORM_START.equals(afterField)) {
				if (targetForm != null) {
					checkControl(targetForm, afterField, formSvc);
				} else if (afterField.startsWith("extensionDetail.")) {
					String name = afterField.substring("extensionDetail.".length());
					if (!name.matches("[A-Za-z][A-Za-z0-9_]*")) invalid("afterField");
					Map<String, Object> info = formSvc.getExtensionInfo(cpId,
						"visit".equals(target) ? Visit.EXTN : Specimen.EXTN);
					if (info == null || !(info.get("formId") instanceof Number n)) invalid("afterField");
					checkControl(((Number) info.get("formId")).longValue(), name, formSvc);
				} else {
					Set<String> fields = "visit".equals(target) ? VISIT_FIELDS : SPECIMEN_FIELDS;
					if (!fields.contains(afterField)) invalid("afterField");
				}
			}
			if (sourceForm != null) {
				checkContext(cpId, sourceForm, source, deDaoFactory);
				checkControl(sourceForm, field, formSvc);
			} else if (field.startsWith("extensionDetail.")) {
				String name = field.substring("extensionDetail.".length());
				if (!name.matches("[A-Za-z][A-Za-z0-9_]*")) invalid("field");
				String entityType = "participant".equals(source) ? Participant.EXTN :
					"visit".equals(source) ? Visit.EXTN : Specimen.EXTN;
				Map<String, Object> info = formSvc.getExtensionInfo(cpId, entityType);
				if (info == null || !(info.get("formId") instanceof Number number)) invalid("extensionDetail");
				checkControl(((Number) info.get("formId")).longValue(), name, formSvc);
			} else {
				Set<String> allowed = "participant".equals(source) ? CPR_FIELDS :
					"visit".equals(source) ? VISIT_FIELDS : SPECIMEN_FIELDS;
				if (!allowed.contains(field)) invalid("field");
			}
		}
	}

	private static Long resolveFormId(String name,
		com.krishagni.catissueplus.core.de.repository.DaoFactory daoFactory) {
		var form = daoFactory.getFormDao().getFormByName(name);
		if (form == null || form.getDeletedOn() != null) invalid("formName");
		return form.getId();
	}

	@SuppressWarnings("unchecked")
	private static void set(Map<?, ?> rule, String key, Object value) {
		((Map<String, Object>) rule).put(key, value);
	}

	private static void checkContext(Long cpId, Long formId, String level,
		com.krishagni.catissueplus.core.de.repository.DaoFactory daoFactory) {
		String entityType = "participant".equals(level) ? "Participant" :
			"visit".equals(level) ? "SpecimenCollectionGroup" : "Specimen";
		FormContextBean context = daoFactory.getFormDao().getFormContext(formId, cpId, entityType);
		if (context == null && "participant".equals(level)) {
			context = daoFactory.getFormDao().getFormContext(formId, cpId, "CommonParticipant");
		}
		if (context == null) invalid("formId");
	}

	private static void checkControl(Long formId, String field, FormService formSvc) {
		if (!field.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)?")) invalid("field");
		Container form = formSvc.getFormDefinition(RequestEvent.wrap(formId)).getPayload();
		if (form == null) invalid("formId");
		String[] path = field.split("\\.");
		Control control = form.getControl(path[0], "\\.");
		if (path.length == 2) {
			if (!(control instanceof SubFormControl subForm)) invalid("field");
			control = ((SubFormControl) control).getSubContainer().getControl(path[1], "\\.");
		}
		if (control == null || !isReferenceableControlType(control.getCtrlType())) invalid("field");
	}

	private static Long id(Object value) {
		if (value == null) return null;
		if (!(value instanceof Number n) || n.longValue() <= 0) invalid("formId");
		return ((Number) value).longValue();
	}

	private static String str(Object value) {
		return value instanceof String s ? s : "";
	}

	private static void invalid(String field) {
		throw OpenSpecimenException.userError(CommonErrorCode.INVALID_INPUT, "fieldReferences." + field);
	}
}
