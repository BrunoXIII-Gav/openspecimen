package com.krishagni.catissueplus.core.biospecimen.domain;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.krishagni.catissueplus.core.administrative.domain.PermissibleValue;
import com.krishagni.catissueplus.core.administrative.repository.PermissibleValueDao;
import com.krishagni.catissueplus.core.biospecimen.domain.factory.CpErrorCode;
import com.krishagni.catissueplus.core.biospecimen.services.impl.CpWorkflowTxnCache;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;

public class SpecimenTypeAbbreviations {

	public static final String WORKFLOW = "specimenTypeAbbreviations";

	public static String getAbbreviation(Specimen specimen) {
		PermissibleValue type = specimen.getSpecimenType();
		if (type == null) {
			return StringUtils.EMPTY;
		}

		if (specimen.getCollectionProtocol() != null && specimen.getCpId() != null && type.getId() != null) {
			Map<String, String> abbreviations = CpWorkflowTxnCache.getInstance()
				.getValue(specimen.getCpId(), WORKFLOW, "abbreviations");
			String abbreviation = abbreviations == null ? null : abbreviations.get(type.getId().toString());
			if (StringUtils.isNotBlank(abbreviation)) {
				return abbreviation;
			}
		}

		String abbreviation = type.getProps().get("abbreviation");
		return StringUtils.isBlank(abbreviation) ? StringUtils.EMPTY : abbreviation;
	}

	public static Map<String, Object> validate(Map<String, Object> data, PermissibleValueDao pvDao) {
		if (data == null || !(data.getOrDefault("version", 1) instanceof Number version) ||
			version.doubleValue() != 1 || !(data.get("abbreviations") instanceof Map<?, ?> abbreviations)) {
			throw invalidConfiguration();
		}

		Map<String, String> normalized = new HashMap<>();
		for (Map.Entry<?, ?> entry : abbreviations.entrySet()) {
			if (!(entry.getKey() instanceof String key) || !key.matches("[1-9][0-9]*") ||
				!(entry.getValue() instanceof String abbreviation) || abbreviation.trim().length() > 255) {
				throw invalidConfiguration();
			}

			Long typeId;
			try {
				typeId = Long.valueOf(key);
			} catch (NumberFormatException e) {
				throw invalidConfiguration();
			}

			PermissibleValue type = pvDao.getById(typeId);
			if (type == null || !"specimen_type".equals(type.getAttribute()) || type.getParent() == null) {
				throw invalidConfiguration();
			}

			if (StringUtils.isNotBlank(abbreviation)) {
				normalized.put(key, abbreviation.trim());
			}
		}

		Map<String, Object> result = new HashMap<>(data);
		result.put("version", 1);
		result.put("abbreviations", normalized);
		if (data.containsKey("intermediateTypeIds")) {
			if (!(data.get("intermediateTypeIds") instanceof List<?> typeIds)) {
				throw invalidConfiguration();
			}

			LinkedHashSet<String> ids = new LinkedHashSet<>();
			for (Object id : typeIds) {
				if (!(id instanceof String key) || !key.matches("[1-9][0-9]*")) {
					throw invalidConfiguration();
				}
				PermissibleValue type;
				try {
					type = pvDao.getById(Long.valueOf(key));
				} catch (NumberFormatException e) {
					throw invalidConfiguration();
				}
				if (type == null || !"specimen_type".equals(type.getAttribute()) || type.getParent() == null) {
					throw invalidConfiguration();
				}
				ids.add(key);
			}
			result.put("intermediateTypeIds", new ArrayList<>(ids));
		}
		return result;
	}

	private static OpenSpecimenException invalidConfiguration() {
		return OpenSpecimenException.userError(CpErrorCode.INVALID_SPECIMEN_TYPE_ABBREVIATIONS);
	}
}
