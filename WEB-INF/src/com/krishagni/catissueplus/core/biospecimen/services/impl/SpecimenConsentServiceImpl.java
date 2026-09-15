package com.krishagni.catissueplus.core.biospecimen.services.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.SessionFactory;

import com.krishagni.catissueplus.core.administrative.domain.User;
import com.krishagni.catissueplus.core.biospecimen.ConfigParams;
import com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocol;
import com.krishagni.catissueplus.core.biospecimen.domain.CpConsentTier;
import com.krishagni.catissueplus.core.biospecimen.domain.Specimen;
import com.krishagni.catissueplus.core.biospecimen.domain.SpecimenConsent;
import com.krishagni.catissueplus.core.biospecimen.domain.SpecimenConsentDocument;
import com.krishagni.catissueplus.core.biospecimen.domain.SpecimenConsentResponse;
import com.krishagni.catissueplus.core.biospecimen.domain.factory.SpecimenErrorCode;
import com.krishagni.catissueplus.core.biospecimen.events.ConsentTierResponseDetail;
import com.krishagni.catissueplus.core.biospecimen.events.FileDetail;
import com.krishagni.catissueplus.core.biospecimen.events.SpecimenConsentDetail;
import com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory;
import com.krishagni.catissueplus.core.biospecimen.services.SpecimenConsentService;
import com.krishagni.catissueplus.core.common.PlusTransactional;
import com.krishagni.catissueplus.core.common.access.AccessCtrlMgr;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.common.events.RequestEvent;
import com.krishagni.catissueplus.core.common.events.ResponseEvent;
import com.krishagni.catissueplus.core.common.util.AuthUtil;

/** Service for the additive, specimen-scoped consent model. */
public class SpecimenConsentServiceImpl implements SpecimenConsentService {
	private DaoFactory daoFactory;
	private SessionFactory sessionFactory;

	public void setDaoFactory(DaoFactory daoFactory) { this.daoFactory = daoFactory; }
	public void setSessionFactory(SessionFactory sessionFactory) { this.sessionFactory = sessionFactory; }

	@Override
	@PlusTransactional
	public ResponseEvent<List<SpecimenConsentDetail>> getConsents(RequestEvent<Long> req) {
		try {
			Specimen specimen = getSpecimen(req.getPayload(), false);
			List<SpecimenConsent> consents = sessionFactory.getCurrentSession()
				.createQuery("select distinct c from com.krishagni.catissueplus.core.biospecimen.domain.SpecimenConsent c " +
					"left join fetch c.responses left join fetch c.documents where c.specimen = :specimen order by c.creationTime desc", SpecimenConsent.class)
				.setParameter("specimen", specimen).list();
			return ResponseEvent.response(consents.stream().map(SpecimenConsentDetail::from).collect(Collectors.toList()));
		} catch (OpenSpecimenException ose) {
			return ResponseEvent.error(ose);
		} catch (Exception e) {
			return ResponseEvent.serverError(e);
		}
	}

	@Override
	@PlusTransactional
	public ResponseEvent<SpecimenConsentDetail> getConsent(RequestEvent<SpecimenConsentDetail> req) {
		try {
			SpecimenConsentDetail input = req.getPayload();
			Specimen specimen = getSpecimen(input.getSpecimenId(), false);
			return ResponseEvent.response(SpecimenConsentDetail.from(getConsent(specimen, input.getId())));
		} catch (OpenSpecimenException ose) {
			return ResponseEvent.error(ose);
		} catch (Exception e) {
			return ResponseEvent.serverError(e);
		}
	}

	@Override
	@PlusTransactional
	public ResponseEvent<SpecimenConsentDetail> saveConsent(RequestEvent<SpecimenConsentDetail> req) {
		try {
			SpecimenConsentDetail input = req.getPayload();
			Specimen specimen = getSpecimen(input.getSpecimenId(), true);
			SpecimenConsent consent = input.getId() == null ? new SpecimenConsent() : getConsent(specimen, input.getId());
			Date now = new Date();
			User currentUser = AuthUtil.getCurrentUser();

			if (consent.getId() == null) {
				consent.setSpecimen(specimen);
				consent.setCpr(specimen.getRegistration());
				consent.setCreator(currentUser);
				consent.setCreationTime(now);
			}

			consent.setConsentType(input.getConsentType());
			consent.setConsentVersion(input.getConsentVersion());
			consent.setConsentSignatureDate(input.getConsentSignatureDate());
			consent.setComments(input.getComments());
			consent.setStatus(StringUtils.defaultIfBlank(input.getStatus(), SpecimenConsent.DRAFT));
			consent.setWitness(input.getWitness() == null || input.getWitness().getId() == null ? null : daoFactory.getUserDao().getById(input.getWitness().getId()));
			consent.setUpdater(currentUser);
			consent.setUpdateTime(now);
			setResponses(consent, input.getResponses(), specimen.getCollectionProtocol());

			if (consent.getId() == null) {
				sessionFactory.getCurrentSession().persist(consent);
			}

			return ResponseEvent.response(SpecimenConsentDetail.from(consent));
		} catch (OpenSpecimenException ose) {
			return ResponseEvent.error(ose);
		} catch (Exception e) {
			return ResponseEvent.serverError(e);
		}
	}

	@Override
	@PlusTransactional
	public ResponseEvent<Boolean> deleteConsent(RequestEvent<SpecimenConsentDetail> req) {
		try {
			SpecimenConsentDetail input = req.getPayload();
			Specimen specimen = getSpecimen(input.getSpecimenId(), true);
			SpecimenConsent consent = getConsent(specimen, input.getId());
			for (SpecimenConsentDocument document : new ArrayList<>(consent.getDocuments())) {
				deleteStoredFile(document.getFileName());
			}
			sessionFactory.getCurrentSession().remove(consent);
			return ResponseEvent.response(true);
		} catch (OpenSpecimenException ose) {
			return ResponseEvent.error(ose);
		} catch (Exception e) {
			return ResponseEvent.serverError(e);
		}
	}

	@Override
	@PlusTransactional
	public ResponseEvent<String> uploadDocument(RequestEvent<FileDetail> req) {
		OutputStream out = null;
		try {
			FileDetail input = req.getPayload();
			Long specimenId = (Long) input.getObjectProps().get("specimenId");
			Specimen specimen = getSpecimen(specimenId, true);
			SpecimenConsent consent = getConsent(specimen, input.getId());
			String safeName = new File(input.getFilename()).getName();
			String storedName = "spmn-consent-" + UUID.randomUUID() + "_" + safeName;
			File dir = new File(ConfigParams.getConsentsDirPath());
			dir.mkdirs();
			out = new FileOutputStream(new File(dir, storedName));
			IOUtils.copy(input.getFileIn(), out);

			SpecimenConsentDocument document = new SpecimenConsentDocument();
			document.setConsent(consent);
			document.setFileName(storedName);
			document.setOriginalFileName(safeName);
			document.setContentType(input.getContentType());
			document.setCreator(AuthUtil.getCurrentUser());
			document.setCreationTime(new Date());
			consent.getDocuments().add(document);
			sessionFactory.getCurrentSession().persist(document);
			return ResponseEvent.response(safeName);
		} catch (OpenSpecimenException ose) {
			return ResponseEvent.error(ose);
		} catch (Exception e) {
			return ResponseEvent.serverError(e);
		} finally {
			IOUtils.closeQuietly(out);
		}
	}

	@Override
	@PlusTransactional
	public ResponseEvent<File> getDocument(RequestEvent<FileDetail> req) {
		try {
			FileDetail input = req.getPayload();
			Specimen specimen = getSpecimen((Long) input.getObjectProps().get("specimenId"), false);
			SpecimenConsent consent = getConsent(specimen, input.getId());
			SpecimenConsentDocument document = getDocument(consent, Long.parseLong(input.getName()));
			File file = new File(ConfigParams.getConsentsDirPath(), document.getFileName());
			if (!file.exists()) return ResponseEvent.userError(SpecimenErrorCode.CONSENT_DOCUMENT_NOT_FOUND);
			return ResponseEvent.response(file);
		} catch (OpenSpecimenException ose) {
			return ResponseEvent.error(ose);
		} catch (Exception e) {
			return ResponseEvent.serverError(e);
		}
	}

	@Override
	@PlusTransactional
	public ResponseEvent<Boolean> deleteDocument(RequestEvent<FileDetail> req) {
		try {
			FileDetail input = req.getPayload();
			Specimen specimen = getSpecimen((Long) input.getObjectProps().get("specimenId"), true);
			SpecimenConsent consent = getConsent(specimen, input.getId());
			SpecimenConsentDocument document = getDocument(consent, Long.parseLong(input.getName()));
			deleteStoredFile(document.getFileName());
			consent.getDocuments().remove(document);
			return ResponseEvent.response(true);
		} catch (OpenSpecimenException ose) {
			return ResponseEvent.error(ose);
		} catch (Exception e) {
			return ResponseEvent.serverError(e);
		}
	}

	private Specimen getSpecimen(Long specimenId, boolean update) {
		Specimen specimen = daoFactory.getSpecimenDao().getById(specimenId);
		if (specimen == null) throw OpenSpecimenException.userError(SpecimenErrorCode.NOT_FOUND, specimenId);
		if (!specimen.getCollectionProtocol().isSpecimenConsentsEnabled()) {
			throw OpenSpecimenException.userError(SpecimenErrorCode.SPECIMEN_CONSENTS_DISABLED);
		}
		if (update) AccessCtrlMgr.getInstance().ensureCreateOrUpdateSpecimenRights(specimen, false);
		else AccessCtrlMgr.getInstance().ensureReadSpecimenRights(specimen, false);
		return specimen;
	}

	private SpecimenConsent getConsent(Specimen specimen, Long consentId) {
		SpecimenConsent consent = sessionFactory.getCurrentSession().find(SpecimenConsent.class, consentId);
		if (consent == null || !consent.getSpecimen().equals(specimen)) throw OpenSpecimenException.userError(SpecimenErrorCode.CONSENT_NOT_FOUND);
		return consent;
	}

	private SpecimenConsentDocument getDocument(SpecimenConsent consent, Long documentId) {
		return consent.getDocuments().stream().filter(d -> d.getId().equals(documentId)).findFirst()
			.orElseThrow(() -> OpenSpecimenException.userError(SpecimenErrorCode.CONSENT_DOCUMENT_NOT_FOUND));
	}

	private void setResponses(SpecimenConsent consent, List<ConsentTierResponseDetail> input, CollectionProtocol cp) {
		Map<String, CpConsentTier> tiers = (cp.getConsentsSource() != null ? cp.getConsentsSource() : cp).getConsentTier().stream()
			.collect(Collectors.toMap(t -> t.getStatement().getCode(), t -> t));
		for (SpecimenConsentResponse response : new ArrayList<>(consent.getResponses())) {
			sessionFactory.getCurrentSession().remove(response);
		}
		consent.getResponses().clear();
		for (ConsentTierResponseDetail responseDetail : input == null ? new ArrayList<ConsentTierResponseDetail>() : input) {
			CpConsentTier tier = tiers.get(responseDetail.getCode());
			if (tier == null) throw OpenSpecimenException.userError(SpecimenErrorCode.INVALID_CONSENT_RESPONSE, responseDetail.getCode());
			SpecimenConsentResponse response = new SpecimenConsentResponse();
			response.setConsent(consent);
			response.setConsentTier(tier);
			response.setStatementCode(tier.getStatement().getCode());
			response.setStatement(tier.getStatement().getStatement());
			response.setResponse(responseDetail.getResponse());
			consent.getResponses().add(response);
			if (consent.getId() != null) {
				sessionFactory.getCurrentSession().persist(response);
			}
		}
	}

	private void deleteStoredFile(String fileName) {
		File file = new File(ConfigParams.getConsentsDirPath(), fileName);
		if (file.exists()) file.delete();
	}
}
