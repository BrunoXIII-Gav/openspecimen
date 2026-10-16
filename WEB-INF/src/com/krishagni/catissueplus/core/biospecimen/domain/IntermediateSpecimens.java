package com.krishagni.catissueplus.core.biospecimen.domain;

import java.util.List;

import com.krishagni.catissueplus.core.biospecimen.services.impl.CpWorkflowTxnCache;

public class IntermediateSpecimens {

	public static boolean isIntermediate(Specimen specimen) {
		if (specimen == null || !specimen.isDerivative() || specimen.getCollectionProtocol() == null ||
			specimen.getCpId() == null || specimen.getSpecimenType() == null || specimen.getSpecimenType().getId() == null) {
			return false;
		}

		List<String> typeIds = CpWorkflowTxnCache.getInstance().getValue(
			specimen.getCpId(), SpecimenTypeAbbreviations.WORKFLOW, "intermediateTypeIds");
		return typeIds != null && typeIds.contains(specimen.getSpecimenType().getId().toString());
	}

	public static String getSourceLabel(Specimen specimen) {
		Specimen source = specimen.getParentSpecimen();
		while (source != null && isIntermediate(source) && source.getParentSpecimen() != null) {
			source = source.getParentSpecimen();
		}
		return source == null ? null : source.getLabel();
	}
}
