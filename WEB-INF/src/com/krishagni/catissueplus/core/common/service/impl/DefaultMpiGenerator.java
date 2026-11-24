package com.krishagni.catissueplus.core.common.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;

import com.krishagni.catissueplus.core.biospecimen.repository.DaoFactory;
import com.krishagni.catissueplus.core.common.service.MpiGenerator;

@Configurable
public class DefaultMpiGenerator implements MpiGenerator {

	@Autowired
	private DaoFactory daoFactory;
	
	private String mpiFormat;

	private long mpiStartNumber;
	
	public DefaultMpiGenerator(String mpiFormat) {
		this(mpiFormat, 1);
	}

	public DefaultMpiGenerator(String mpiFormat, long mpiStartNumber) {
		this.mpiFormat = mpiFormat;
		this.mpiStartNumber = Math.max(1, mpiStartNumber);
	}
	
	@Override
	public String generateMpi() {
		Long uniqueId = daoFactory.getUniqueIdGenerator().getUniqueId("MPI", "MPI", mpiStartNumber - 1);
		return String.format(mpiFormat, uniqueId.intValue());
	}

}
