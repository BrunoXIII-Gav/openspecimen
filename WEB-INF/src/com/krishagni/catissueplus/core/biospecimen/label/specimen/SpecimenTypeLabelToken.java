package com.krishagni.catissueplus.core.biospecimen.label.specimen;

import com.krishagni.catissueplus.core.biospecimen.domain.Specimen;
import com.krishagni.catissueplus.core.biospecimen.domain.SpecimenTypeAbbreviations;

public class SpecimenTypeLabelToken extends AbstractSpecimenLabelToken {

	public SpecimenTypeLabelToken() {
		this.name = "SP_TYPE";
	}

	@Override
	public String getLabel(Specimen specimen) {
		return SpecimenTypeAbbreviations.getAbbreviation(specimen);
	}
}
