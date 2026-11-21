package com.krishagni.catissueplus.core.biospecimen.services.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.krishagni.catissueplus.core.biospecimen.domain.Specimen;
import com.krishagni.catissueplus.core.biospecimen.domain.SpecimenSavedEvent;
import com.krishagni.catissueplus.core.biospecimen.events.CollectionEventDetail;
import com.krishagni.catissueplus.core.biospecimen.events.ReceivedEventDetail;
import com.krishagni.catissueplus.core.biospecimen.events.SpecimenReceiptInput;
import com.krishagni.catissueplus.core.biospecimen.events.SpecimenMatrixInput;
import com.krishagni.catissueplus.core.biospecimen.events.SpecimenDetail;
import com.krishagni.catissueplus.core.biospecimen.domain.Visit;
import com.krishagni.catissueplus.core.biospecimen.services.SpecimenService;
import com.krishagni.catissueplus.core.common.events.RequestEvent;
import com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory;
import com.krishagni.catissueplus.core.biospecimen.repository.SpecimenListCriteria;
import com.krishagni.catissueplus.core.common.PlusTransactional;
import com.krishagni.catissueplus.core.common.access.AccessCtrlMgr;
import com.krishagni.catissueplus.core.common.access.ProtocolContentAccess;
import com.krishagni.catissueplus.core.common.errors.CommonErrorCode;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.common.events.ResponseEvent;
import com.krishagni.catissueplus.core.common.service.impl.EventPublisher;
import com.krishagni.rbac.common.errors.RbacErrorCode;

@Service
public class SpecimenWorkflowService {
	@Autowired private DaoFactory daoFactory;
	@Autowired private SpecimenService specimenService;

	@PlusTransactional
	public ResponseEvent<Map<String, Object>> getContext(Long id) {
		try {
			Specimen specimen = find(id);
			ensureRead(specimen);
			var cp = specimen.getCollectionProtocol();
			var visit = specimen.getVisit();
			// Only PPID and protocol event metadata accompany the specimen navigation.
			var cpr = SpecimenNavigationService.registrationContext(specimen.getRegistration());
			var visitContext = SpecimenNavigationService.visitContext(visit);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("specimenId", id); result.put("cpId", cp.getId()); result.put("cpr", cpr); result.put("visit", visitContext);
			result.put("visitStatus", visit.getStatus());
			return ResponseEvent.response(result);
		} catch (OpenSpecimenException e) { return ResponseEvent.error(e); }
		catch (Exception e) { return ResponseEvent.serverError(e); }
	}

	@PlusTransactional
	public ResponseEvent<Map<String, Object>> getMatrixContext(Long cpId, Long visitId) {
		try {
			Visit visit = visitId == null ? null : daoFactory.getVisitsDao().getById(visitId);
			if (visit == null || !visit.getCollectionProtocol().getId().equals(cpId)) denied();
			try { AccessCtrlMgr.getInstance().ensureReadSpecimenRights(visit.getRegistration(), false); }
			catch (OpenSpecimenException e) {
				if (!e.containsError(RbacErrorCode.ACCESS_DENIED)) throw e;
				AccessCtrlMgr.getInstance().ensureReadPrimarySpecimenRights(visit.getRegistration(), false);
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("cpId", cpId); result.put("visitStatus", visit.getStatus());
			result.put("cpr", SpecimenNavigationService.registrationContext(visit.getRegistration()));
			result.put("visit", SpecimenNavigationService.visitContext(visit));
			return ResponseEvent.response(result);
		} catch (OpenSpecimenException e) { return ResponseEvent.error(e); }
		catch (Exception e) { return ResponseEvent.serverError(e); }
	}

	@PlusTransactional
	public ResponseEvent<Boolean> saveMatrix(SpecimenMatrixInput input) {
		try {
			if (input == null || input.getCpId() == null || input.getCpId() <= 0 || input.getSpecimens() == null || input.getReceipts() == null ||
				(input.getSpecimens().isEmpty() && input.getReceipts().isEmpty()) || input.getReceipts().size() > 500) invalid("matrix selection");
			var seen = new java.util.HashSet<String>();
			for (SpecimenDetail detail : input.getSpecimens()) validateMatrixSpecimen(input.getCpId(), detail, null, seen);
			for (var receipt : input.getReceipts()) {
				if (receipt == null || !find(receipt.getId()).getCollectionProtocol().getId().equals(input.getCpId())) denied();
			}
			// Receipt quantity reconciliation precedes child consumption; both roll back on any error.
			if (!input.getReceipts().isEmpty()) receive(input.getReceipts()).throwErrorIfUnsuccessful();
			if (!input.getSpecimens().isEmpty()) collectMatrixSpecimens(input.getSpecimens());
			return ResponseEvent.response(true);
		} catch (OpenSpecimenException e) { return ResponseEvent.error(e); }
		catch (Exception e) { return ResponseEvent.serverError(e); }
	}

	protected void collectMatrixSpecimens(List<SpecimenDetail> specimens) {
		specimenService.collectSpecimens(RequestEvent.wrap(specimens)).throwErrorIfUnsuccessful();
	}

	private void validateMatrixSpecimen(Long cpId, SpecimenDetail detail, Long parentVisit, java.util.Set<String> seen) {
		if (detail == null || seen.size() >= 500) invalid("matrix specimen");
		Specimen existing = detail.getId() == null ? null : find(detail.getId());
		Long visitId = existing == null ? detail.getVisitId() : existing.getVisit().getId();
		if (visitId == null || visitId <= 0 || (parentVisit != null && !parentVisit.equals(visitId))) invalid("matrix visit");
		Visit visit = existing == null ? daoFactory.getVisitsDao().getById(visitId) : existing.getVisit();
		if (visit == null || !cpId.equals(visit.getCollectionProtocol().getId()) || (detail.getCpId() != null && !cpId.equals(detail.getCpId())) ||
			(detail.getCprId() != null && !detail.getCprId().equals(visit.getRegistration().getId()))) denied();
		if (existing != null) {
			ensureRead(existing);
			if (existing.isCollected() && detail.isUpdate()) invalid("collected specimens are references in the matrix");
		} else {
			AccessCtrlMgr.getInstance().ensureReadSpecimenRights(visit.getRegistration(), false);
		}
		if ((existing == null || !existing.isCollected()) && "New".equals(existing == null ? detail.getLineage() : existing.getLineage()) &&
			!"Complete".equals(visit.getStatus())) invalid("complete initial visit collection first");
		if (detail.getReqId() != null) {
			var requirement = daoFactory.getSpecimenRequirementDao().getById(detail.getReqId());
			if (requirement == null || !cpId.equals(requirement.getCollectionProtocol().getId()) || visit.getCpEvent() == null ||
				!requirement.getCollectionProtocolEvent().getId().equals(visit.getCpEvent().getId())) denied();
			if (existing == null && visit.getSpecimens().stream().anyMatch(specimen ->
				specimen.getSpecimenRequirement() != null && detail.getReqId().equals(specimen.getSpecimenRequirement().getId()))) {
				invalid("specimen requirement already exists; reload the matrix");
			}
		}
		String key = existing == null ? "r:" + visitId + ":" + detail.getReqId() : "s:" + existing.getId();
		if (!seen.add(key)) invalid("duplicate matrix specimen");
		if (detail.getChildren() != null) for (var child : detail.getChildren()) validateMatrixSpecimen(cpId, child, visitId, seen);
	}

	@PlusTransactional
	public ResponseEvent<Map<String, Object>> getReceiptMatrix(Long cpId, Long visitId, List<Long> ids) {
		try {
			if (cpId == null || cpId <= 0 || (visitId != null && visitId <= 0) || (visitId == null && (ids == null || ids.isEmpty()))) invalid("specimen selection");
			List<Specimen> specimens;
			if (ids != null && !ids.isEmpty()) {
				if (ids.size() > 500 || ids.stream().anyMatch(id -> id == null || id <= 0) || ids.stream().distinct().count() != ids.size()) invalid("specimen selection");
				specimens = new ArrayList<>();
				for (Long id : ids) specimens.add(find(id));
			} else {
				var siteCps = AccessCtrlMgr.getInstance().getReadAccessSpecimenSiteCps(cpId);
				if (siteCps != null && siteCps.isEmpty()) denied();
				var criteria = new SpecimenListCriteria().cpId(cpId).visitId(visitId).siteCps(siteCps)
					.useMrnSites(AccessCtrlMgr.getInstance().isAccessRestrictedBasedOnMrn());
				criteria.maxResults(501);
				specimens = daoFactory.getSpecimenDao().getSpecimens(criteria);
				if (specimens.size() > 500) invalid("select at most 500 specimens");
			}
			List<Map<String, Object>> rows = new ArrayList<>();
			for (Specimen specimen : specimens) {
				ensureRead(specimen); ensureReceive(specimen);
				if (!cpId.equals(specimen.getCollectionProtocol().getId()) || (visitId != null && !visitId.equals(specimen.getVisit().getId()))) denied();
				if (isPendingReceipt(specimen)) rows.add(toReceiptRow(specimen));
			}
			return ResponseEvent.response(Map.of("specimens", rows, "skipped", specimens.size() - rows.size()));
		} catch (OpenSpecimenException e) { return ResponseEvent.error(e); }
		catch (Exception e) { return ResponseEvent.serverError(e); }
	}

	@PlusTransactional
	public ResponseEvent<List<Long>> receive(List<SpecimenReceiptInput> input) {
		try {
			if (input == null || input.isEmpty() || input.size() > 500) invalid("receipt specimens");
			Map<Long, SpecimenReceiptInput> ordered = new TreeMap<>();
			for (var item : input) {
				if (item == null || item.getId() == null || item.getId() <= 0 || ordered.put(item.getId(), item) != null) invalid("specimen id");
			}
			Map<Specimen, Specimen> prepared = new LinkedHashMap<>();
			// Stable lock order; authorize and validate the whole batch before changing any specimen.
			for (var entry : ordered.entrySet()) {
				Specimen specimen = find(entry.getKey());
				ensureRead(specimen); ensureReceive(specimen);
				daoFactory.getSpecimenDao().lockForQuantityUpdate(specimen);
				if (!specimen.isPrimary() || !specimen.isCollected() || !specimen.isActive()) invalid("receipt requires a collected primary specimen");
				if (specimen.isReceived() && !Boolean.TRUE.equals(entry.getValue().getEditExisting())) invalid("select the specimen explicitly to edit its receipt");
				prepared.put(specimen, prepareReceipt(specimen, entry.getValue().getReceivedEvent()));
			}
			for (var entry : prepared.entrySet()) {
				Specimen specimen = entry.getKey(), receipt = entry.getValue();
				specimen.updateReceivedDetails(receipt);
				specimen.setAvailableQuantity(receipt.getAvailableQuantity());
				specimen.updateAvailableStatus();
				specimen.setUpdated(true);
				daoFactory.getSpecimenDao().saveOrUpdate(specimen, true);
				EventPublisher.getInstance().publish(new SpecimenSavedEvent(specimen));
			}
			return ResponseEvent.response(new ArrayList<>(ordered.keySet()));
		} catch (OpenSpecimenException e) { return ResponseEvent.error(e); }
		catch (Exception e) { return ResponseEvent.serverError(e); }
	}

	protected Specimen prepareReceipt(Specimen existing, ReceivedEventDetail event) {
		if (event == null || event.getReceivedQuality() == null || event.getReceivedQuality().isBlank() ||
			Specimen.TO_BE_RECEIVED.equals(event.getReceivedQuality()) || event.getTime() == null ||
			event.getUser() == null || event.getUser().getId() == null || event.getUser().getId() <= 0 ||
			(event.getNewLabel() != null && !event.getNewLabel().isBlank())) invalid("receipt quality, time and receiver");
		// Editing only receiver/time/comments must not reset an earlier quantity discrepancy.
		if (existing.isReceived() && event.getReceivedQtyDifferent() == null && event.getReceivedQty() == null && event.getReceivedQtyReason() == null) {
			var quantity = existing.getReceivedQuantity();
			event.setReceivedQtyDifferent(quantity != null && (existing.getInitialQuantity() == null || quantity.compareTo(existing.getInitialQuantity()) != 0));
			event.setReceivedQty(quantity); event.setReceivedQtyReason(existing.getReceivedQuantityReason());
		}
		for (String attr : List.of("receivedQuality", "time", "user", "comments", "receivedQtyDifferent", "receivedQty", "receivedQtyReason")) event.attrModified(attr);
		Specimen receipt = newReceiptSpecimen();
		receipt.setCollectionProtocol(existing.getCollectionProtocol()); receipt.setVisit(existing.getVisit());
		receipt.setSpecimenRequirement(existing.getSpecimenRequirement()); receipt.setLineage(existing.getLineage());
		receipt.setCollectionStatus(existing.getCollectionStatus()); receipt.setInitialQuantity(existing.getInitialQuantity());
		receipt.setAvailableQuantity(existing.getAvailableQuantity()); receipt.setLabel(existing.getLabel());
		receipt.setReceivedDetails(existing, event);
		return receipt;
	}
	protected Specimen newReceiptSpecimen() { return new Specimen(); }

	protected void ensureRead(Specimen specimen) { AccessCtrlMgr.getInstance().ensureReadSpecimenRights(specimen, false); }
	protected void ensureReceive(Specimen specimen) {
		AccessCtrlMgr.getInstance().ensureCreateOrUpdateSpecimenRights(specimen, false);
		ProtocolContentAccess.getInstance().ensureAllowed(specimen.getCollectionProtocol().getId(), ProtocolContentAccess.RECEIPT);
	}
	protected Specimen find(Long id) {
		Specimen specimen = id == null ? null : daoFactory.getSpecimenDao().getById(id);
		if (specimen == null) denied();
		return specimen;
	}
	private boolean isPendingReceipt(Specimen specimen) { return specimen.isPrimary() && specimen.isCollected() && specimen.isActive() && !specimen.isReceived(); }
	private Map<String, Object> toReceiptRow(Specimen specimen) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", specimen.getId()); row.put("label", specimen.getLabel()); row.put("lineage", specimen.getLineage());
		row.put("type", specimen.getSpecimenType().getValue()); row.put("specimenClass", specimen.getSpecimenClass().getValue());
		row.put("initialQty", specimen.getInitialQuantity()); row.put("availableQty", specimen.getAvailableQuantity());
		row.put("receivedEvent", ReceivedEventDetail.from(specimen));
		row.put("collectionEvent", CollectionEventDetail.from(specimen));
		return row;
	}
	private void invalid(String field) { throw OpenSpecimenException.userError(CommonErrorCode.INVALID_INPUT, field); }
	private void denied() { throw OpenSpecimenException.userError(RbacErrorCode.ACCESS_DENIED); }
}
