package com.krishagni.catissueplus.core.biospecimen.domain;

import org.hibernate.envers.Audited;

@Audited
public class SpecimenConsentDocument extends BaseEntity {
	private SpecimenConsent consent;
	private String fileName;
	private String originalFileName;
	private String contentType;

	public SpecimenConsent getConsent() { return consent; }
	public void setConsent(SpecimenConsent consent) { this.consent = consent; }
	public String getFileName() { return fileName; }
	public void setFileName(String fileName) { this.fileName = fileName; }
	public String getOriginalFileName() { return originalFileName; }
	public void setOriginalFileName(String originalFileName) { this.originalFileName = originalFileName; }
	public String getContentType() { return contentType; }
	public void setContentType(String contentType) { this.contentType = contentType; }
}
