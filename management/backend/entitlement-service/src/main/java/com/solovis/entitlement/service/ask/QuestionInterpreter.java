package com.solovis.entitlement.service.ask;

/**
 * The one seam to the external language model. Implementations receive exactly two things —
 * the question and the active-capability catalogue — which is what makes spec §4's confinement
 * structural: an interpreter cannot transmit what it is never given.
 */
public interface QuestionInterpreter {

	/**
	 * @throws AskUnavailableException when the language service cannot be reached or answers
	 *                                 unusably; the caller degrades to 503, never to a guess
	 */
	Proposal interpret(String question, CapabilityCatalog catalog);
}
