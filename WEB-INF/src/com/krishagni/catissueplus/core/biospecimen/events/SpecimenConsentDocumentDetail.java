package com.krishagni.catissueplus.core.biospecimen.events;

import java.util.Date;

import com.krishagni.catissueplus.core.biospecimen.domain.SpecimenConsentDocument;
import com.krishagni.catissueplus.core.common.events.UserSummary;

public class SpecimenConsentDocumentDetail {
	private Long id;
	private String fileName;
	private String contentType;
	private UserSummary creator;
	private Date creationTime;

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public String getFileName() { return fileName; }
	public void setFileName(String fileName) { this.fileName = fileName; }
	public String getContentType() { return contentType; }
	public void setContentType(String contentType) { this.contentType = contentType; }
	public UserSummary getCreator() { return creator; }
	public void setCreator(UserSummary creator) { this.creator = creator; }
	public Date getCreationTime() { return creationTime; }
	public void setCreationTime(Date creationTime) { this.creationTime = creationTime; }

	public static SpecimenConsentDocumentDetail from(SpecimenConsentDocument document) {
		SpecimenConsentDocumentDetail result = new SpecimenConsentDocumentDetail();
		result.setId(document.getId());
		result.setFileName(document.getOriginalFileName());
		result.setContentType(document.getContentType());
		result.setCreator(UserSummary.from(document.getCreator()));
		result.setCreationTime(document.getCreationTime());
		return result;
	}
}
