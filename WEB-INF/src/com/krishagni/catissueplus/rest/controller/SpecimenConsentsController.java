package com.krishagni.catissueplus.rest.controller;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;

import com.krishagni.catissueplus.core.biospecimen.events.FileDetail;
import com.krishagni.catissueplus.core.biospecimen.events.SpecimenConsentDetail;
import com.krishagni.catissueplus.core.biospecimen.services.SpecimenConsentService;
import com.krishagni.catissueplus.core.common.events.RequestEvent;
import com.krishagni.catissueplus.core.common.events.ResponseEvent;
import com.krishagni.catissueplus.core.common.util.Utility;

/** REST API kept separate from the legacy CPR consent endpoints. */
@Controller
@RequestMapping("/specimens/{specimenId}/consents")
public class SpecimenConsentsController {
	@Autowired
	private SpecimenConsentService specimenConsentSvc;

	@RequestMapping(method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public List<SpecimenConsentDetail> getConsents(@PathVariable("specimenId") Long specimenId) {
		return unwrap(specimenConsentSvc.getConsents(RequestEvent.wrap(specimenId)));
	}

	@RequestMapping(method = RequestMethod.GET, value = "/{consentId}")
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public SpecimenConsentDetail getConsent(@PathVariable("specimenId") Long specimenId, @PathVariable("consentId") Long consentId) {
		return unwrap(specimenConsentSvc.getConsent(RequestEvent.wrap(detail(specimenId, consentId))));
	}

	@RequestMapping(method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public SpecimenConsentDetail createConsent(@PathVariable("specimenId") Long specimenId, @RequestBody SpecimenConsentDetail input) {
		input.setSpecimenId(specimenId);
		return unwrap(specimenConsentSvc.saveConsent(RequestEvent.wrap(input)));
	}

	@RequestMapping(method = RequestMethod.PUT, value = "/{consentId}")
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public SpecimenConsentDetail updateConsent(@PathVariable("specimenId") Long specimenId, @PathVariable("consentId") Long consentId, @RequestBody SpecimenConsentDetail input) {
		input.setSpecimenId(specimenId);
		input.setId(consentId);
		return unwrap(specimenConsentSvc.saveConsent(RequestEvent.wrap(input)));
	}

	@RequestMapping(method = RequestMethod.DELETE, value = "/{consentId}")
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public Boolean deleteConsent(@PathVariable("specimenId") Long specimenId, @PathVariable("consentId") Long consentId) {
		return unwrap(specimenConsentSvc.deleteConsent(RequestEvent.wrap(detail(specimenId, consentId))));
	}

	@RequestMapping(method = RequestMethod.POST, value = "/{consentId}/documents")
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public String uploadDocument(@PathVariable("specimenId") Long specimenId, @PathVariable("consentId") Long consentId, @RequestParam("file") MultipartFile file) throws IOException {
		FileDetail input = fileDetail(consentId, specimenId);
		input.setFilename(file.getOriginalFilename());
		input.setContentType(file.getContentType());
		input.setFileIn(file.getInputStream());
		return unwrap(specimenConsentSvc.uploadDocument(RequestEvent.wrap(input)));
	}

	@RequestMapping(method = RequestMethod.GET, value = "/{consentId}/documents/{documentId}")
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public void downloadDocument(@PathVariable("specimenId") Long specimenId, @PathVariable("consentId") Long consentId, @PathVariable("documentId") Long documentId, HttpServletResponse response) throws IOException {
		FileDetail input = fileDetail(consentId, specimenId);
		input.setName(documentId.toString());
		File file = unwrap(specimenConsentSvc.getDocument(RequestEvent.wrap(input)));
		String filename = file.getName().replaceFirst("^spmn-consent-[^_]+_", "");
		Utility.sendToClient(response, filename, file);
	}

	@RequestMapping(method = RequestMethod.DELETE, value = "/{consentId}/documents/{documentId}")
	@ResponseStatus(HttpStatus.OK)
	@ResponseBody
	public Boolean deleteDocument(@PathVariable("specimenId") Long specimenId, @PathVariable("consentId") Long consentId, @PathVariable("documentId") Long documentId) {
		FileDetail input = fileDetail(consentId, specimenId);
		input.setName(documentId.toString());
		return unwrap(specimenConsentSvc.deleteDocument(RequestEvent.wrap(input)));
	}

	private SpecimenConsentDetail detail(Long specimenId, Long consentId) {
		SpecimenConsentDetail result = new SpecimenConsentDetail();
		result.setSpecimenId(specimenId);
		result.setId(consentId);
		return result;
	}

	private FileDetail fileDetail(Long consentId, Long specimenId) {
		FileDetail result = new FileDetail();
		result.setId(consentId);
		Map<String, Object> props = new HashMap<>();
		props.put("specimenId", specimenId);
		result.setObjectProps(props);
		return result;
	}

	private <T> T unwrap(ResponseEvent<T> response) {
		response.throwErrorIfUnsuccessful();
		return response.getPayload();
	}
}
