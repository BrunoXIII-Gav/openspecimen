package com.krishagni.catissueplus.core.biospecimen.domain;

import org.hibernate.envers.Audited;

/** Immutable statement/code snapshots protect historical consent meaning. */
@Audited
public class SpecimenConsentResponse extends BaseEntity {
	private SpecimenConsent consent;
	private CpConsentTier consentTier;
	private String statementCode;
	private String statement;
	private String response;

	public SpecimenConsent getConsent() { return consent; }
	public void setConsent(SpecimenConsent consent) { this.consent = consent; }
	public CpConsentTier getConsentTier() { return consentTier; }
	public void setConsentTier(CpConsentTier consentTier) { this.consentTier = consentTier; }
	public String getStatementCode() { return statementCode; }
	public void setStatementCode(String statementCode) { this.statementCode = statementCode; }
	public String getStatement() { return statement; }
	public void setStatement(String statement) { this.statement = statement; }
	public String getResponse() { return response; }
	public void setResponse(String response) { this.response = response; }
}
