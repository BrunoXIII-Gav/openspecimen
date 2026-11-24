package com.krishagni.catissueplus.core.common.repository.impl;

import java.util.List;

import com.krishagni.catissueplus.core.common.domain.KeySequence;
import com.krishagni.catissueplus.core.common.repository.AbstractDao;
import com.krishagni.catissueplus.core.common.repository.UniqueIdGenerator;

public class UniqueIdGeneratorImpl extends AbstractDao<KeySequence> implements UniqueIdGenerator {
	@Override
	public Long getUniqueId(String type, String id) {
		return getUniqueId(type, id, 0L);
	}

	@Override
	public Long getUniqueId(String type, String id, Long defStartSeq) {
		return getUniqueId(type, id, defStartSeq, 1);
	}

	@Override
	public Long getUniqueId(String type, String id, Long defStartSeq, int incrementBy) {
		List<KeySequence> seqs = getSequences(type, id, true);

		KeySequence seq;
		if (seqs.isEmpty()) {
			seq = new KeySequence();
			seq.setType(type);
			seq.setTypeId(id);
			seq.setSequence(defStartSeq);
		} else {
			seq = seqs.iterator().next();
		}

		Long uniqueId = seq.increment();
		seq.incrementBy(incrementBy >= 1 ? incrementBy - 1 : 0);
		if (seq.getId() == null) {
			getCurrentSession().persist(seq);
		} else {
			getCurrentSession().merge(seq);
		}

		return uniqueId;
	}

	@Override
	public Long getNextUniqueId(String type, String id, Long defStartSeq) {
		List<KeySequence> seqs = getSequences(type, id, false);
		if (seqs.isEmpty()) {
			return Math.max(1L, defStartSeq == null ? 1L : defStartSeq);
		}

		return seqs.iterator().next().getSequence() + 1;
	}

	@Override
	public boolean setNextUniqueId(String type, String id, Long nextUniqueId) {
		if (nextUniqueId == null || nextUniqueId < 1) {
			return false;
		}

		List<KeySequence> seqs = getSequences(type, id, true);
		if (seqs.isEmpty()) {
			KeySequence seq = new KeySequence();
			seq.setType(type);
			seq.setTypeId(id);
			seq.setSequence(nextUniqueId - 1);
			getCurrentSession().persist(seq);
			return true;
		}

		KeySequence seq = seqs.iterator().next();
		if (nextUniqueId <= seq.getSequence()) {
			return false;
		}

		seq.setSequence(nextUniqueId - 1);
		getCurrentSession().merge(seq);
		return true;
	}

	private List<KeySequence> getSequences(String type, String id, boolean lock) {
		var query = createNamedQuery(GET_BY_TYPE_AND_TYPE_ID, KeySequence.class)
			.setParameter("type", type)
			.setParameter("typeId", id);

		if (lock) {
			query.acquirePessimisticWriteLock("ks");
		}

		return query.list();
	}

	private static final String FQN = KeySequence.class.getName();
	
	private static final String GET_BY_TYPE_AND_TYPE_ID = FQN + ".getByTypeAndTypeId";
}
