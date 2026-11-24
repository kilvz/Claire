package com.wangzan101.mika.setup.ui

import android.content.Context
import com.wangzan101.mika.settings.ui.models.edit

/** The three first-run steps. */
enum class OnboardingStep { Welcome, Model, Phone }

/**
 * Which steps to walk through on first launch (pure functions, easy to test). Like the phone guide, it decides based on "has this been seen before" rather than popping up every time:
 * - Welcome and model setup: only when there is no main model yet and no model has been configured in onboarding. Users who already configured a model (including existing users) skip straight past.
 * - Phone control: when the system is new enough, phone control is not ready yet, and the guide has not been seen.
 */
object Onboarding {
    data class Inputs(
        val hasMainModel: Boolean,
        val modelSetupDone: Boolean,
        /** The system is new enough and phone control is not ready yet. */
        val phoneNeeded: Boolean,
        val phoneGuideSeen: Boolean,
    )

    fun start(i: Inputs): OnboardingStep? = when {
        !i.hasMainModel && !i.modelSetupDone -> OnboardingStep.Welcome
        i.phoneNeeded && !i.phoneGuideSeen -> OnboardingStep.Phone
        else -> null
    }

    /** The next step after [step] is done; null when there is none (go to the conversation). */
    fun next(step: OnboardingStep, i: Inputs): OnboardingStep? = when (step) {
        OnboardingStep.Welcome -> OnboardingStep.Model
        OnboardingStep.Model -> if (i.phoneNeeded && !i.phoneGuideSeen) OnboardingStep.Phone else null
        OnboardingStep.Phone -> null
    }

    /** Bottom pager dots: which step of the three this is. */
    fun dot(step: OnboardingStep): Int = step.ordinal
    const val DOTS = 3
}

/** Whether "configure model" in the onboarding flow has been completed; stored on the device. */
class OnboardingState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun modelSetupDone(): Boolean = prefs.getBoolean(MODEL_DONE, false)

    fun markModelSetupDone() {
        prefs.edit().putBoolean(MODEL_DONE, true).apply()
    }

    private companion object {
        const val PREFS = "onboarding"
        const val MODEL_DONE = "model_setup_done"
    }
}
