package com.krishagni.catissueplus.core.common.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.krishagni.catissueplus.core.administrative.repository.FormListCriteria;
import com.krishagni.catissueplus.core.common.PlusTransactional;
import com.krishagni.catissueplus.core.common.access.AccessCtrlMgr;
import com.krishagni.catissueplus.core.common.access.ProtocolContentAccess;
import com.krishagni.catissueplus.core.common.events.ResponseEvent;
import com.krishagni.catissueplus.core.de.domain.Form;
import com.krishagni.catissueplus.core.de.repository.FormDao;

@Service
public class ProtocolContentService {

	@Autowired
	private FormDao formDao;

	@Autowired
	private com.krishagni.catissueplus.core.de.domain.FormInfoCache formInfoCache;

	@PlusTransactional
	public ResponseEvent<List<Map<String, Object>>> getOptions(Long cpId) {
		AccessCtrlMgr.getInstance().ensureUserIsAdmin();
		List<Map<String, Object>> result = new ArrayList<>();
		result.add(Map.of("key", ProtocolContentAccess.COLLECTION, "labelCode", "roles.content.collection"));
		result.add(Map.of("key", ProtocolContentAccess.RECEIPT, "labelCode", "roles.content.receipt"));
		FormListCriteria criteria = new FormListCriteria().entityTypes(Arrays.asList(
			"Participant", "ParticipantExtension", "CommonParticipant", "VisitExtension", "SpecimenCollectionGroup",
			"Specimen", "SpecimenExtension", "SpecimenPrimaryExtension", "SpecimenDerivedExtension", "SpecimenAliquotExtension", "SpecimenEvent"))
			.maxResults(Integer.MAX_VALUE);
		if (cpId != null && cpId > 0) criteria.cpIds(Arrays.asList(cpId, -1L));
		Map<Long, java.util.Set<String>> entityTypes = new LinkedHashMap<>();
		for (var association : formDao.getEntityForms(criteria)) {
			if (association.getEntityType() != null) entityTypes.computeIfAbsent(association.getFormId(), id -> new java.util.LinkedHashSet<>())
				.add(association.getEntityType());
		}
		for (Form form : formDao.getForms(criteria)) {
			String key = ProtocolContentAccess.formKey(form.getId(), form.getName());
			if (key.equals(ProtocolContentAccess.COLLECTION) || key.equals(ProtocolContentAccess.RECEIPT)) continue;
			Map<String, Object> option = new LinkedHashMap<>();
			option.put("key", key);
			option.put("formId", form.getId());
			option.put("label", form.getCaption());
			option.put("name", form.getName());
			option.put("entityTypes", entityTypes.getOrDefault(form.getId(), java.util.Set.of()));
			result.add(option);
		}
		return ResponseEvent.response(result);
	}

	@PlusTransactional
	public ResponseEvent<Map<String, Object>> getAccess(Long cpId) {
		ProtocolContentAccess access = ProtocolContentAccess.getInstance();
		var user = com.krishagni.catissueplus.core.common.util.AuthUtil.getCurrentUser();
		if (user == null) return ResponseEvent.userError(com.krishagni.rbac.common.errors.RbacErrorCode.ACCESS_DENIED);
		// Do not add a new CP-read requirement to users already allowed to work on its records.
		if (user.isAdmin() || !access.hasClinicalContext(cpId)) AccessCtrlMgr.getInstance().ensureReadCpRights(cpId);
		List<String> createLineages = List.of("New", "Derived", "Aliquot").stream()
			.filter(lineage -> access.isCreationAllowed(cpId, lineage)).collect(java.util.stream.Collectors.toList());
		if (access.isUnrestricted(cpId)) {
			return ResponseEvent.response(Map.of("unrestricted", true, "allowed", List.of(), "extensionKeys", Map.of(), "blockedExtensions", List.of(), "createLineages", createLineages));
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("unrestricted", false);
		result.put("createLineages", createLineages);
		java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
		if (access.isAllowed(cpId, ProtocolContentAccess.COLLECTION)) allowed.add(ProtocolContentAccess.COLLECTION);
		if (access.isAllowed(cpId, ProtocolContentAccess.RECEIPT)) allowed.add(ProtocolContentAccess.RECEIPT);
		for (var form : formDao.getEntityForms(new FormListCriteria().cpIds(Arrays.asList(cpId, -1L)).maxResults(Integer.MAX_VALUE))) {
			String key = ProtocolContentAccess.formKey(form.getFormId(), form.getName());
			if (access.isAllowed(cpId, key, form.getEntityType())) allowed.add(key);
		}
		result.put("allowed", allowed);
		Map<String, String> extensionKeys = new LinkedHashMap<>();
		List<String> blockedExtensions = new ArrayList<>();
		for (String type : List.of("ParticipantExtension", "VisitExtension", "SpecimenExtension",
			"SpecimenPrimaryExtension", "SpecimenDerivedExtension", "SpecimenAliquotExtension")) {
			Map<String, Object> info = formInfoCache.getFormInfo(cpId, type);
			if (info != null) {
				String key = ProtocolContentAccess.formKey(((Number) info.get("formId")).longValue());
				extensionKeys.put(type, key);
				if (!access.isAllowed(cpId, key, type)) blockedExtensions.add(type);
			}
		}
		result.put("extensionKeys", extensionKeys);
		result.put("blockedExtensions", blockedExtensions);
		return ResponseEvent.response(result);
	}
}
