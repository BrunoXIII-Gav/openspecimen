package com.krishagni.catissueplus.core.common.util;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.junit.Test;
import org.w3c.dom.Element;

import com.krishagni.catissueplus.core.common.access.ProtocolQueryAccess;

import edu.common.dynamicextensions.nutility.ContainerParser;
import edu.common.dynamicextensions.domain.nui.Container;
import edu.common.dynamicextensions.domain.nui.SubFormControl;
import edu.common.dynamicextensions.domain.nui.factory.ControlManager;
import edu.common.dynamicextensions.query.Query;
import edu.common.dynamicextensions.query.QuerySpace;
import edu.common.dynamicextensions.query.WideRowMode;
import edu.common.dynamicextensions.ndao.DbSettingsFactory;
import com.krishagni.catissueplus.core.de.ui.PvControlFactory;
import com.krishagni.catissueplus.core.de.ui.UserControlFactory;
import com.krishagni.catissueplus.core.de.ui.SiteControlFactory;
import com.krishagni.catissueplus.core.de.ui.StorageContainerControlFactory;

import static org.junit.Assert.*;

public class ConsentTrackingQueryFormTest {
  @Test
  public void consentStatusFieldsRenderAndParseInBothLanguages() throws Exception {
    ControlManager.getInstance().registerFactory(PvControlFactory.getInstance());
    ControlManager.getInstance().registerFactory(UserControlFactory.getInstance());
    ControlManager.getInstance().registerFactory(SiteControlFactory.getInstance());
    ControlManager.getInstance().registerFactory(StorageContainerControlFactory.getInstance());
    String source = Files.readString(Path.of("WEB-INF/resources/query-forms/cpr.xml"));
    for (String language : new String[] {"es", "en"}) {
      VelocityContext context = new VelocityContext();
      context.put("locale", Locale.forLanguageTag(language));
      context.put("messageSource", new Messages());
      context.put("cfgSvc", new Settings());
      StringWriter result = new StringWriter();
      new VelocityEngine().evaluate(context, result, "consent-query-test", source);
      byte[] xml = result.toString().getBytes(StandardCharsets.UTF_8);
      var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(xml));
      var columns = document.getElementsByTagName("column");
      boolean found = false;
      for (int i = 0; i < columns.getLength(); ++i) {
        Element column = (Element) columns.item(i);
        if (column.getTextContent().equals(language.equals("es") ? "STATUS_ES" : "STATUS")) found = true;
        assertFalse(column.getTextContent().contains("$consentStatusColumn"));
      }
      assertTrue(found);
      Container participant = new ContainerParser(new ByteArrayInputStream(xml)).parse();
      SubFormControl tracking = (SubFormControl) participant.getControl("consentTracking");
      assertEquals("CPR_ID", tracking.getSubContainer().getPrimaryKey());

      QuerySpace space = new QuerySpace();
      space.setRootForm("Participant");
      space.setForms(Map.of("Participant", participant));
      var originalSettings = DbSettingsFactory.instance;
      try {
        DbSettingsFactory.init("MySQL");
        for (WideRowMode mode : new WideRowMode[] {WideRowMode.OFF, WideRowMode.DEEP}) {
          Query query = Query.createQuery().querySpace(space).wideRowMode(mode);
          query.compile("Participant", "select Participant.empi, Participant.consentTracking.status " +
            "where Participant.consentTracking.pending = 1");
          // The public getDataSql() disables wide-row markers. Use the same overload
          // as getData() so this test covers the hidden subform key in the results.
          var dataSql = Query.class.getDeclaredMethod("getDataSql", boolean.class, int.class, int.class);
          dataSql.setAccessible(true);
          String sql = (String) dataSql.invoke(query, mode != WideRowMode.OFF, 0, 0);
          assertTrue(sql.contains("OS_CPR_CONSENT_STATUS_V"));
          assertFalse("The status view has CPR_ID, not IDENTIFIER: " + sql, sql.contains(".IDENTIFIER"));
          Path sqlFile = Path.of("build", "tmp", "consent-tracking-" + language + "-" + mode + ".sql");
          Files.createDirectories(sqlFile.getParent());
          Files.writeString(sqlFile, sql);
        }
      } finally {
        DbSettingsFactory.instance = originalSettings;
      }
    }
  }

  @Test
  public void consentStatusCanBeUsedInNativeQueryFiltersWithoutOpeningCustomFields() {
    assertTrue(ProtocolQueryAccess.permits(
      "select Participant.empi, Participant.consentTracking.status where Participant.consentTracking.pending = true",
      ProtocolQueryAccess::isNativeField));
    assertFalse(ProtocolQueryAccess.isNativeField("Participant.consentTracking.privateField"));
  }

  public static class Messages {
    public String getMessage(String key, Object[] arguments, Locale locale) { return key; }
  }

  public static class Settings {
    public int getIntSetting(String module, String key, int defaultValue) { return defaultValue; }
  }
}
