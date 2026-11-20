package io.github.mangi.eta.agent.runtime

/** Platform revisions disagree on colorization. Query the actual payload contract instead
 * of guessing by SDK, manufacturer or hidden feature flags. No eligibility is fabricated. */
internal fun liveUpdateRequiresColorization(isEligible: (Boolean) -> Boolean): Boolean =
    if (isEligible(false)) false else isEligible(true)
