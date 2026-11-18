package com.krishagni.catissueplus.core.biospecimen.events;

import java.util.List;

/** Existing-visit matrix: pending specimens and receipt updates share one transaction. */
public class SpecimenMatrixInput {
	private Long cpId;
	private List<SpecimenDetail> specimens = List.of();
	private List<SpecimenReceiptInput> receipts = List.of();
	public Long getCpId() { return cpId; }
	public void setCpId(Long cpId) { this.cpId = cpId; }
	public List<SpecimenDetail> getSpecimens() { return specimens; }
	public void setSpecimens(List<SpecimenDetail> specimens) { this.specimens = specimens; }
	public List<SpecimenReceiptInput> getReceipts() { return receipts; }
	public void setReceipts(List<SpecimenReceiptInput> receipts) { this.receipts = receipts; }
}
