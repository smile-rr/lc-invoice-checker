package com.lc.checker.pipeline;

/**
 * One step of the LC checking pipeline. The order in which stages run is declared
 * in {@link LcCheckPipeline} as a literal {@code List.of(...)} — read that file to
 * see the flow at a glance.
 *
 * <p>Stage names: {@code lc_parse}, {@code invoice_extract}, {@code lc_check},
 * {@code report_assembly}. Anything that emits events, persists a step row, or
 * queries traces relies on those literal strings. Sub-phases inside
 * {@code lc_check} (parties / money / goods / logistics / procedural / holistic)
 * surface via {@code PHASE_*} events on the SSE stream and via
 * {@code step_key="phase:<name>"} rows in {@code pipeline_steps}.
 *
 * <p>Each stage owns its own try/catch. If it throws, the runner stops the loop —
 * the stage has already finalized the session row (or, for {@code lc_parse}, has
 * decided not to because no session row exists yet).
 */
public interface Stage {

    String name();

    void execute(StageContext ctx);
}
