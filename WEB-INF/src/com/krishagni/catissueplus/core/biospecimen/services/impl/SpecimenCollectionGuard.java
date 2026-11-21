package com.krishagni.catissueplus.core.biospecimen.services.impl;

import java.util.List;

import com.krishagni.catissueplus.core.biospecimen.domain.Specimen;
import com.krishagni.catissueplus.core.biospecimen.domain.factory.SpecimenErrorCode;
import com.krishagni.catissueplus.core.biospecimen.events.SpecimenDetail;
import com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory;
import com.krishagni.catissueplus.core.common.errors.CommonErrorCode;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;

/** Validate the persisted parents of the whole collection before any specimen is changed. */
public class SpecimenCollectionGuard {

	private final DaoFactory daos;

	public SpecimenCollectionGuard(DaoFactory daos) { this.daos = daos; }

	public void validate(List<SpecimenDetail> specimens) {
		if (specimens == null || specimens.isEmpty()) {
			throw OpenSpecimenException.userError(CommonErrorCode.INVALID_INPUT, "specimens");
		}
		for (var specimen : specimens) validate(specimen, null);
	}

	private void validate(SpecimenDetail detail, Specimen enclosingParent) {
		if (detail == null) throw OpenSpecimenException.userError(CommonErrorCode.INVALID_INPUT, "specimen");
		Specimen existing = detail.getId() == null ? null : daos.getSpecimenDao().getById(detail.getId());
		if (detail.getId() != null && existing == null) {
			throw OpenSpecimenException.userError(SpecimenErrorCode.NOT_FOUND, detail.getId());
		}
		if (existing == null && detail.getReqId() != null && detail.getVisitId() != null) {
			existing = daos.getSpecimenDao().getSpecimenByVisitAndSr(detail.getVisitId(), detail.getReqId());
		}
		var requirement = existing != null ? existing.getSpecimenRequirement() : detail.getReqId() == null ? null :
			daos.getSpecimenRequirementDao().getById(detail.getReqId());
		String lineage = existing != null ? existing.getLineage() : requirement != null ? requirement.getLineage() : detail.getLineage();
		if ((existing == null || !existing.isCollected()) &&
			!Specimen.PENDING.equals(detail.getStatus()) && !"New".equals(lineage)) {
			Specimen parent = existing == null ? null : existing.getParentSpecimen();
			if (parent == null && requirement != null && requirement.getParentSpecimenRequirement() != null) {
				parent = daos.getSpecimenDao().getParentSpecimenByVisitAndSr(detail.getVisitId(), requirement.getId());
			} else if (parent == null && detail.getParentId() != null) {
				parent = daos.getSpecimenDao().getById(detail.getParentId());
			} else if (parent == null && detail.getParentLabel() != null) {
				parent = detail.getCpShortTitle() == null ? daos.getSpecimenDao().getByLabel(detail.getParentLabel()) :
					daos.getSpecimenDao().getByLabelAndCp(detail.getCpShortTitle(), detail.getParentLabel());
			}
			if (parent == null) parent = enclosingParent;
			if (parent == null || parent.getId() == null || !parent.isCollected() || !parent.isActive()) {
				throw OpenSpecimenException.userError(SpecimenErrorCode.COLL_PARENT_REQ);
			}
			parent.ensurePrimaryReceived();
		}
		if (detail.getChildren() != null) {
			for (var child : detail.getChildren()) validate(child, existing);
		}
	}
}
