package com.krishagni.catissueplus.core.biospecimen.events;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.krishagni.catissueplus.core.common.errors.CommonErrorCode;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;

/** Receipt is an update of an existing specimen, never a specimen creation payload. */
public class SpecimenReceiptInput {
	private Long id;
	private ReceivedEventDetail receivedEvent;
	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public ReceivedEventDetail getReceivedEvent() { return receivedEvent; }
	public void setReceivedEvent(ReceivedEventDetail receivedEvent) { this.receivedEvent = receivedEvent; }
	@JsonAnySetter
	public void rejectOtherFields(String name, Object value) {
		throw OpenSpecimenException.userError(CommonErrorCode.INVALID_INPUT, name);
	}
}
