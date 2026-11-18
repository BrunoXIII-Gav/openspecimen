package com.krishagni.rbac.events;

import java.util.LinkedHashSet;
import java.util.Set;

/** An allow-list for a role in one protocol. -1 means all assigned protocols. */
public class ProtocolContentPolicy {

	private Long cpId;

	private Set<String> allowed = new LinkedHashSet<>();
	// Omitted in existing roles: preserve their original creation permissions.
	private Set<String> createLineages;

	public Set<String> getCreateLineages() { return createLineages; }

	public void setCreateLineages(Set<String> createLineages) { this.createLineages = createLineages; }

	public Long getCpId() { return cpId; }

	public void setCpId(Long cpId) { this.cpId = cpId; }

	public Set<String> getAllowed() { return allowed; }

	public void setAllowed(Set<String> allowed) { this.allowed = allowed; }
}
