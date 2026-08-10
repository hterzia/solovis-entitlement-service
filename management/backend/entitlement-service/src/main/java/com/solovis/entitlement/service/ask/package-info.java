/**
 * Plain-English checker (003): interprets a typed operator question into one (account, capability)
 * pair via an external language model, verifies the proposal against local records, and answers
 * through the classic checker. The interpreter receives only the question text and the active
 * capability catalogue — never values, decisions, traces, reasons, or the account roster — and
 * never sees the outcome of the question it interpreted.
 */
package com.solovis.entitlement.service.ask;
