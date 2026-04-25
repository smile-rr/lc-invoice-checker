package com.lc.checker.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Tiny fluent builder for declaring the pipeline at the call site:
 *
 * <pre>
 * List&lt;Stage&gt; stages = Flow.start()
 *         .then(lcParse)         // Stage 1a
 *         .endHere()             // ← debug end-and-return: comment / uncomment as needed
 *         .then(invoiceExtract)  // Stage 1b
 *         .then(ruleActivation)  // Stage 2
 *         ...
 *         .build();
 * </pre>
 *
 * <p>{@link #endHere()} marks the end of the executed chain. Subsequent
 * {@code .then(...)} calls are dropped from the configured stages. The runner
 * detects the trimmed chain (no {@code report_assembly} stage), synthesises a
 * partial {@code DiscrepancyReport} via {@code earlyFinalize()}, finalises the
 * session row, emits a {@code REPORT_COMPLETE} event, and returns. There is no
 * hang and no holding — control returns to the HTTP caller as soon as the last
 * configured stage completes.
 *
 * <p>This is intentionally a debug-only mechanism — there is no runtime
 * config, env var, or yaml knob.
 */
public final class Flow {

    private Flow() {}

    public static Builder start() {
        return new Builder();
    }

    public static final class Builder {

        private final List<Stage> stages = new ArrayList<>();
        private boolean ended = false;

        private Builder() {}

        public Builder then(Stage stage) {
            if (!ended) stages.add(stage);
            return this;
        }

        /**
         * Debug end-and-return — subsequent {@code .then(...)} calls are dropped
         * from the configured stages, and the runner returns the partial result
         * via the {@code earlyFinalize()} synthesis path as soon as the last
         * configured stage completes.
         */
        public Builder endHere() {
            this.ended = true;
            return this;
        }

        public List<Stage> build() {
            return List.copyOf(stages);
        }
    }
}
