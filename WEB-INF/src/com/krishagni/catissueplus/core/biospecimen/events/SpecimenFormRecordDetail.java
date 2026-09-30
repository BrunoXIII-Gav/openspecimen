package com.krishagni.catissueplus.core.biospecimen.events;

import java.util.HashMap;
import java.util.Map;

public class SpecimenFormRecordDetail {
	private Integer specimenIndex;

	private Long formId;

	private Long formCtxtId;

	private Map<String, Object> data = new HashMap<>();

	public Integer getSpecimenIndex() {
		return specimenIndex;
	}

	public void setSpecimenIndex(Integer specimenIndex) {
		this.specimenIndex = specimenIndex;
	}

	public Long getFormId() {
		return formId;
	}

	public void setFormId(Long formId) {
		this.formId = formId;
	}

	public Long getFormCtxtId() {
		return formCtxtId;
	}

	public void setFormCtxtId(Long formCtxtId) {
		this.formCtxtId = formCtxtId;
	}

	public Map<String, Object> getData() {
		return data;
	}

	public void setData(Map<String, Object> data) {
		this.data = data;
	}
}
