import SwiftUI

/// Stands in for `AppRoute.onboarding` until the first-run flow is built. Nothing opens it by
/// itself yet, so people don't see it: the app still starts as it did.
struct OnboardingPlaceholderView: View {
    let onFinish: () -> Void

    var body: some View {
        EmptyState(.shelf, title: L10n.onboardingPlaceholderTitle, message: L10n.onboardingPlaceholderBody) {
            LarderButton(L10n.onboardingPlaceholderContinue, action: onFinish)
        }
        .background(LarderColor.background.ignoresSafeArea())
    }
}

#Preview("Onboarding") { OnboardingPlaceholderView {} }
#Preview("Onboarding, dark") { OnboardingPlaceholderView {}.preferredColorScheme(.dark) }
