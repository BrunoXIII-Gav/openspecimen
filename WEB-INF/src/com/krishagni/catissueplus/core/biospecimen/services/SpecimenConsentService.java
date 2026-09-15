package com.krishagni.catissueplus.core.biospecimen.services;

import java.io.File;
import java.util.List;

import com.krishagni.catissueplus.core.biospecimen.events.FileDetail;
import com.krishagni.catissueplus.core.biospecimen.events.SpecimenConsentDetail;
import com.krishagni.catissueplus.core.common.events.RequestEvent;
import com.krishagni.catissueplus.core.common.events.ResponseEvent;

public interface SpecimenConsentService {
	ResponseEvent<List<SpecimenConsentDetail>> getConsents(RequestEvent<Long> req);
	ResponseEvent<SpecimenConsentDetail> getConsent(RequestEvent<SpecimenConsentDetail> req);
	ResponseEvent<SpecimenConsentDetail> saveConsent(RequestEvent<SpecimenConsentDetail> req);
	ResponseEvent<Boolean> deleteConsent(RequestEvent<SpecimenConsentDetail> req);
	ResponseEvent<String> uploadDocument(RequestEvent<FileDetail> req);
	ResponseEvent<File> getDocument(RequestEvent<FileDetail> req);
	ResponseEvent<Boolean> deleteDocument(RequestEvent<FileDetail> req);
}
