package com.krishagni.catissueplus.core.common.util;

import java.util.Locale;

import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.exporter.domain.ExportErrorCode;

public enum ExportFormat {
	CSV, XLSX;

	public static ExportFormat from(String value) {
		if (value == null || value.isBlank()) {
			return CSV;
		}

		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw OpenSpecimenException.userError(ExportErrorCode.INVALID_FORMAT, value);
		}
	}
}
