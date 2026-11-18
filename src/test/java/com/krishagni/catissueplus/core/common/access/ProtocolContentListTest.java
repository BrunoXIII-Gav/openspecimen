package com.krishagni.catissueplus.core.common.access;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krishagni.catissueplus.core.biospecimen.repository.impl.BiospecimenDaoHelper;
import com.krishagni.catissueplus.core.common.events.RequestEvent;
import com.krishagni.catissueplus.core.query.Column;
import com.krishagni.catissueplus.core.query.DefaultListGenerator;
import com.krishagni.catissueplus.core.query.DefaultListService;
import com.krishagni.catissueplus.core.query.ListConfig;
import com.krishagni.catissueplus.core.query.ListDetail;
import com.krishagni.catissueplus.core.query.ListGenerator;
import static org.junit.Assert.*;

public class ProtocolContentListTest {

	private final ProtocolContentDataFlowTest fixture = new ProtocolContentDataFlowTest();

	@Before public void setup() throws Exception { fixture.setup(); }
	@After public void cleanup() throws Exception { fixture.cleanup(); }

	@Test
	public void shippedProtocolListCanLoadDataAndCountWithARestrictedRole() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		var workflow = mapper.readTree(getClass().getResourceAsStream(
			"/com/krishagni/catissueplus/core/biospecimen/system-workflows.json"));
		ListConfig cfg = mapper.treeToValue(workflow.get(0).get("data"), ListConfig.class);
		cfg.setCpId(-1L); cfg.setDrivingForm("CollectionProtocol"); cfg.setDistinct(true);
		Column id = column("CollectionProtocol.id"); id.setCaption("cpId"); cfg.setPrimaryColumn(id);
		Column starred = column("CollectionProtocol.__userLabels.userId"); starred.setDirection("desc");
		cfg.setHiddenColumns(List.of(id, column("CollectionProtocol.catalogId"), starred));
		cfg.setOrderBy(List.of(starred, cfg.getOrderBy().get(0)));
		// Reproduce Pedro's actual assignment: all current and future sites, in CP 1.
		String sites = BiospecimenDaoHelper.getInstance().getSiteCpsCondAqlForCps(List.of(SiteCpPair.make(1L, null, 1L)));
		cfg.setRestriction("(CollectionProtocol.__userLabels.userId not exists or CollectionProtocol.__userLabels.userId = 5) and " + sites);
		DefaultListService service = new DefaultListService();
		service.registerListConfigurator("cp-list-view", params -> cfg);
		service.setListGenerator((ListGenerator) Proxy.newProxyInstance(ListGenerator.class.getClassLoader(),
			new Class<?>[] {ListGenerator.class}, (proxy, method, args) -> {
				ListConfig filtered = (ListConfig) args[0];
				assertEquals(cfg.getColumns().size(), filtered.getColumns().size());
				assertEquals(cfg.getFilters().size(), filtered.getFilters().size());
				boolean count = method.getName().equals("getListSize");
				var generator = new DefaultListGenerator();
				var build = DefaultListGenerator.class.getDeclaredMethod(count ? "getCountAql" : "getDataAql",
					count ? new Class<?>[] {ListConfig.class, String.class} : new Class<?>[] {ListConfig.class, String.class, Column.class});
				build.setAccessible(true);
				String criteria = "(" + filtered.getCriteria() + ") and (" + filtered.getRestriction() + ")";
				String aql = (String) (count ? build.invoke(generator, filtered, criteria) : build.invoke(generator, filtered, criteria, null));
				ProtocolContentAccess.getInstance().ensureQueryAllowed(filtered.getCpId(), null, aql);
				if (count) return 1;
				ListDetail result = new ListDetail(); result.setColumns(filtered.getColumns()); result.setRows(List.of()); return result;
			}));
		var req = RequestEvent.wrap(Map.<String, Object>of("listName", "cp-list-view"));
		var data = service.getList(req); data.throwErrorIfUnsuccessful();
		var count = service.getListSize(req); count.throwErrorIfUnsuccessful();
		assertEquals(Integer.valueOf(1), count.getPayload());
		assertEquals(3, data.getPayload().getColumns().size());
	}

	@Test
	public void restrictedColumnsAndFiltersDisappearWithoutChangingSharedConfiguration() {
		ListConfig cfg = new ListConfig(); cfg.setCpId(1L); cfg.setPrimaryColumn(column("Specimen.id"));
		var label = column("Specimen.label"); var collection = column("Specimen.spmnCollRecvDetails.collTime");
		var receipt = column("Specimen.spmnCollRecvDetails.recvTime"); var hidden = column("Specimen.extensions.Hidden.requiredField");
		cfg.setColumns(List.of(label, collection, receipt, hidden)); cfg.setFilters(cfg.getColumns());
		cfg.setHiddenColumns(List.of(receipt)); cfg.setOrderBy(List.of(receipt)); cfg.setFixedColumns(List.of(hidden));
		AtomicReference<ListConfig> used = new AtomicReference<>();
		DefaultListService service = new DefaultListService(); service.registerListConfigurator("specimens", params -> cfg);
		service.setListGenerator((ListGenerator) Proxy.newProxyInstance(ListGenerator.class.getClassLoader(),
			new Class<?>[] {ListGenerator.class}, (proxy, method, args) -> {
				ListConfig filtered = (ListConfig) args[0]; used.set(filtered); return filtered.getFilters();
			}));
		var result = service.getListCfg(RequestEvent.wrap(Map.of("listName", "specimens"))); result.throwErrorIfUnsuccessful();
		assertEquals(List.of(label, collection), used.get().getColumns());
		assertEquals(List.of(label, collection), used.get().getFilters());
		assertTrue(used.get().getHiddenColumns().isEmpty()); assertTrue(used.get().getOrderBy().isEmpty());
		assertTrue(used.get().getFixedColumns().isEmpty());
		assertEquals(4, cfg.getColumns().size()); assertEquals(4, cfg.getFilters().size());
	}

	private static Column column(String expression) {
		Column column = new Column(); column.setExpr(expression); return column;
	}
}
