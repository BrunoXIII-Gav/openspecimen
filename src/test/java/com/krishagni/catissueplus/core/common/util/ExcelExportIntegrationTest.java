package com.krishagni.catissueplus.core.common.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.zip.ZipFile;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.common.events.RequestEvent;
import com.krishagni.catissueplus.core.common.events.ResponseEvent;
import com.krishagni.catissueplus.core.common.service.ConfigurationService;
import com.krishagni.catissueplus.core.de.services.QueryService;
import com.krishagni.catissueplus.core.exporter.domain.ExportJob;
import com.krishagni.catissueplus.core.exporter.services.ExportService;
import com.krishagni.catissueplus.core.exporter.services.impl.ExportServiceImpl;
import com.krishagni.catissueplus.rest.controller.ExportJobsController;
import com.krishagni.catissueplus.rest.controller.QueryController;
import com.krishagni.rbac.common.errors.RbacErrorCode;

import static org.junit.Assert.*;

public class ExcelExportIntegrationTest {
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void commonExportZipContainsExcelAndUnmodifiedAttachmentsAndExcludesIntermediateCsv() throws Exception {
		Field singleton = ConfigUtil.class.getDeclaredField("instance"); singleton.setAccessible(true);
		Object previous = singleton.get(null);
		ConfigUtil config = new ConfigUtil();
		set(config, "cfgSvc", proxy(ConfigurationService.class, (name, args) -> temporary.getRoot().getAbsolutePath()));
		singleton.set(null, config);
		try {
			File dir = new File(temporary.getRoot(), "export-jobs/7"); assertTrue(dir.mkdirs());
			Files.writeString(new File(dir, "raw-data.csv").toPath(), "intermediate data");
			Files.writeString(new File(dir, "output.csv").toPath(), "Id\n00123\n");
			File excel = new File(dir, "output.xlsx");
			ExcelExport.convertCsv(new File(dir, "output.csv"), excel, ',', true);
			File attachment = new File(dir, "files/original.csv"); assertTrue(attachment.getParentFile().mkdirs());
			byte[] original = "original,attachment\n1,2\n".getBytes(); Files.write(attachment.toPath(), original);
			ExportJob job = new ExportJob(); job.setId(7L); job.param("fileFormat", "XLSX");
			ExportServiceImpl service = new ExportServiceImpl();
			invoke(service, "generateOutputZip", job);
			File zip = new File(dir, "output.zip");
			try (ZipFile archive = new ZipFile(zip)) {
				assertNull(archive.getEntry("raw-data.csv")); assertNull(archive.getEntry("output.csv"));
				assertNotNull(archive.getEntry("output.xlsx"));
				assertArrayEquals(original, archive.getInputStream(archive.getEntry("files/original.csv")).readAllBytes());
			}
			invoke(service, "cleanupFiles", job);
			assertTrue(zip.exists()); assertFalse(excel.exists());
			assertEquals(zip.getAbsolutePath(), invoke(service, "getOutputFile", job));
			File directDir = new File(temporary.getRoot(), "export-jobs/8"); assertTrue(directDir.mkdirs());
			job.setId(8L);
			File direct = new File(directDir, "output.xlsx"); Files.write(direct.toPath(), new byte[]{1, 2});
			invoke(service, "cleanupFiles", job);
			assertTrue(direct.exists()); assertEquals(direct.getAbsolutePath(), invoke(service, "getOutputFile", job));
		} finally { singleton.set(null, previous); }
	}

	@Test
	public void commonDownloadUsesExcelMimeAndExtensionAfterTheExistingServiceAccessCheck() throws Exception {
		File file = temporary.newFile("records.xlsx"); Files.write(file.toPath(), new byte[]{1, 2, 3});
		ExportJobsController controller = new ExportJobsController();
		int[] checks = {0};
		set(controller, "exportSvc", proxy(ExportService.class, (name, args) -> {
			assertEquals("getExportFile", name); assertEquals(12L, ((RequestEvent<?>) args[0]).getPayload());
			++checks[0]; return ResponseEvent.response(file.getAbsolutePath());
		}));
		Response response = new Response(); controller.getExportJobOutputFile(12L, response.proxy());
		assertEquals(1, checks[0]); assertEquals(ExcelExport.CONTENT_TYPE, response.type);
		assertTrue(response.disposition.endsWith("ExportJob_12.xlsx")); assertArrayEquals(new byte[]{1, 2, 3}, response.body.toByteArray());
	}

	@Test
	public void queryDownloadUsesRealExcelFilenameAndCsvKeepsItsZipDownload() throws Exception {
		QueryController controller = new QueryController();
		for (String extension : new String[]{"xlsx", "zip"}) {
			File file = temporary.newFile("results." + extension); Files.write(file.toPath(), new byte[]{1, 2, 3});
			set(controller, "querySvc", proxy(QueryService.class, (name, args) -> {
				assertEquals("getExportDataFile", name); return ResponseEvent.response(file);
			}));
			Response response = new Response(); controller.downloadExportDataFile("authorized-id", "QueryResults.zip", response.proxy());
			assertTrue(response.disposition.contains("." + extension));
			if (extension.equals("xlsx")) assertEquals(ExcelExport.CONTENT_TYPE, response.type);
			assertArrayEquals(new byte[]{1, 2, 3}, response.body.toByteArray());
		}
	}

	@Test
	public void deniedDownloadsDoNotWriteAnyFileContents() throws Exception {
		ExportJobsController jobs = new ExportJobsController();
		set(jobs, "exportSvc", proxy(ExportService.class, (name, args) -> ResponseEvent.userError(RbacErrorCode.ACCESS_DENIED)));
		QueryController queries = new QueryController();
		set(queries, "querySvc", proxy(QueryService.class, (name, args) -> ResponseEvent.userError(RbacErrorCode.ACCESS_DENIED)));
		Response response = new Response();
		try { jobs.getExportJobOutputFile(1L, response.proxy()); fail("Denied export downloaded"); }
		catch (OpenSpecimenException expected) { assertEquals(0, response.body.size()); }
		try { queries.downloadExportDataFile("id", "QueryResults.zip", response.proxy()); fail("Denied query downloaded"); }
		catch (OpenSpecimenException expected) { assertEquals(0, response.body.size()); }
	}

	private interface Call { Object apply(String method, Object[] args) throws Throwable; }
	private static <T> T proxy(Class<T> type, Call call) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, args) -> call.apply(m.getName(), args)));
	}
	private static void set(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
	}
	private static Object invoke(Object target, String name, ExportJob job) throws Exception {
		Method method = target.getClass().getDeclaredMethod(name, ExportJob.class); method.setAccessible(true); return method.invoke(target, job);
	}
	private static class Response {
		String type, disposition; ByteArrayOutputStream body = new ByteArrayOutputStream();
		HttpServletResponse proxy() {
			return ExcelExportIntegrationTest.proxy(HttpServletResponse.class, (name, args) -> {
				if (name.equals("setContentType")) type = (String) args[0];
				if ((name.equals("setHeader") || name.equals("addHeader")) && args[0].equals("Content-Disposition")) disposition = (String) args[1];
				if (name.equals("getOutputStream")) return new ServletOutputStream() {
					public boolean isReady() { return true; }
					public void setWriteListener(WriteListener listener) { }
					public void write(int value) { body.write(value); }
				};
				return null;
			});
		}
	}
}
