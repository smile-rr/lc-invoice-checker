package com.lc.checker.pipeline.stages;

import com.lc.checker.pipeline.Stage;
import com.lc.checker.pipeline.StageContext;
import org.springframework.stereotype.Component;

/**
 * Stage 4 — Holistic Sweep (Layer 3) per {@code logic-flow.md}. Two LLM passes
 * over the full LC + invoice text to surface UCP/ISBP edge cases and LC-specific
 * conditions the rule catalog couldn't anticipate.
 *
 * <p>V1: design ready, executor deferred. This stage is a no-op so the slot is
 * visible in the pipeline order and emits no events. When the executor lands it
 * will populate {@code ctx.checkResults} with {@code REQUIRES_HUMAN_REVIEW} entries.
 */
@Component
public class HolisticSweepStage implements Stage {

    @Override
    public String name() {
        return "holistic_sweep";
    }

    @Override
    public void execute(StageContext ctx) {
        // intentionally blank — see class Javadoc.
    }
}
