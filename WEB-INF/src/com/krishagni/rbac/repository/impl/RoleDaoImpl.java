package com.krishagni.rbac.repository.impl;

import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.krishagni.catissueplus.core.common.repository.AbstractDao;
import com.krishagni.catissueplus.core.common.repository.Criteria;
import com.krishagni.rbac.domain.Role;
import com.krishagni.rbac.repository.RoleDao;
import com.krishagni.rbac.repository.RoleListCriteria;

public class RoleDaoImpl extends AbstractDao<Role> implements RoleDao {
	
	@Override
	public List<Role> getRoles(RoleListCriteria listCriteria) {
		Criteria<Role> query = createCriteria(Role.class, "r");
		if (StringUtils.isNotBlank(listCriteria.query())) {
			query.add(query.ilike("r.name", listCriteria.query()));
		}
		
		return query.orderBy(query.asc("r.name")).list(listCriteria.startAt(), listCriteria.maxResults());
	}

	@Override
	public Long getRolesCount(RoleListCriteria listCriteria) {
		Criteria<Role> query = createCriteria(Role.class, "r");
		if (StringUtils.isNotBlank(listCriteria.query())) {
			query.add(query.ilike("r.name", listCriteria.query()));
		}

		return query.getCount("r.id");
	}

	@Override
	public List<Role> getRolesByNames(List<String> roleNames) {
		return createNamedQuery(GET_ROLES_BY_NAMES, Role.class)
			.setParameterList("roleNames", roleNames)
			.list();
	}
	
	@Override
	public Role getRoleByName(String roleName) {
		List<Role> roles = getRolesByNames(Collections.singletonList(roleName));
		return roles.isEmpty() ? null : roles.get(0);
	}
	
	@Override
	public Class<?> getType() {
		return Role.class;
	}

	@Override
	public List<Role> getProtocolContentRoles(Long userId, Long cpId, Long instituteId) {
		// Use the same subject-role assignments as the existing RBAC access view.
		return createQuery("select sr.role from com.krishagni.rbac.domain.SubjectRole sr " +
			"where sr.subject.id = :userId and (sr.collectionProtocol.id = :cpId or sr.collectionProtocol is null) " +
			"and (sr.site.id in (select cps.site.id from com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocolSite cps " +
			"where cps.collectionProtocol.id = :cpId) or (sr.site is null and " +
			"(sr.collectionProtocol is not null or exists (select s.id from com.krishagni.catissueplus.core.administrative.domain.Site s " +
			"where s.institute.id = :instituteId and s.id in (select cs.site.id from " +
			"com.krishagni.catissueplus.core.biospecimen.domain.CollectionProtocolSite cs where cs.collectionProtocol.id = :cpId)))))", Role.class)
			.setParameter("userId", userId).setParameter("cpId", cpId).setParameter("instituteId", instituteId).list();
	}

	@Override
	public boolean hasContentPolicies(Long userId) {
		// Oracle does not support DISTINCT or <> comparisons on a CLOB.
		return createQuery("select sr.role from com.krishagni.rbac.domain.SubjectRole sr where sr.subject.id = :userId " +
			"and sr.role.contentPoliciesJson is not null", Role.class)
			.setParameter("userId", userId).list().stream()
			.anyMatch(role -> !com.krishagni.catissueplus.core.common.access.ProtocolContentAccess.policies(role).isEmpty());
	}
	
	private static final String FQN = Role.class.getName();
	
	private static final String GET_ROLES_BY_NAMES = FQN + ".getRolesByNames";
}
