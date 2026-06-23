package com.krishagni.catissueplus.core.administrative.events;

public class PvAttributeSummary {
	private String attribute;

	private long count;

	public PvAttributeSummary(String attribute, long count) {
		this.attribute = attribute;
		this.count = count;
	}

	public String getAttribute() {
		return attribute;
	}

	public void setAttribute(String attribute) {
		this.attribute = attribute;
	}

	public long getCount() {
		return count;
	}

	public void setCount(long count) {
		this.count = count;
	}
}
