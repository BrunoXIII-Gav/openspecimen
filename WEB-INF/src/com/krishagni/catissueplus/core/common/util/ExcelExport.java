package com.krishagni.catissueplus.core.common.util;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import au.com.bytecode.opencsv.CSVReader;

import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.exporter.domain.ExportErrorCode;

public final class ExcelExport {
	public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	private ExcelExport() { }

	public static void convertCsv(File source, File target, char separator, boolean header) throws IOException {
		convertCsv(source, target, separator, header, SpreadsheetVersion.EXCEL2007.getMaxRows());
	}

	static void convertCsv(File source, File target, char separator, boolean header, int maxRows) throws IOException {
		if (maxRows < 2 || maxRows > SpreadsheetVersion.EXCEL2007.getMaxRows()) {
			throw new IllegalArgumentException("Invalid Excel sheet row limit");
		}

		Path temporary = Files.createTempFile(target.toPath().toAbsolutePath().getParent(), "excel-export-", ".xlsx");
		SXSSFWorkbook workbook = new SXSSFWorkbook(100);
		try {
			workbook.setCompressTempFiles(true);
			Font font = workbook.createFont();
			font.setBold(true);
			CellStyle headerStyle = workbook.createCellStyle();
			headerStyle.setFont(font);
			SXSSFSheet sheet = newSheet(workbook, header);
			int rowNumber = 0;
			long sourceRow = 0;
			String[] headings = null;
			// Read the generated CSV without trimming cells or interpreting numbers/formulas.
			try (CSVReader reader = new CSVReader(Files.newBufferedReader(source.toPath(), Charset.defaultCharset()), separator)) {
				String[] values;
				while ((values = reader.readNext()) != null) {
					++sourceRow;
					if (rowNumber == maxRows) {
						sheet = newSheet(workbook, header);
						rowNumber = 0;
						if (header && headings != null) {
							writeRow(sheet.createRow(rowNumber++), headings, headerStyle, 1);
						}
					}
					if (header && headings == null) {
						headings = values;
					}
					writeRow(sheet.createRow(rowNumber++), values, header && sourceRow == 1 ? headerStyle : null, sourceRow);
				}
			}
			try (OutputStream out = Files.newOutputStream(temporary)) {
				workbook.write(out);
			}
			// Publish only a complete workbook; source and target may be the same query file.
			Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} finally {
			try {
				workbook.close();
			} finally {
				workbook.dispose();
				Files.deleteIfExists(temporary);
			}
		}
	}

	private static SXSSFSheet newSheet(SXSSFWorkbook workbook, boolean header) {
		SXSSFSheet sheet = workbook.createSheet("Data " + (workbook.getNumberOfSheets() + 1));
		sheet.setDefaultColumnWidth(22);
		if (header) {
			sheet.createFreezePane(0, 1);
		}
		return sheet;
	}

	private static void writeRow(Row row, String[] values, CellStyle style, long sourceRow) {
		if (values.length > SpreadsheetVersion.EXCEL2007.getMaxColumns()) {
			throw OpenSpecimenException.userError(ExportErrorCode.EXCEL_TOO_MANY_COLUMNS, sourceRow);
		}
		for (int column = 0; column < values.length; ++column) {
			String value = values[column] == null ? "" : values[column];
			if (value.length() > SpreadsheetVersion.EXCEL2007.getMaxTextLength()) {
				throw OpenSpecimenException.userError(ExportErrorCode.EXCEL_CELL_TOO_LONG, sourceRow, column + 1);
			}
			var cell = row.createCell(column);
			cell.setCellValue(value);
			if (style != null) {
				cell.setCellStyle(style);
			}
		}
	}
}
