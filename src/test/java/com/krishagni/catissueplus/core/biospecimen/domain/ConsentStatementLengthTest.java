package com.krishagni.catissueplus.core.biospecimen.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.krishagni.catissueplus.core.biospecimen.domain.factory.ConsentStatementErrorCode;
import com.krishagni.catissueplus.core.biospecimen.domain.factory.impl.ConsentStatementFactoryImpl;
import com.krishagni.catissueplus.core.biospecimen.events.ConsentStatementDetail;
import com.krishagni.catissueplus.core.common.errors.OpenSpecimenException;

public class ConsentStatementLengthTest {
	@Test
	public void acceptsAndPreservesLongMultilineStatements() {
		String prefix = "Autorización para investigación.\n";
		String statement = prefix + "á".repeat(ConsentStatement.MAX_STATEMENT_LENGTH - prefix.length());
		assertEquals(statement, create(statement).getStatement());
	}

	@Test
	public void acceptsExactly4000Characters() {
		String statement = "á".repeat(4000);
		assertEquals(statement, create(statement).getStatement());
	}

	@Test
	public void rejectsTooLongStatementsWithoutTruncating() {
		assertRejected("x".repeat(4001), ConsentStatementErrorCode.TOO_LONG);
	}

	@Test
	public void continuesToRequireANonblankStatement() {
		assertRejected(" \n\t ", ConsentStatementErrorCode.REQUIRED);
	}

	private ConsentStatement create(String statement) {
		ConsentStatementDetail input = new ConsentStatementDetail();
		input.setCode("CI017");
		input.setStatement(statement);
		return new ConsentStatementFactoryImpl().createStatement(input);
	}

	private void assertRejected(String statement, ConsentStatementErrorCode code) {
		try {
			create(statement);
			fail("Expected validation error " + code);
		} catch (OpenSpecimenException error) {
			assertTrue(error.containsError(code));
		}
	}
}
