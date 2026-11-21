package com.krishagni.catissueplus.core.biospecimen.services.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.krishagni.catissueplus.core.biospecimen.domain.Specimen;
import com.krishagni.catissueplus.core.biospecimen.events.SpecimenFormRecordDetail;
import com.krishagni.catissueplus.core.common.PlusTransactional;
import com.krishagni.catissueplus.core.common.access.AccessCtrlMgr;
import com.krishagni.catissueplus.core.common.access.ProtocolContentAccess;
import com.krishagni.catissueplus.core.common.errors.CommonErrorCode;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.common.events.RequestEvent;
import com.krishagni.catissueplus.core.de.events.FormCtxtSummary;
import com.krishagni.catissueplus.core.de.repository.FormDao;
import com.krishagni.catissueplus.core.de.services.FormService;
import com.krishagni.rbac.common.errors.RbacErrorCode;
import edu.common.dynamicextensions.domain.nui.Container;
import edu.common.dynamicextensions.napi.FormData;

@Service
public class SpecimenProcessingService {
	@Autowired private FormDao formDao;
	@Autowired private FormService formSvc;

	@PlusTransactional
	public List<FormCtxtSummary> getForms(Long cpId) {
		if (cpId == null || cpId <= 0) invalid();
		var sites = AccessCtrlMgr.getInstance().getReadAccessSpecimenSiteCps(cpId);
		if (sites != null && sites.isEmpty()) denied();
		return java.util.stream.Stream.concat(formDao.getFormContexts(-1L, "SpecimenEvent").stream(),
			formDao.getFormContexts(cpId, "SpecimenEvent").stream())
			.filter(form -> !form.isSysForm() && "SpecimenEvent".equals(form.getEntityType()))
			.map(summary -> {
				var form = formDao.getFormById(summary.getFormId());
				if (form == null || form.getDeletedOn() != null) return null;
				summary.setFormName(form.getName()); summary.setFormCaption(form.getCaption());
				return summary;
			})
			.filter(java.util.Objects::nonNull)
			.filter(form -> ProtocolContentAccess.getInstance().isAllowed(cpId,
				ProtocolContentAccess.formKey(form.getFormId(), form.getFormName()), "SpecimenEvent"))
			.toList();
	}

	@PlusTransactional
	public void save(Specimen specimen, SpecimenFormRecordDetail input) {
		if (specimen == null || specimen.isPrimary() || !specimen.isCollected() || input == null ||
			input.getFormId() == null || input.getFormCtxtId() == null) invalid();
		AccessCtrlMgr.getInstance().ensureCreateOrUpdateSpecimenRights(specimen, false);
		Long cpId = specimen.getCollectionProtocol().getId();
		ProtocolContentAccess.getInstance().ensureCreationAllowed(cpId, specimen.getLineage());
		var context = formDao.getFormContext(input.getFormCtxtId());
		if (context == null || context.getDeletedOn() != null || context.isSysForm() ||
			!"SpecimenEvent".equals(context.getEntityType()) || !input.getFormId().equals(context.getContainerId()) ||
			context.getEntityId() != null || (context.getCpId() == null ||
			(!Long.valueOf(-1).equals(context.getCpId()) && !cpId.equals(context.getCpId())))) denied();
		Container form = getForm(input.getFormId());
		if (form == null) invalid();
		if (!ProtocolContentAccess.getInstance().isAllowed(cpId,
			ProtocolContentAccess.formKey(input.getFormId(), form.getName()), "SpecimenEvent")) denied();
		Map<String, Object> data = new HashMap<>(input.getData() == null ? Map.of() : input.getData());
		data.remove("id"); data.remove("recordId"); data.remove("appData");
		// FormService enriches appData while saving the record. It must therefore be
		// mutable; Map.of() causes the save to fail with UnsupportedOperationException.
		Map<String, Object> appData = new HashMap<>();
		appData.put("objectId", specimen.getId());
		appData.put("formCtxtId", input.getFormCtxtId());
		appData.put("formId", input.getFormId());
		appData.put("formStatus", "COMPLETE");
		data.put("appData", appData);
		saveFormData(FormData.getFormData(form, data));
		if (specimen.getParentEvent() != null) specimen.getParentEvent().setProcessingForm(form.getCaption());
	}

	protected Container getForm(Long id) { return Container.getContainer(id); }
	protected void saveFormData(FormData data) {
		formSvc.saveBulkFormData(RequestEvent.wrap(List.of(data))).throwErrorIfUnsuccessful();
	}
	private void invalid() { throw OpenSpecimenException.userError(CommonErrorCode.INVALID_INPUT, "processing event"); }
	private void denied() { throw OpenSpecimenException.userError(RbacErrorCode.ACCESS_DENIED); }
}
