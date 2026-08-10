package com.solovis.entitlement.service.ask;

import java.time.LocalDate;

/**
 * The one seam to the external language model. Implementations receive exactly three things —
 * the question, the capability catalogue, and today's date — which is what makes spec §4's
 * confinement structural: an interpreter cannot transmit what it is never given. Today's date is
 * the one fact required to turn "last month" into a particular day, and is deliberately the
 * least revealing thing the service holds (spec §4).
 */
public interface QuestionInterpreter {

	/**
	 * @throws AskUnavailableException when the language service cannot be reached or answers
	 *                                 unusably; the caller degrades to 503, never to a guess
	 */
	Proposal interpret(String question, CapabilityCatalog catalog, LocalDate today);
}
