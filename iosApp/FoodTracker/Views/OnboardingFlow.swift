/// The first-run screens. Mirrors Android's `OnboardingFlow.kt`.
enum OnboardingStep: String {
    case welcome, notifications, camera, staples, ready, declined
}

/// Where the first-run flow hands over to the app.
enum OnboardingExit {
    case scanner, addByHand, home
}

/// Which rationale screens still have something to ask. Read fresh each time, because an answer
/// can change it. Nothing to ask means the screen is skipped, as when a flow restarted after being
/// killed already has its answers.
struct OnboardingAsks {
    var notifications: Bool
    var camera: Bool
}

/// Where the first-run flow stands. Every answer moves it forward; nothing depends on the answer
/// except what the last screen says.
///
/// An answer is true for allowed, false for refused on the system prompt, and nil when the system
/// never put the question, as with Not now. Only a refusal counts as declined.
struct OnboardingFlow {
    private(set) var step: OnboardingStep = .welcome
    private(set) var remindersDeclined = false
    private(set) var cameraDeclined = false

    /// Where the Staples step goes on to: the last screen, saying whether anything was refused.
    private var ending: OnboardingStep {
        remindersDeclined || cameraDeclined ? .declined : .ready
    }

    private func afterNotifications(_ asks: OnboardingAsks) -> OnboardingStep {
        asks.camera ? .camera : .staples
    }

    mutating func start(_ asks: OnboardingAsks) {
        step = asks.notifications ? .notifications : afterNotifications(asks)
    }

    mutating func notificationsAnswered(_ answer: Bool?, _ asks: OnboardingAsks) {
        remindersDeclined = answer == false
        step = afterNotifications(asks)
    }

    mutating func cameraAnswered(_ answer: Bool?) {
        cameraDeclined = answer == false
        step = .staples
    }

    /// Continue or Skip on the Staples step (which every path reaches before the last screen).
    mutating func staplesDone() {
        step = ending
    }

    /// The screen before this one, skipping those with nothing left to ask (a permission already
    /// answered on the system prompt can't be asked again). Welcome is the first and stays.
    /// Answers already given are kept: a later answer replaces them. Android: `OnboardingFlow.back`.
    mutating func back(_ asks: OnboardingAsks) {
        switch step {
        case .welcome, .notifications:
            step = .welcome
        case .camera:
            step = asks.notifications ? .notifications : .welcome
        case .staples:
            step = asks.camera ? .camera : asks.notifications ? .notifications : .welcome
        case .ready, .declined:
            step = .staples
        }
    }

    /// Whether the screen offers Back: every one but the first.
    var canGoBack: Bool { step != .welcome }

    /// Scanning leads the way unless the camera was refused, when the scanner would have nothing
    /// to show. Reminders being off doesn't get in the way of a scan.
    var primaryExit: OnboardingExit {
        cameraDeclined ? .addByHand : .scanner
    }
}
