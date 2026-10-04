package com.privacypilot.llm;

import java.util.Optional;

/**
 * Thin abstraction over an LLM chat/completion API.
 *
 * <p>The client is responsible only for transport: given a system instruction
 * and a user prompt, it returns the model's raw text response (which callers
 * expect to be JSON). It performs NO parsing or validation — that is the job of
 * the calling AI service, which treats the returned text as untrusted.
 *
 * <p>When no API key is configured (offline mode) or the call fails, the client
 * returns {@link Optional#empty()} so callers can fall back gracefully rather
 * than crash.
 */
public interface LlmClient {

    /**
     * @param systemInstruction high-level instruction (role/behavior)
     * @param userPrompt        the concrete task prompt
     * @return raw model text if a response was obtained, otherwise empty
     */
    Optional<String> complete(String systemInstruction, String userPrompt);

    /** @return true if the client cannot actually reach a model (no key configured). */
    boolean isOffline();
}
