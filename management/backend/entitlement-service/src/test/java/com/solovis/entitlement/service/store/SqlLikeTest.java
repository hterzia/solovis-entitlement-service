package com.solovis.entitlement.service.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlLikeTest {

	@Test
	void leavesPlainTextUnescaped() {
		assertThat(SqlLike.contains("cotton")).isEqualTo("%cotton%");
	}

	@Test
	void escapesPercentSoItIsALiteralCharacter() {
		assertThat(SqlLike.contains("100%")).isEqualTo("%100\\%%");
	}

	@Test
	void escapesUnderscoreSoItIsALiteralCharacter() {
		assertThat(SqlLike.contains("_otton")).isEqualTo("%\\_otton%");
	}

	@Test
	void escapesBackslashFirstSoItDoesNotDoubleEscapeSubsequentEscapes() {
		assertThat(SqlLike.contains("a\\b")).isEqualTo("%a\\\\b%");
	}
}
