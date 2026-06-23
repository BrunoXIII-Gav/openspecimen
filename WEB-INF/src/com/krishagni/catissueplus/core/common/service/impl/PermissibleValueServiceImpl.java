
package com.krishagni.catissueplus.core.common.service.impl;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.krishagni.catissueplus.core.administrative.domain.PermissibleValue;
import com.krishagni.catissueplus.core.administrative.domain.factory.PvErrorCode;
import com.krishagni.catissueplus.core.administrative.events.ListPvCriteria;
import com.krishagni.catissueplus.core.administrative.events.PvAttributeSummary;
import com.krishagni.catissueplus.core.administrative.events.PvDetail;
import com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory;
import com.krishagni.catissueplus.core.common.PlusTransactional;
import com.krishagni.catissueplus.core.common.errors.ErrorType;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;
import com.krishagni.catissueplus.core.common.events.EntityQueryCriteria;
import com.krishagni.catissueplus.core.common.events.RequestEvent;
import com.krishagni.catissueplus.core.common.events.ResponseEvent;
import com.krishagni.catissueplus.core.common.service.PermissibleValueService;

public class PermissibleValueServiceImpl implements PermissibleValueService {
	private DaoFactory daoFactory;

	public void setDaoFactory(DaoFactory daoFactory) {
		this.daoFactory = daoFactory;
	}

	@Override
	@PlusTransactional
	public ResponseEvent<List<PvAttributeSummary>> getPvAttributes(RequestEvent<ListPvCriteria> req) {
		String activityStatus = req.getPayload().activityStatus();
		return ResponseEvent.response(daoFactory.getPermissibleValueDao().getAttributes(activityStatus));
	}

	@Override
	@PlusTransactional
	public ResponseEvent<List<PvDetail>> getPermissibleValues(RequestEvent<ListPvCriteria> req) {
		ListPvCriteria crit = req.getPayload();
		List<PermissibleValue> pvs = daoFactory.getPermissibleValueDao().getPvs(crit);
		return ResponseEvent.response(PvDetail.from(pvs, crit.includeParentValue(), crit.includeProps()));
	}

	@Override
	@PlusTransactional
	public ResponseEvent<Long> getPermissibleValuesCount(RequestEvent<ListPvCriteria> req) {
		return ResponseEvent.response(daoFactory.getPermissibleValueDao().getPvsCount(req.getPayload()));
	}

	@Override
	@PlusTransactional
	public ResponseEvent<PvDetail> getPermissibleValue(RequestEvent<EntityQueryCriteria> req) {
		EntityQueryCriteria crit = req.getPayload();
		PermissibleValue value = null;
		Object key = null;
		if (crit.getId() != null) {
			value = daoFactory.getPermissibleValueDao().getById(crit.getId());
			key = crit.getId();
		} else if (StringUtils.isNotBlank(crit.getName()) && StringUtils.isNotBlank(crit.paramString("attribute"))) {
			value = daoFactory.getPermissibleValueDao().getByValue(crit.paramString("attribute"), crit.getName());
			key = crit.paramString("attribute") + ": " + crit.getName();
		}

		if (key == null) {
			throw OpenSpecimenException.userError(PvErrorCode.VALUE_REQUIRED);
		} else if (value == null) {
			throw OpenSpecimenException.userError(PvErrorCode.NOT_FOUND, key);
		}

		boolean includeProps = Boolean.TRUE.equals(crit.paramBoolean("includeProps"));
		return ResponseEvent.response(PvDetail.from(value, includeProps, includeProps));
	}

	@Override
	@PlusTransactional
	public ResponseEvent<PvDetail> createPv(RequestEvent<PvDetail> req) {
		try {
			PvDetail detail = req.getPayload();
			PermissibleValue pv = buildPvFromDetail(detail);

			OpenSpecimenException ose = new OpenSpecimenException(ErrorType.USER_ERROR);
			ensureRequiredFields(pv, ose);
			ensureUniqueValue(pv, null, ose);
			ose.checkAndThrow();

			daoFactory.getPermissibleValueDao().saveOrUpdate(pv, true);
			return ResponseEvent.response(PvDetail.from(pv, true, true));
		} catch (OpenSpecimenException ose) {
			return ResponseEvent.error(ose);
		} catch (Exception e) {
			return ResponseEvent.serverError(e);
		}
	}

	@Override
	@PlusTransactional
	public ResponseEvent<PvDetail> updatePv(RequestEvent<PvDetail> req) {
		try {
			PvDetail detail = req.getPayload();
			PermissibleValue existing = daoFactory.getPermissibleValueDao().getById(detail.getId());
			if (existing == null) {
				return ResponseEvent.userError(PvErrorCode.NOT_FOUND, detail.getId());
			}

			PermissibleValue pv = buildPvFromDetail(detail);

			OpenSpecimenException ose = new OpenSpecimenException(ErrorType.USER_ERROR);
			ensureRequiredFields(pv, ose);
			ensureUniqueValue(pv, existing, ose);
			ose.checkAndThrow();

			existing.update(pv);
			return ResponseEvent.response(PvDetail.from(existing, true, true));
		} catch (OpenSpecimenException ose) {
			return ResponseEvent.error(ose);
		} catch (Exception e) {
			return ResponseEvent.serverError(e);
		}
	}

	@Override
	@PlusTransactional
	public ResponseEvent<PvDetail> deletePv(RequestEvent<Long> req) {
		try {
			PermissibleValue pv = daoFactory.getPermissibleValueDao().getById(req.getPayload());
			if (pv == null) {
				return ResponseEvent.userError(PvErrorCode.NOT_FOUND, req.getPayload());
			}

			if (!pv.getChildren().isEmpty()) {
				return ResponseEvent.userError(PvErrorCode.IN_USE);
			}

			daoFactory.getPermissibleValueDao().delete(pv);
			return ResponseEvent.response(PvDetail.from(pv));
		} catch (OpenSpecimenException ose) {
			return ResponseEvent.error(ose);
		} catch (Exception e) {
			return ResponseEvent.serverError(e);
		}
	}

	private PermissibleValue buildPvFromDetail(PvDetail detail) {
		PermissibleValue pv = new PermissibleValue();
		pv.setAttribute(detail.getAttribute());
		pv.setValue(detail.getValue());
		pv.setConceptCode(detail.getConceptCode());
		pv.setLabelCode(detail.getLabelCode());
		pv.setActivityStatus(detail.getActivityStatus() != null ? detail.getActivityStatus() : "Active");

		Map<String, String> props = detail.getProps();
		if (props != null) {
			pv.setProps(props);
		}

		if (detail.getParentId() != null) {
			PermissibleValue parent = daoFactory.getPermissibleValueDao().getById(detail.getParentId());
			if (parent == null) {
				throw OpenSpecimenException.userError(PvErrorCode.PARENT_ATTR_NOT_FOUND, detail.getParentId());
			}
			pv.setParent(parent);
		} else if (StringUtils.isNotBlank(detail.getParentValue()) && StringUtils.isNotBlank(detail.getAttribute())) {
			PermissibleValue parent = daoFactory.getPermissibleValueDao().getByValue(detail.getAttribute(), detail.getParentValue());
			if (parent != null) {
				pv.setParent(parent);
			}
		}

		return pv;
	}

	private void ensureRequiredFields(PermissibleValue pv, OpenSpecimenException ose) {
		if (StringUtils.isBlank(pv.getAttribute())) {
			ose.addError(PvErrorCode.ATTR_NAME_REQUIRED);
		}
		if (StringUtils.isBlank(pv.getValue())) {
			ose.addError(PvErrorCode.VALUE_REQUIRED);
		}
	}

	private void ensureUniqueValue(PermissibleValue pv, PermissibleValue existing, OpenSpecimenException ose) {
		if (existing != null
				&& pv.getValue().equals(existing.getValue())
				&& pv.getAttribute().equals(existing.getAttribute())) {
			return;
		}
		PermissibleValue dup = daoFactory.getPermissibleValueDao().getByValue(pv.getAttribute(), pv.getValue());
		if (dup != null) {
			ose.addError(PvErrorCode.DUP_VALUE, pv.getValue());
		}
	}
}
