package com.krishagni.catissueplus.core.biospecimen.domain;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

import org.hibernate.envers.Audited;

import com.krishagni.catissueplus.core.administrative.domain.User;

/**
 * A consent recorded for one concrete specimen.  It deliberately does not
 * replace CollectionProtocolRegistration's protocol-level consent.
 */
@Audited
public class SpecimenConsent extends BaseEntity {
	public static final String DRAFT = "Draft";
	public static final String COMPLETE = "Complete";

	private Specimen specimen;

	private CollectionProtocolRegistration cpr;

	private String consentType;

	private String consentVersion;

	private Date consentSignatureDate;

	private User witness;

	private String comments;

	private String status = DRAFT;

	private Set<SpecimenConsentResponse> responses = new LinkedHashSet<>();

	private Set<SpecimenConsentDocument> documents = new LinkedHashSet<>();

	public Specimen getSpecimen() {
		return specimen;
	}

	public void setSpecimen(Specimen specimen) {
		this.specimen = specimen;
	}

	public CollectionProtocolRegistration getCpr() {
		return cpr;
	}

	public void setCpr(CollectionProtocolRegistration cpr) {
		this.cpr = cpr;
	}

	public String getConsentType() {
		return consentType;
	}

	public void setConsentType(String consentType) {
		this.consentType = consentType;
	}

	public String getConsentVersion() {
		return consentVersion;
	}

	public void setConsentVersion(String consentVersion) {
		this.consentVersion = consentVersion;
	}

	public Date getConsentSignatureDate() {
		return consentSignatureDate;
	}

	public void setConsentSignatureDate(Date consentSignatureDate) {
		this.consentSignatureDate = consentSignatureDate;
	}

	public User getWitness() {
		return witness;
	}

	public void setWitness(User witness) {
		this.witness = witness;
	}

	public String getComments() {
		return comments;
	}

	public void setComments(String comments) {
		this.comments = comments;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public Set<SpecimenConsentResponse> getResponses() {
		return responses;
	}

	public void setResponses(Set<SpecimenConsentResponse> responses) {
		this.responses = responses;
	}

	public Set<SpecimenConsentDocument> getDocuments() {
		return documents;
	}

	public void setDocuments(Set<SpecimenConsentDocument> documents) {
		this.documents = documents;
	}
}
