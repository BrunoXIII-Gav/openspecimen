package com.krishagni.catissueplus.core.common.util;

import java.io.File;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import au.com.bytecode.opencsv.CSVWriter;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.exporter.domain.ExportErrorCode;

import static org.junit.Assert.*;

public class ExcelExportTest {
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void workbookPreservesTextPrecisionUnicodeBlankCellsQuotesAndFormulaLikeValues() throws Exception {
		String[] values = {"00123", "12345678901234567890", "José Quispe", "", "line 1\nline 2", "a,\"b\"",
			"=SUM(A1:A2)", "+cmd", "@name", "  spaces  ", "2026-11-20", "-1.25"};
		File source = csv(',', new String[]{"Heading"}, values);
		File target = new File(temporary.getRoot(), "result.xlsx");
		ExcelExport.convertCsv(source, target, ',', true);
		try (XSSFWorkbook workbook = new XSSFWorkbook(target)) {
			var sheet = workbook.getSheetAt(0);
			assertEquals(2, sheet.getPhysicalNumberOfRows());
			for (int i = 0; i < values.length; ++i) {
				assertEquals(CellType.STRING, sheet.getRow(1).getCell(i).getCellType());
				assertEquals(values[i], sheet.getRow(1).getCell(i).getStringCellValue());
			}
			assertTrue(sheet.getPaneInformation().isFreezePane());
		}
	}

	@Test
	public void streamingConversionKeepsAllRowsAndSupportsOtherDelimiters() throws Exception {
		String[][] rows = new String[305][];
		rows[0] = new String[]{"PPID", "Value"};
		for (int i = 1; i < rows.length; ++i) rows[i] = new String[]{"00" + i, "value;" + i};
		File target = new File(temporary.getRoot(), "large.xlsx");
		ExcelExport.convertCsv(csv(';', rows), target, ';', true);
		try (XSSFWorkbook workbook = new XSSFWorkbook(target)) {
			assertEquals(rows.length, workbook.getSheetAt(0).getPhysicalNumberOfRows());
			assertEquals("value;304", workbook.getSheetAt(0).getRow(304).getCell(1).getStringCellValue());
		}
	}

	@Test
	public void fullSheetsContinueInAnotherSheetWithTheColumnHeadings() throws Exception {
		File source = csv(',', new String[]{"Id"}, new String[]{"01"}, new String[]{"02"}, new String[]{"03"}, new String[]{"04"});
		File target = new File(temporary.getRoot(), "sheets.xlsx");
		ExcelExport.convertCsv(source, target, ',', true, 3);
		try (XSSFWorkbook workbook = new XSSFWorkbook(target)) {
			assertEquals(2, workbook.getNumberOfSheets());
			assertEquals("Id", workbook.getSheetAt(1).getRow(0).getCell(0).getStringCellValue());
			assertEquals("03", workbook.getSheetAt(1).getRow(1).getCell(0).getStringCellValue());
			assertEquals("04", workbook.getSheetAt(1).getRow(2).getCell(0).getStringCellValue());
		}
	}

	@Test
	public void queryMetadataAndBlankLinesAreKeptDuringInPlaceConversion() throws Exception {
		File file = csv(',', new String[]{"Exported by", "Researcher"}, new String[]{""}, new String[]{"Label"}, new String[]{"001"});
		ExcelExport.convertCsv(file, file, ',', false, 3);
		try (XSSFWorkbook workbook = new XSSFWorkbook(file)) {
			assertEquals("Exported by", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
			assertEquals("", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
			assertEquals("001", workbook.getSheetAt(1).getRow(0).getCell(0).getStringCellValue());
		}
	}

	@Test
	public void excelLimitsFailWithoutTruncatingOrPublishingAPartialWorkbook() throws Exception {
		for (boolean longCell : new boolean[]{true, false}) {
			String[] row = new String[longCell ? 1 : 16385];
			Arrays.fill(row, longCell ? "x".repeat(32768) : "x");
			File source = csv(',', row);
			File target = new File(temporary.getRoot(), "failure.xlsx");
			try {
				ExcelExport.convertCsv(source, target, ',', false);
				fail("Expected an Excel limit error");
			} catch (OpenSpecimenException e) {
				assertEquals(longCell ? ExportErrorCode.EXCEL_CELL_TOO_LONG : ExportErrorCode.EXCEL_TOO_MANY_COLUMNS,
					e.getErrors().get(0).error());
			}
			assertFalse(target.exists());
			try (var files = Files.list(temporary.getRoot().toPath())) {
				assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith("excel-export-")));
			}
		}
	}

	@Test
	public void formatDefaultsToCsvAndRejectsUnknownFormats() {
		assertEquals(ExportFormat.CSV, ExportFormat.from(null));
		assertEquals(ExportFormat.CSV, ExportFormat.from(" "));
		assertEquals(ExportFormat.CSV, ExportFormat.from("csv"));
		assertEquals(ExportFormat.XLSX, ExportFormat.from(" xlsx "));
		try {
			ExportFormat.from("../xlsm");
			fail("Unknown format accepted");
		} catch (OpenSpecimenException e) {
			assertEquals(ExportErrorCode.INVALID_FORMAT, e.getErrors().get(0).error());
		}
	}

	private File csv(char separator, String[]... rows) throws Exception {
		StringWriter text = new StringWriter();
		try (CSVWriter writer = new CSVWriter(text, separator)) {
			for (String[] row : rows) writer.writeNext(row);
		}
		Path path = Files.createTempFile(temporary.getRoot().toPath(), "source-", ".csv");
		Files.writeString(path, text.toString(), Charset.defaultCharset());
		return path.toFile();
	}
}
