package com.krishagni.catissueplus.rest.controller;

import java.util.ArrayList;
import java.util.Collection;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocol;
import com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocolRegistration;
import com.krishagni.catissueplus.core.biospecimen.domain.CpWorkflowConfig;
import com.krishagni.catissueplus.core.biospecimen.domain.Participant;
import com.krishagni.catissueplus.core.biospecimen.domain.Specimen;
import com.krishagni.catissueplus.core.biospecimen.domain.Visit;
import com.krishagni.catissueplus.core.biospecimen.events.CollectionProtocolRegistrationDetail;
import com.krishagni.catissueplus.core.biospecimen.events.SpecimenDetail;
import com.krishagni.catissueplus.core.biospecimen.events.VisitDetail;
import com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory;
import com.krishagni.catissueplus.core.biospecimen.services.impl.FieldReferenceRules;
import com.krishagni.catissueplus.core.common.access.AccessCtrlMgr;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.de.events.FormDataDetail;
import com.krishagni.catissueplus.core.de.events.FormRecordCriteria;
import com.krishagni.catissueplus.core.de.services.FormService;
import com.krishagni.catissueplus.core.common.events.RequestEvent;

import edu.common.dynamicextensions.domain.nui.Container;
import edu.common.dynamicextensions.domain.nui.Control;
import edu.common.dynamicextensions.domain.nui.SubFormControl;
import edu.common.dynamicextensions.napi.ControlValue;
import edu.common.dynamicextensions.napi.FormData;
import krishagni.catissueplus.beans.FormContextBean;
import krishagni.catissueplus.beans.FormRecordEntryBean;

/** Resolves only configured ancestor fields; callers cannot choose a source object or field. */
@Controller
@RequestMapping("/field-references")
public class FieldReferencesController {
	private static final String WORKFLOW = "fieldReferences";

	@Autowired
	private DaoFactory daoFactory;

	@Autowired
	private FormService formSvc;

	@Autowired
	private com.krishagni.catissueplus.core.de.repository.DaoFactory deDaoFactory;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@RequestMapping(method = RequestMethod.GET, value = "/catalog")
	@ResponseBody
	public List<CatalogField> catalog(@RequestParam("cpId") Long cpId, @RequestParam("level") String level,
		@RequestParam(value = "formId", required = false) Long formId) {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		tx.setReadOnly(true);
		return tx.execute(status -> catalogInTransaction(cpId, level, formId));
	}

	private List<CatalogField> catalogInTransaction(Long cpId, String level, Long formId) {
		CollectionProtocol cp = daoFactory.getCollectionProtocolDao().getById(cpId);
		if (cp == null) return List.of();
		AccessCtrlMgr.getInstance().ensureUpdateCpRights(cp);
		String entityType = "participant".equals(level) ? "Participant" :
			"visit".equals(level) ? "SpecimenCollectionGroup" :
			"specimen".equals(level) ? "Specimen" : null;
		if (entityType == null) return List.of();
		if (formId != null) {
			if (deDaoFactory.getFormDao().getFormContext(formId, cpId, entityType) == null &&
				!("participant".equals(level) && deDaoFactory.getFormDao()
					.getFormContext(formId, cpId, "CommonParticipant") != null)) return List.of();
			return controls(formId, false);
		}

		List<CatalogField> result = new ArrayList<>();
		Set<String> fixed = "participant".equals(level) ? FieldReferenceRules.CPR_FIELDS :
			"visit".equals(level) ? FieldReferenceRules.VISIT_FIELDS : FieldReferenceRules.SPECIMEN_FIELDS;
		Set<String> visibleFixed = visibleFixedFields(daoFactory.getCollectionProtocolDao().getCpWorkflows(cpId), level, fixed);
		fixed.stream().sorted().forEach(name -> result.add(new CatalogField(name, name, "fixed",
			name.startsWith("participant.") || "surgicalPathologyNumber".equals(name), visibleFixed.contains(name))));
		String extensionType = "participant".equals(level) ? Participant.EXTN :
			"visit".equals(level) ? Visit.EXTN : Specimen.EXTN;
		Map<String, Object> extension = formSvc.getExtensionInfo(cpId, extensionType);
		if (extension != null && extension.get("formId") instanceof Number n) {
			result.addAll(controls(n.longValue(), true));
		}
		return result;
	}

	private List<CatalogField> controls(Long formId, boolean extension) {
		Container form = formSvc.getFormDefinition(RequestEvent.wrap(formId)).getPayload();
		if (form == null) return List.of();
		List<CatalogField> result = new ArrayList<>();
		for (Control control : form.getControls()) {
			String type = control.getCtrlType();
			if (control instanceof SubFormControl subForm && !extension) {
				for (Control child : subForm.getSubContainer().getControls()) {
					if (!FieldReferenceRules.isReferenceableControlType(child.getCtrlType()) ||
						child.getName() == null || !child.getName().matches("[A-Za-z][A-Za-z0-9_]*")) continue;
					result.add(new CatalogField(control.getName() + "." + child.getName(),
						control.getCaption() + " / " + child.getCaption(), child.getCtrlType(),
						control.isPhi() || child.isPhi(), true));
				}
				continue;
			}
			if (!FieldReferenceRules.isReferenceableControlType(type)) continue;
			String name = control.getName();
			if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]*")) continue;
			result.add(new CatalogField(extension ? "extensionDetail." + name : name,
				control.getCaption(), type, control.isPhi(), true));
		}
		return result;
	}

	@RequestMapping(method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public List<ResolvedField> resolve(
		@RequestParam("cpId") Long cpId,
		@RequestParam("target") String target,
		@RequestParam(value = "formId", required = false) Long targetFormId,
		@RequestParam(value = "cprId", required = false) Long cprId,
		@RequestParam(value = "visitId", required = false) Long visitId,
		@RequestParam(value = "specimenId", required = false) Long specimenId,
		@RequestParam(value = "parentId", required = false) Long parentId,
		@RequestParam(value = "page", required = false, defaultValue = "1") int page) {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		tx.setReadOnly(true);
		return tx.execute(status ->
			resolveInTransaction(cpId, target, targetFormId, cprId, visitId, specimenId, parentId, page));
	}

	private List<ResolvedField> resolveInTransaction(Long cpId, String target, Long targetFormId,
		Long cprId, Long visitId, Long specimenId, Long parentId, int page) {
		if (page < 1 || page > 100000) return List.of();
		if (!Set.of("participant", "visit", "specimen").contains(target)) {
			return List.of();
		}

		CollectionProtocol cp = daoFactory.getCollectionProtocolDao().getById(cpId);
		if (cp == null) {
			return List.of();
		}

		CollectionProtocolRegistration cpr = cprId != null && cprId > 0 ? daoFactory.getCprDao().getById(cprId) : null;
		Visit visit = visitId != null && visitId > 0 ? daoFactory.getVisitsDao().getById(visitId) : null;
		Specimen specimen = specimenId != null && specimenId > 0 ? daoFactory.getSpecimenDao().getById(specimenId) : null;
		Specimen parent = parentId != null && parentId > 0 ? daoFactory.getSpecimenDao().getById(parentId) : null;
		if ((cprId != null && cprId > 0 && cpr == null) || (visitId != null && visitId > 0 && visit == null) ||
			(specimenId != null && specimenId > 0 && specimen == null) || (parentId != null && parentId > 0 && parent == null)) return List.of();

		// Resolve the chain from the persisted destination, never from unrelated IDs supplied by the browser.
		if (specimen != null) {
			if (visit != null && !visit.equals(specimen.getVisit())) return List.of();
			visit = specimen.getVisit();
			if (parent != null && !parent.equals(specimen.getParentSpecimen())) return List.of();
			parent = specimen.getParentSpecimen();
		} else if (parent != null) {
			if (visit != null && !visit.equals(parent.getVisit())) return List.of();
			visit = parent.getVisit();
		}
		if (visit != null) {
			if (cpr != null && !cpr.equals(visit.getRegistration())) return List.of();
			cpr = visit.getRegistration();
		}
		if (cpr == null || !cp.equals(cpr.getCollectionProtocol())) return List.of();
		if ("specimen".equals(target) && specimen == null && visit == null && parent == null) return List.of();
		if ("participant".equals(target) && (visit != null || specimen != null || parent != null)) return List.of();
		if ("visit".equals(target) && (specimen != null || parent != null)) return List.of();

		AccessCtrlMgr access = AccessCtrlMgr.getInstance();
		if (specimen != null) access.ensureReadSpecimenRights(specimen, true);
		else if (visit != null) access.ensureReadVisitRights(visit, true);
		else if (parent != null) access.ensureReadSpecimenRights(parent, true);
		else access.ensureReadCprRights(cpr);
		if (targetFormId != null) {
			String type = "participant".equals(target) ? "Participant" :
				"visit".equals(target) ? "SpecimenCollectionGroup" : "Specimen";
			if (deDaoFactory.getFormDao().getFormContext(targetFormId, cpId, type) == null &&
				!("participant".equals(target) && deDaoFactory.getFormDao().getFormContext(targetFormId, cpId, "CommonParticipant") != null)) return List.of();
		}

		CpWorkflowConfig cfg = daoFactory.getCollectionProtocolDao().getCpWorkflows(cpId);
		if (cfg == null || cfg.getWorkflows().get(WORKFLOW) == null || cfg.getWorkflows().get(WORKFLOW).getData() == null) return List.of();
		Map<String, Object> config = cfg.getWorkflows().get(WORKFLOW).getData();
		if (Boolean.FALSE.equals(config.get("enabled"))) return List.of();
		Object configured = config.get("rules");
		if (!(configured instanceof List<?> rules)) return List.of();

		List<ResolvedField> result = new ArrayList<>();
		Map<String, List<FormRecordEntryBean>> entriesCache = new HashMap<>();
		Map<String, FormDataDetail> dataCache = new HashMap<>();
		Map<Long, String> formCaptions = new HashMap<>();
		Map<String, Set<String>> visibleFixedByLevel = new HashMap<>();
		for (Object item : rules) {
			if (!(item instanceof Map<?, ?> rule) || !target.equals(rule.get("target")) ||
				!sameForm(targetFormId, rule.get("targetFormId"))) continue;

			String source = string(rule.get("source"));
			if (!("visit".equals(target) && "participant".equals(source)) &&
				!("specimen".equals(target) && Set.of("participant", "visit", "parent", "primary").contains(source))) continue;
			String field = string(rule.get("field"));
			String caption = string(rule.get("caption"));
			String group = string(rule.get("group"));
			String afterField = string(rule.get("afterField"));
			if (StringUtils.isBlank(field) || StringUtils.isBlank(caption)) continue;
			Object entity = null;
			String entityType = null;
			boolean phi = false;
			try {
				switch (source) {
					case "participant" -> { entity = cpr; entityType = "Participant"; phi = access.ensureReadCprRights(cpr); }
					case "visit" -> { entity = visit; entityType = "SpecimenCollectionGroup";
						if (visit != null) phi = access.ensureReadVisitRights(visit, true); }
					case "parent" -> { entity = parent; entityType = "Specimen";
						if (parent != null) phi = access.ensureReadSpecimenRights(parent, true).phiAccess; }
					case "primary" -> {
						Specimen primary = specimen != null ? specimen : parent;
						int depth = 0;
						while (primary != null && primary.getParentSpecimen() != null && ++depth < 100) {
							if (!visit.equals(primary.getVisit())) { primary = null; break; }
							primary = primary.getParentSpecimen();
						}
						if (depth >= 100 || primary != null && !visit.equals(primary.getVisit())) continue;
						entity = primary;
						entityType = "Specimen";
						if (primary != null) phi = access.ensureReadSpecimenRights(primary, true).phiAccess;
					}
					default -> { }
				}
			} catch (OpenSpecimenException denied) { continue; }
			if (entity == null) continue;
			try {
				Long formId = number(rule.get("sourceFormId"));
				String sourceLevel = "parent".equals(source) || "primary".equals(source) ? "specimen" : source;
				if (formId == null && !field.startsWith("extensionDetail.") &&
					!visibleFixedByLevel.computeIfAbsent(sourceLevel, key ->
						visibleFixedFields(cfg, key, fixedFields(key))).contains(field)) {
					continue;
				}
				String sourceFormCaption = formId == null ? null : formCaptions.computeIfAbsent(formId, id -> {
					var form = deDaoFactory.getFormDao().getFormById(id);
					return form == null ? null : StringUtils.defaultIfBlank(form.getCaption(), form.getName());
				});
				if (formId != null) {
					ReferenceValue value = formValue(formId, entityType, entity, field, phi,
						string(rule.get("recordPolicy")), page, entriesCache, dataCache);
					if (value != null) result.add(new ResolvedField(caption, group, afterField, value.value(), value.hasMore(), sourceLevel, formId, sourceFormCaption));
				} else {
					Object value = fixedValue(entity, field, phi);
					if (value != null) result.add(new ResolvedField(caption, group, afterField, value, false, sourceLevel, null, null));
				}
			} catch (OpenSpecimenException denied) {
				// A destination may be readable even when this particular source is not.
			}
		}
		return result;
	}

	/**
	 * A dictionary saved by the field-settings editor is authoritative: default
	 * fields absent from it are deliberately hidden for this CP. Older dictionary
	 * workflows are overlays, so they retain the historical behaviour of exposing
	 * all default fields.
	 */
	private Set<String> visibleFixedFields(CpWorkflowConfig config, String level, Set<String> fixed) {
		if (config == null || config.getWorkflows().get("dictionary") == null ||
			!isAuthoritativeDictionary(config.getWorkflows().get("dictionary").getData())) {
			return fixed;
		}

		Object configured = config.getWorkflows().get("dictionary").getData().get("fields");
		if (!(configured instanceof List<?> fields)) return fixed;
		String prefix = "participant".equals(level) ? "cpr." : level + ".";
		Set<String> visible = new HashSet<>();
		for (Object item : fields) {
			if (!(item instanceof Map<?, ?> field) || Boolean.FALSE.equals(field.get("visible"))) continue;
			String name = string(field.get("name"));
			if (name.startsWith(prefix)) visible.add(name.substring(prefix.length()));
		}
		visible.retainAll(fixed);
		return visible;
	}

	private boolean isAuthoritativeDictionary(Map<String, Object> data) {
		if (data == null || !(data.get("osFieldsEditor") instanceof Map<?, ?> editor)) return false;
		Object version = editor.get("version");
		return version instanceof Number number && number.intValue() == 1;
	}

	private Set<String> fixedFields(String level) {
		return "participant".equals(level) ? FieldReferenceRules.CPR_FIELDS :
			"visit".equals(level) ? FieldReferenceRules.VISIT_FIELDS : FieldReferenceRules.SPECIMEN_FIELDS;
	}

	private Object fixedValue(Object entity, String field, boolean phi) {
		Object detail;
		Set<String> allowed;
		if (entity instanceof CollectionProtocolRegistration cpr) {
			allowed = FieldReferenceRules.CPR_FIELDS;
			detail = CollectionProtocolRegistrationDetail.from(cpr, !phi);
		} else if (entity instanceof Visit visit) {
			allowed = FieldReferenceRules.VISIT_FIELDS;
			detail = VisitDetail.from(visit, false, !phi);
		} else if (entity instanceof Specimen specimen) {
			allowed = FieldReferenceRules.SPECIMEN_FIELDS;
			detail = SpecimenDetail.from(specimen, false, !phi, true);
		} else return null;
		if (field.startsWith("extensionDetail.")) {
			String name = field.substring("extensionDetail.".length());
			if (!name.matches("[A-Za-z][A-Za-z0-9_]*")) return null;
			try {
				Object extension = PropertyUtils.getProperty(detail, entity instanceof CollectionProtocolRegistration ? "participant.extensionDetail" : "extensionDetail");
				if (extension instanceof com.krishagni.catissueplus.core.de.events.ExtensionDetail ext) {
					var attr = ext.getAttr(name);
					if (attr == null || !FieldReferenceRules.isReferenceableControlType(attr.getType())) return null;
					return attr.getDisplayValue() != null ? attr.getDisplayValue() : safeValue(attr.getValue());
				}
			} catch (Exception e) { return null; }
			return null;
		}
		if (!allowed.contains(field)) return null;
		if (!phi && (field.startsWith("participant.") || "surgicalPathologyNumber".equals(field))) return null;
		try { return safeValue(PropertyUtils.getProperty(detail, field)); }
		catch (Exception e) { return null; }
	}

	private ReferenceValue formValue(Long formId, String entityType, Object entity, String field, boolean phi,
		String policy, int page, Map<String, List<FormRecordEntryBean>> entriesCache, Map<String, FormDataDetail> dataCache) {
		if (!field.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)?")) return null;
		Long objectId = entity instanceof CollectionProtocolRegistration cpr ? cpr.getId() :
			entity instanceof Visit visit ? visit.getId() : ((Specimen) entity).getId();
		Long cpId = entity instanceof CollectionProtocolRegistration cpr ? cpr.getCollectionProtocol().getId() :
			entity instanceof Visit visit ? visit.getCollectionProtocol().getId() : ((Specimen) entity).getCollectionProtocol().getId();
		FormContextBean context = deDaoFactory.getFormDao().getFormContext(formId, cpId, entityType);
		if (context == null && entity instanceof CollectionProtocolRegistration cpr) {
			context = deDaoFactory.getFormDao().getFormContext(formId, cpId, "CommonParticipant");
			if (context != null) objectId = cpr.getParticipant().getId();
		}
		if (context == null) return null;
		Long contextId = context.getIdentifier();
		Long finalObjectId = objectId;
		boolean all = "all".equals(policy);
		boolean complete = "latestComplete".equals(policy);
		int startAt = all ? (page - 1) * 10 : 0;
		List<FormRecordEntryBean> records = entriesCache.computeIfAbsent(contextId + ":" + objectId + ":" + policy + ":" + page,
			key -> deDaoFactory.getFormDao().getReferenceRecordEntries(contextId, finalObjectId, complete,
				startAt, all ? 11 : 1));
		if (records == null || records.isEmpty()) return null;
		boolean hasMore = all && records.size() > 10;
		List<FormRecordEntryBean> selected = records.subList(0, all ? Math.min(10, records.size()) : 1);
		List<Object> values = new ArrayList<>();
		for (FormRecordEntryBean record : selected) {
			FormDataDetail detail = dataCache.computeIfAbsent(formId + ":" + record.getRecordId(), key -> {
				FormRecordCriteria crit = new FormRecordCriteria();
				crit.setFormId(formId);
				crit.setRecordId(record.getRecordId());
				return formSvc.getFormData(RequestEvent.wrap(crit)).getPayload();
			});
			if (detail == null || detail.getFormData() == null) continue;
			FormData data = detail.getFormData();
			Object value = formFieldValue(data, field, phi);
			if (value != null) values.add(value);
		}
		return values.isEmpty() && !hasMore ? null : new ReferenceValue(all ? values : values.get(0), hasMore);
	}

	private Object formFieldValue(FormData data, String field, boolean phi) {
		String[] path = field.split("\\.");
		ControlValue cv = data.getFieldValue(path[0]);
		if (cv == null || cv.getControl() == null || cv.getControl().isPhi() && !phi) return null;
		if (path.length == 1) {
			if (!FieldReferenceRules.isReferenceableControlType(cv.getControl().getCtrlType())) return null;
			return safeValue(cv.getControl().toDisplayValue(cv.getValue()));
		}
		if (!(cv.getControl() instanceof SubFormControl subForm)) return null;
		Control child = subForm.getSubContainer().getControl(path[1], "\\.");
		if (child == null || !FieldReferenceRules.isReferenceableControlType(child.getCtrlType()) || child.isPhi() && !phi) return null;
		Object rows = cv.getValue();
		if (rows instanceof FormData row) return subFormFieldValue(row, path[1], phi);
		if (!(rows instanceof List<?> list)) return null;
		List<Object> result = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof FormData row)) continue;
			Object value = subFormFieldValue(row, path[1], phi);
			result.add(value == null ? "-" : value);
		}
		return result.isEmpty() ? null : result;
	}

	private Object subFormFieldValue(FormData row, String name, boolean phi) {
		ControlValue cv = row.getFieldValue(name);
		if (cv == null || cv.getControl() == null || cv.getControl().isPhi() && !phi ||
			!FieldReferenceRules.isReferenceableControlType(cv.getControl().getCtrlType())) return null;
		return safeValue(cv.getControl().toDisplayValue(cv.getValue()));
	}

	private Object safeValue(Object value) {
		if ("###".equals(value)) return null;
		if (value instanceof Date date) return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(date);
		if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) return value;
		if (value instanceof Collection<?> items) {
			return items.stream().filter(v -> v instanceof CharSequence || v instanceof Number || v instanceof Boolean).limit(100).toList();
		}
		return null;
	}

	private boolean sameForm(Long requested, Object configured) {
		Long id = number(configured);
		return requested == null ? id == null : requested.equals(id);
	}

	private Long number(Object value) {
		return value instanceof Number n ? n.longValue() : null;
	}

	private String string(Object value) {
		return value instanceof String s ? s : "";
	}

	private record ReferenceValue(Object value, boolean hasMore) { }

	public record ResolvedField(String caption, String group, String afterField, Object value, boolean hasMore,
		String sourceLevel, Long sourceFormId, String sourceFormCaption) { }

	public record CatalogField(String name, String caption, String type, boolean sensitive, boolean visible) { }
}
