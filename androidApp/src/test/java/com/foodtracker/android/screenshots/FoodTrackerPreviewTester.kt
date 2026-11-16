package com.foodtracker.android.screenshots

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.AndroidComposePreviewTester
import com.github.takahirom.roborazzi.ComposePreviewTester
import com.github.takahirom.roborazzi.ComposePreviewTester.Options.JUnit4TestLifecycleOptions
import com.github.takahirom.roborazzi.ComposePreviewTester.TestParameter.JUnit4TestParameter.AndroidPreviewJUnit4TestParameter
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.InternalRoborazziApi
import com.github.takahirom.roborazzi.RoboComposePreviewOptionVariation
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.ThresholdValidator
import com.github.takahirom.roborazzi.annotations.ManualClockOptions
import com.github.takahirom.roborazzi.toRoborazziComposeOptions
import io.github.classgraph.AnnotationInfoList
import java.util.Collections
import java.util.IdentityHashMap
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview

/**
 * The UI screenshot check (scripts/ui_check.sh). Roborazzi's generated test renders every
 * @Preview under com.foodtracker.android.ui through this tester and compares it with the
 * baseline in src/test/snapshots/images/.
 *
 * On top of Roborazzi's own tester it
 * - adds a dark twin of every preview that has no dark variant of its own (most use
 *   @PreviewLightDark already), named like the light one plus "_NIGHT";
 * - runs every preview on a manual clock stopped at [CLOCK_MS], so an endless animation (a
 *   blinking cursor, a spinner) is caught at one fixed frame instead of keeping Compose busy;
 * - pins the time zone and keeps the network out ([ScreenshotEnvironmentRule]);
 * - compares strictly: see [compareOptions].
 */
@OptIn(ExperimentalRoborazziApi::class, InternalRoborazziApi::class)
class FoodTrackerPreviewTester private constructor(
    private val delegate: AndroidComposePreviewTester,
) : ComposePreviewTester<AndroidPreviewJUnit4TestParameter> by delegate {

    constructor() : this(
        AndroidComposePreviewTester(
            capturer = { parameter ->
                AndroidComposePreviewTester.DefaultCapturer().capture(
                    parameter.copy(
                        // The clock variation adds "_TIME_<ms>ms" to every name; it is the same for all.
                        filePath = parameter.filePath.replace(CLOCK_SUFFIX, ""),
                        roborazziOptions = parameter.roborazziOptions.copy(compareOptions = compareOptions),
                    )
                )
            }
        )
    )

    override fun options(): ComposePreviewTester.Options {
        val base = delegate.options()
        val lifecycle = base.testLifecycleOptions as JUnit4TestLifecycleOptions
        return base.copy(
            testLifecycleOptions = lifecycle.copy(
                testRuleFactory = { rule ->
                    RuleChain.outerRule(PreviewSetupRule(previewsByRule.remove(rule)))
                        .around(ScreenshotEnvironmentRule())
                        .around(lifecycle.testRuleFactory(rule))
                }
            )
        )
    }

    override fun testParameters(): List<AndroidPreviewJUnit4TestParameter> {
        val parameters = delegate.testParameters()
        val coveredDark = parameters
            .filter { it.preview.previewInfo.isNight() }
            .map { it.preview.declaringClass to it.preview.methodName }
            .toSet()
        val darkTwins = parameters
            .filter { (it.preview.declaringClass to it.preview.methodName) !in coveredDark }
            .map { it.copy(preview = NightPreview(it.preview)) }
        return (parameters + darkTwins)
            .sortedBy { it.preview.toString() }
            .map { parameter ->
                val ruleFactory = parameter.composeTestRuleFactory
                parameter.copy(
                    composeTestRuleFactory = { ruleFactory().also { previewsByRule[it] = parameter.preview } },
                    composeRoboComposePreviewOptionVariation = RoboComposePreviewOptionVariation(
                        manualClockOptions = ManualClockOptions(advanceTimeMillis = CLOCK_MS)
                    ),
                )
            }
    }

    companion object {
        /** Where every preview's clock stands when it is captured: past any enter animation. */
        const val CLOCK_MS = 10_000L
        private const val CLOCK_SUFFIX = "_TIME_${CLOCK_MS}ms"

        /**
         * A pixel counts as changed when its colour moves by more than 0.7 % of the colour
         * space's diagonal (Roborazzi's default per-pixel tolerance, a few units out of 255,
         * which absorbs nothing but anti-aliasing noise), and one changed pixel fails the
         * preview (threshold 0). A 1dp shift moves whole edges by 2.75 px at the Pixel 5's
         * density and a token colour change repaints every pixel of its fill, so both fail.
         */
        val compareOptions = RoborazziOptions.CompareOptions(
            imageComparator = SimpleImageComparator(maxDistance = 0.007f),
            resultValidator = ThresholdValidator(0f),
        )

        /** Which preview each generated test's rule is for, so its setup can run before launch. */
        private val previewsByRule: MutableMap<ComposeContentTestRule, ComposablePreview<AndroidPreviewInfo>> =
            Collections.synchronizedMap(IdentityHashMap())
    }
}

/**
 * Applies the preview's device, size, locale, uiMode and font scale before the rule launches
 * its activity, as Roborazzi does for its own tester (createRoborazziPreviewConfigurationRule
 * does it only for AndroidComposePreviewTester itself). Without it the capture changes them
 * afterwards, the activity is recreated, and a full-screen preview comes out 3 px taller.
 */
@OptIn(ExperimentalRoborazziApi::class, InternalRoborazziApi::class)
private class PreviewSetupRule(private val preview: ComposablePreview<AndroidPreviewInfo>?) : TestRule {
    override fun apply(base: Statement, description: org.junit.runner.Description): Statement =
        object : Statement() {
            override fun evaluate() {
                if (preview == null) return base.evaluate()
                val qualifiers = RuntimeEnvironment.getQualifiers()
                val fontScale = RuntimeEnvironment.getFontScale()
                try {
                    preview.toRoborazziComposeOptions().applySetup()
                    base.evaluate()
                } finally {
                    RuntimeEnvironment.setQualifiers(qualifiers)
                    if (RuntimeEnvironment.getFontScale() != fontScale) RuntimeEnvironment.setFontScale(fontScale)
                }
            }
        }
}

private fun AndroidPreviewInfo.isNight(): Boolean =
    uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/** The same preview in dark mode: its uiMode with the night bit set, everything else kept. */
private class NightPreview(
    private val base: ComposablePreview<AndroidPreviewInfo>,
) : ComposablePreview<AndroidPreviewInfo> {
    override val previewInfo: AndroidPreviewInfo = base.previewInfo.let { info ->
        val type = (info.uiMode and Configuration.UI_MODE_TYPE_MASK)
            .takeIf { it != Configuration.UI_MODE_TYPE_UNDEFINED } ?: Configuration.UI_MODE_TYPE_NORMAL
        info.copy(uiMode = Configuration.UI_MODE_NIGHT_YES or type)
    }
    override val previewIndex: Int? get() = base.previewIndex
    override val previewIndexDisplayName: String? get() = base.previewIndexDisplayName
    override val otherAnnotationsInfo: AnnotationInfoList? get() = base.otherAnnotationsInfo
    override val declaringClass: String get() = base.declaringClass
    override val methodName: String get() = base.methodName
    override val methodParametersType: String get() = base.methodParametersType

    @Composable
    override fun invoke() = base()

    override fun toString(): String = "${base}_NIGHT"
}
