package com.krishagni.catissueplus.core.common.repository;

public interface UniqueIdGenerator {
	Long getUniqueId(String type, String id);

	Long getUniqueId(String type, String id, Long defStartSeq);

	Long getUniqueId(String type, String id, Long defStartSeq, int incrementBy);

	Long getNextUniqueId(String type, String id, Long defStartSeq);

	/**
	 * Advances the sequence so that the next generated ID is {@code nextUniqueId}.
	 * The sequence is never moved backwards.
	 *
	 * @return true when the sequence was advanced; false when it already reached
	 *         or passed the requested number
	 */
	boolean setNextUniqueId(String type, String id, Long nextUniqueId);
}
