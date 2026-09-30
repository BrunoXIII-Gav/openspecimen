package com.krishagni.catissueplus.core.biospecimen.events;

import java.util.ArrayList;
import java.util.List;

public class SpecimenBatchDetail {
	private List<SpecimenDetail> specimens = new ArrayList<>();

	private List<SpecimenFormRecordDetail> formRecords = new ArrayList<>();

	public List<SpecimenDetail> getSpecimens() {
		return specimens;
	}

	public void setSpecimens(List<SpecimenDetail> specimens) {
		this.specimens = specimens;
	}

	public List<SpecimenFormRecordDetail> getFormRecords() {
		return formRecords;
	}

	public void setFormRecords(List<SpecimenFormRecordDetail> formRecords) {
		this.formRecords = formRecords;
	}
}
