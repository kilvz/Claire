package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.model.ChatModel

/** The provider and model triage will actually use (a lightweight model, not the main one); [providerName] is only for the UI to say which provider it is. */
data class TriageTarget(val providerId: String, val providerName: String, val model: String)

/**
 * Which kind of background model request this is. Reply lengths vary widely: triage and topic learning return a few short markers, while segmented, daily, weekly and monthly digests return
 * a dozen or so sentences of a hundred-plus characters each ([long]). This decides the reply length limit when building the model; one limit cannot serve them all.
 */
enum class ProactiveCall(val long: Boolean) {
    Triage(false), Learn(false), Notes(true), Day(true), Span(true),
}

/** A built model together with the provider and model it uses: both come from the same config read, and consent is checked against this [target] before a request is sent. */
class TargetedModel(val target: TriageTarget, val model: ChatModel)

/** Which provider's which model the user agreed to send notification text to. Only the provider id and model name are recorded; no notification content is included. */
data class TriageConsent(val providerId: String, val model: String) {
    /** Whether the [target] actually used is the one agreed to; does not count when there is no model. */
    fun covers(target: TriageTarget?): Boolean = target != null && target.providerId == providerId && target.model == model

    companion object {
        fun of(target: TriageTarget) = TriageConsent(target.providerId, target.model)

        /**
         * How consent changes when the master switch changes. Turning it off (or the feature being unavailable in this version) clears it: turning it back on requires confirming again, and the previous choice is not reused.
         * When turning on and the user agreed in the confirmation dialog, that is recorded ([confirmed] is the provider and model shown in the dialog);
         * when turning on with nothing to confirm (no model configured yet) [current] is kept — once a model is configured, it is confirmed on the page.
         */
        fun afterSwitch(on: Boolean, confirmed: TriageTarget?, current: TriageConsent?): TriageConsent? = when {
            !on -> null
            confirmed != null -> of(confirmed)
            else -> current
        }
    }
}

/** Whether background model requests (triage and learning) can be sent right now. */
enum class TriageGate {
    Off,
    /** On, but no model configured. */
    NoModel,
    /** On, but the provider or model actually used differs from the one agreed to: hold off until the user confirms again. */
    NeedsConsent,
    Ready;

    companion object {
        fun of(on: Boolean, consent: TriageConsent?, target: TriageTarget?): TriageGate = when {
            !on -> Off
            target == null -> NoModel
            consent?.covers(target) != true -> NeedsConsent
            else -> Ready
        }
    }
}
