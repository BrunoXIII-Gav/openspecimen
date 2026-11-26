package com.krishagni.catissueplus.core.biospecimen.events;

/** Documentary follow-up only. This status does not grant or deny any specimen use. */
public class ConsentStatusDetail {
  private final boolean trackingEnabled;
  private final boolean documentMissing;
  private final boolean responsesMissing;

  public ConsentStatusDetail(boolean trackingEnabled, boolean documentMissing, boolean responsesMissing) {
    this.trackingEnabled = trackingEnabled;
    this.documentMissing = trackingEnabled && documentMissing;
    this.responsesMissing = trackingEnabled && responsesMissing;
  }

  public boolean isTrackingEnabled() { return trackingEnabled; }
  public boolean isDocumentMissing() { return documentMissing; }
  public boolean isResponsesMissing() { return responsesMissing; }
  public boolean isPending() { return documentMissing || responsesMissing; }

  public String getStatus() {
    if (!trackingEnabled) return "NOT_APPLICABLE";
    if (documentMissing && responsesMissing) return "BOTH_PENDING";
    if (documentMissing) return "DOCUMENT_PENDING";
    if (responsesMissing) return "RESPONSES_PENDING";
    return "NO_PENDING";
  }
}
