package com.krishagni.catissueplus.core.biospecimen.events;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import com.krishagni.catissueplus.core.biospecimen.domain.SpecimenConsent;
import com.krishagni.catissueplus.core.biospecimen.domain.SpecimenConsentResponse;
import com.krishagni.catissueplus.core.common.events.UserSummary;

public class SpecimenConsentDetail {
	private Long id;
	private Long specimenId;
	private Long cprId;
	private String consentType;
	private String consentVersion;
	private Date consentSignatureDate;
	private UserSummary witness;
	private String comments;
	private String status;
	private List<ConsentTierResponseDetail> responses = new ArrayList<>();
	private List<SpecimenConsentDocumentDetail> documents = new ArrayList<>();
	private Date creationTime;
	private Date updateTime;

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public Long getSpecimenId() { return specimenId; }
	public void setSpecimenId(Long specimenId) { this.specimenId = specimenId; }
	public Long getCprId() { return cprId; }
	public void setCprId(Long cprId) { this.cprId = cprId; }
	public String getConsentType() { return consentType; }
	public void setConsentType(String consentType) { this.consentType = consentType; }
	public String getConsentVersion() { return consentVersion; }
	public void setConsentVersion(String consentVersion) { this.consentVersion = consentVersion; }
	public Date getConsentSignatureDate() { return consentSignatureDate; }
	public void setConsentSignatureDate(Date consentSignatureDate) { this.consentSignatureDate = consentSignatureDate; }
	public UserSummary getWitness() { return witness; }
	public void setWitness(UserSummary witness) { this.witness = witness; }
	public String getComments() { return comments; }
	public void setComments(String comments) { this.comments = comments; }
	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }
	public List<ConsentTierResponseDetail> getResponses() { return responses; }
	public void setResponses(List<ConsentTierResponseDetail> responses) { this.responses = responses; }
	public List<SpecimenConsentDocumentDetail> getDocuments() { return documents; }
	public void setDocuments(List<SpecimenConsentDocumentDetail> documents) { this.documents = documents; }
	public Date getCreationTime() { return creationTime; }
	public void setCreationTime(Date creationTime) { this.creationTime = creationTime; }
	public Date getUpdateTime() { return updateTime; }
	public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }

	public static SpecimenConsentDetail from(SpecimenConsent consent) {
		SpecimenConsentDetail result = new SpecimenConsentDetail();
		result.setId(consent.getId());
		result.setSpecimenId(consent.getSpecimen().getId());
		result.setCprId(consent.getCpr().getId());
		result.setConsentType(consent.getConsentType());
		result.setConsentVersion(consent.getConsentVersion());
		result.setConsentSignatureDate(consent.getConsentSignatureDate());
		result.setWitness(UserSummary.from(consent.getWitness()));
		result.setComments(consent.getComments());
		result.setStatus(consent.getStatus());
		result.setCreationTime(consent.getCreationTime());
		result.setUpdateTime(consent.getUpdateTime());
		result.setResponses(consent.getResponses().stream().map(SpecimenConsentDetail::toResponse).collect(Collectors.toList()));
		result.setDocuments(consent.getDocuments().stream().map(SpecimenConsentDocumentDetail::from).collect(Collectors.toList()));
		return result;
	}

	private static ConsentTierResponseDetail toResponse(SpecimenConsentResponse response) {
		ConsentTierResponseDetail result = new ConsentTierResponseDetail();
		result.setCode(response.getStatementCode());
		result.setStatement(response.getStatement());
		result.setResponse(response.getResponse());
		return result;
	}
}
