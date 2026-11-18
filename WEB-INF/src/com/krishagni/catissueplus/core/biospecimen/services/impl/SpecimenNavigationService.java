package com.krishagni.catissueplus.core.biospecimen.services.impl;

import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocolRegistration;
import com.krishagni.catissueplus.core.biospecimen.domain.Visit;
import com.krishagni.catissueplus.core.biospecimen.repository.CprListCriteria;
import com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory;
import com.krishagni.catissueplus.core.common.PlusTransactional;
import com.krishagni.catissueplus.core.common.access.AccessCtrlMgr;
import com.krishagni.catissueplus.core.common.errors.CommonErrorCode;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.common.events.ResponseEvent;
import com.krishagni.rbac.common.errors.RbacErrorCode;

/** Navigation containers authorized by specimen access; never participant/visit DTOs. */
@Service
public class SpecimenNavigationService {
	@Autowired private DaoFactory daoFactory;

	@PlusTransactional
	public ResponseEvent<Map<String, Object>> getRegistrations(Long cpId, int startAt, int maxResults) {
		return getRegistrations(cpId, startAt, maxResults, null, true, false);
	}

	@PlusTransactional
	public ResponseEvent<Map<String, Object>> getRegistrations(Long cpId, int startAt, int maxResults, String ppid, boolean descending, boolean includeCount) {
		try {
			validate(cpId, startAt, maxResults);
			var access = AccessCtrlMgr.getInstance();
			var pairs = access.getReadAccessSpecimenSiteCps(cpId, false);
			if (pairs != null && pairs.isEmpty()) denied();
			var criteria = new CprListCriteria().cpId(cpId).siteCps(pairs == null ? null : new HashSet<>(pairs))
				.useMrnSites(access.isAccessRestrictedBasedOnMrn()).ppid(ppid).orderBy("cpr.ppid").asc(!descending).startAt(startAt).maxResults(maxResults + 1);
			var registrations = daoFactory.getCprDao().getCprs(criteria);
			List<Map<String, Object>> rows = new ArrayList<>();
			for (var cpr : registrations.stream().limit(maxResults).toList()) {
				if (!cpId.equals(cpr.getCollectionProtocol().getId())) denied();
				ensureRead(cpr); rows.add(registrationContext(cpr));
			}
			Map<String, Object> result = new LinkedHashMap<>(); result.put("registrations", rows); result.put("hasMore", registrations.size() > maxResults);
			if (includeCount) result.put("size", daoFactory.getCprDao().getCprCount(criteria));
			return ResponseEvent.response(result);
		} catch (OpenSpecimenException e) { return ResponseEvent.error(e); }
		catch (Exception e) { return ResponseEvent.serverError(e); }
	}

	@PlusTransactional
	public ResponseEvent<Map<String, Object>> getRegistration(Long cpId, Long cprId, int startAt, int maxResults) {
		try {
			validate(cpId, startAt, maxResults);
			var cpr = cprId == null ? null : daoFactory.getCprDao().getById(cprId);
			if (cpr == null || !cpId.equals(cpr.getCollectionProtocol().getId())) denied();
			ensureRead(cpr);
			var visits = cpr.getVisits().stream().filter(visit -> visit.getId() != null && !"Disabled".equals(visit.getActivityStatus()))
				.sorted(Comparator.comparing(visit -> visit.getId())).skip(startAt).limit(maxResults + 1L).toList();
			List<Map<String, Object>> rows = new ArrayList<>();
			for (var visit : visits.stream().limit(maxResults).toList()) {
				rows.add(visitContext(visit));
			}
			return ResponseEvent.response(Map.of("cpr", registrationContext(cpr), "visits", rows, "hasMore", visits.size() > maxResults));
		} catch (OpenSpecimenException e) { return ResponseEvent.error(e); }
		catch (Exception e) { return ResponseEvent.serverError(e); }
	}

	public static Map<String, Object> registrationContext(CollectionProtocolRegistration cpr) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", cpr.getId()); result.put("cpId", cpr.getCollectionProtocol().getId());
		result.put("cpShortTitle", cpr.getCollectionProtocol().getShortTitle()); result.put("ppid", cpr.getPpid());
		result.put("contextOnly", true); result.put("participant", Map.of("pmis", List.of()));
		return result;
	}
	public static Map<String, Object> visitContext(Visit visit) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", visit.getId()); result.put("cpId", visit.getCollectionProtocol().getId());
		result.put("cprId", visit.getRegistration().getId()); result.put("contextOnly", true); result.put("status", visit.getStatus());
		var event = visit.getCpEvent();
		result.put("eventId", event == null ? null : event.getId()); result.put("eventLabel", event == null ? null : event.getEventLabel());
		result.put("eventCode", event == null ? null : event.getCode());
		return result;
	}
	private void ensureRead(CollectionProtocolRegistration cpr) {
		try { AccessCtrlMgr.getInstance().ensureReadSpecimenRights(cpr, false); }
		catch (OpenSpecimenException e) {
			if (!e.containsError(RbacErrorCode.ACCESS_DENIED)) throw e;
			AccessCtrlMgr.getInstance().ensureReadPrimarySpecimenRights(cpr, false);
		}
	}
	private void validate(Long cpId, int startAt, int maxResults) {
		if (cpId == null || cpId <= 0 || startAt < 0 || maxResults < 1 || maxResults > 1000) {
			throw OpenSpecimenException.userError(CommonErrorCode.INVALID_INPUT, "navigation page");
		}
	}
	private void denied() { throw OpenSpecimenException.userError(RbacErrorCode.ACCESS_DENIED); }
}
