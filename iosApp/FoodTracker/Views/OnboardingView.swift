import AVFoundation
import SwiftUI
import UserNotifications

/// `.ob-col`: one centred column on every width, so iPads don't stretch it.
private let onboardingColumnWidth: CGFloat = 480
private let onboardingMarkSize: CGFloat = 72

/// The first-run flow: each permission is explained just before the system asks, and nothing
/// depends on the answer. `onFinish` says where to go next.
///
/// A permission screen (notifications, camera) has one button, Continue, which always puts the
/// system prompt up, and no Back, Not now or other way past it: App Review guideline 5.1.1(iv)
/// (2.1.0 was rejected for "Allow the camera" and Not now). Whatever the answer, the flow goes on.
/// A refusal is met where the feature is used, with a way to the Settings app (Home's reminders
/// note, the scanner's "The camera is off").
///
/// The camera is asked here only because this is where it is explained; `ScannerSheetView` still
/// owns every other case. An answer given here carries over through the system's own record, so
/// the scanner never asks a second time.
struct OnboardingView: View {
    /// The person's staples now (normally none at first run; a restored backup may have some).
    var staples: [Staple] = []
    /// Continue on the Staples step: what to change, through the ordinary staple calls.
    var applyStaples: (Staples.PresetChanges) -> Void = { _ in }
    let onFinish: (OnboardingExit) -> Void

    @State private var flow: OnboardingFlow
    /// Keeps a second tap from asking twice while the system is answering.
    @State private var isAsking = false
    /// The Staples step's ticks, held here only (nil until it first shows: then the presets the
    /// person already has). Kept when the step is left, saved only by Continue.
    @State private var stapleTicks: Set<String>?

    init(
        startingAt flow: OnboardingFlow = OnboardingFlow(),
        staples: [Staple] = [],
        applyStaples: @escaping (Staples.PresetChanges) -> Void = { _ in },
        onFinish: @escaping (OnboardingExit) -> Void
    ) {
        _flow = State(initialValue: flow)
        self.staples = staples
        self.applyStaples = applyStaples
        self.onFinish = onFinish
    }

    private var cameraIsUnasked: Bool {
        AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined
    }

    /// What is still to ask, read fresh: an answer on the system prompt can't be taken back here.
    private func currentAsks() async -> OnboardingAsks {
        let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        return OnboardingAsks(notifications: status == .notDetermined, camera: cameraIsUnasked)
    }

    /// Back on every screen but the first.
    private var backAction: (() -> Void)? { flow.canGoBack ? goBack : nil }

    /// Back to the screen before, as Android's system Back does. The Staples ticks are kept.
    private func goBack() {
        ask { flow.back(await currentAsks()) }
    }

    var body: some View {
        Group {
            switch flow.step {
            case .welcome: welcome
            case .notifications: notifications
            case .camera: camera
            case .staples: staplesStep
            case .ready: ready
            case .declined: declined
            }
        }
        .animation(.easeInOut(duration: 0.2), value: flow.step)
    }

    private func ask(_ question: @escaping @MainActor () async -> Void) {
        guard !isAsking else { return }
        isAsking = true
        Task { @MainActor in
            await question()
            isAsking = false
        }
    }

    private var welcome: some View {
        OnboardingPage(onBack: backAction) {
            LarderButton(L10n.onboardingGetStarted, fullWidth: true) {
                ask { flow.start(await currentAsks()) }
            }
        } content: {
            JarMark().padding(.bottom, LarderSpace.s5)
            Text(L10n.appName)
                .larderFont(.title)
                .foregroundStyle(LarderColor.primary)
                .padding(.bottom, LarderSpace.s2)
            OnboardingTitle(L10n.onboardingWelcomeTitle)
            LarderCard {
                HStack(alignment: .top, spacing: LarderSpace.s3) {
                    LarderIcon(.circleCheck).foregroundStyle(LarderColor.primary)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L10n.onboardingPrivateTitle)
                            .larderFont(.label)
                            .foregroundStyle(LarderColor.onSurface)
                        Text(L10n.onboardingPrivateBody)
                            .larderFont(.bodySm)
                            .foregroundStyle(LarderColor.onSurfaceVariant)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.top, LarderSpace.s2)
        }
    }

    private var notifications: some View {
        OnboardingPage(onBack: backAction) {
            // Neutral wording and the only way on: it always leads to the system prompt.
            LarderButton(L10n.continueButton, fullWidth: true) {
                ask {
                    let answer = await IOSNotificationManager.shared.requestAuthorization()
                    flow.notificationsAnswered(answer, OnboardingAsks(notifications: false, camera: cameraIsUnasked))
                }
            }
        } content: {
            OnboardingTitle(L10n.onboardingNotificationsTitle)
            OnboardingBody(L10n.onboardingNotificationsBody)
            SampleReminder()
        }
    }

    private var camera: some View {
        OnboardingPage(onBack: backAction) {
            // As on the notifications screen: Continue, and the system prompt.
            LarderButton(L10n.continueButton, fullWidth: true) {
                ask { flow.cameraAnswered(await AVCaptureDevice.requestAccess(for: .video)) }
            }
        } content: {
            OnboardingTitle(L10n.onboardingCameraTitle)
            OnboardingBody(L10n.onboardingCameraBody(L10n.appName))
            LarderGroup {
                OnboardingRow(
                    .scanBarcode,
                    title: L10n.onboardingCameraBarcodesTitle,
                    supporting: L10n.onboardingCameraBarcodesBody
                )
                OnboardingRow(
                    .scanText,
                    title: L10n.onboardingCameraDatesTitle,
                    supporting: L10n.onboardingCameraDatesBody
                )
            }
        }
    }

    /// Staples: tick the basics always at home, or skip; the same can be done later in Settings.
    private var staplesStep: some View {
        let ticks = stapleTicks ?? Staples.presetTicks(staples)
        let full = Staples.count(staples, ticked: ticks) >= Staples.maxCount
        let language = Staples.appLanguage
        return OnboardingPage(onBack: backAction) {
            LarderButton(L10n.onboardingStaplesContinue, fullWidth: true) {
                let changes = Staples.presetChanges(staples, ticked: ticks)
                if !changes.isEmpty { applyStaples(changes) }
                flow.staplesDone()
            }
            LarderButton(L10n.onboardingStaplesSkip, variant: .text, fullWidth: true) { flow.staplesDone() }
        } content: {
            OnboardingTitle(L10n.onboardingStaplesTitle)
            OnboardingBody(L10n.staplesIntro)
            LarderGroup {
                ForEach(StaplePresets.all, id: \.id) { preset in
                    let ticked = ticks.contains(preset.id)
                    StaplePresetRow(name: preset.name(language), ticked: ticked, disabled: !ticked && full) {
                        var next = ticks
                        if ticked { next.remove(preset.id) } else { next.insert(preset.id) }
                        stapleTicks = next
                    }
                }
            }
            VStack(alignment: .leading, spacing: LarderSpace.s2) {
                Text(L10n.onboardingStaplesLater)
                Text(L10n.staplesNote)
            }
            .larderFont(.bodySm)
            .foregroundStyle(LarderColor.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, LarderSpace.s4)
        }
        .onAppear { if stapleTicks == nil { stapleTicks = Staples.presetTicks(staples) } }
    }

    private var ready: some View {
        OnboardingPage(onBack: backAction) {
            LarderButton(L10n.homeEmptyScan, fullWidth: true) { onFinish(.scanner) }
            LarderButton(L10n.onboardingSkip, variant: .text, fullWidth: true) { onFinish(.home) }
        } content: {
            LarderIcon(.scanBarcode, size: .custom(32))
                .foregroundStyle(LarderColor.onPrimaryContainer)
                .frame(width: onboardingMarkSize, height: onboardingMarkSize)
                .background(LarderColor.primaryContainer, in: Circle())
                .padding(.bottom, LarderSpace.s5)
            OnboardingTitle(L10n.onboardingReadyTitle)
            OnboardingBody(L10n.onboardingReadyBody)
        }
    }

    private var declined: some View {
        OnboardingPage(onBack: backAction) {
            let exit = flow.primaryExit
            LarderButton(exit == .addByHand ? L10n.onboardingAddByHand : L10n.homeEmptyScan, fullWidth: true) {
                onFinish(exit)
            }
            LarderButton(L10n.onboardingSkip, variant: .text, fullWidth: true) { onFinish(.home) }
        } content: {
            OnboardingTitle(L10n.onboardingDeclinedTitle)
            OnboardingBody(L10n.onboardingDeclinedBody)
            LarderGroup {
                if flow.remindersDeclined {
                    OnboardingRow(
                        .bellOff,
                        title: L10n.onboardingDeclinedRemindersTitle,
                        supporting: L10n.onboardingDeclinedRemindersBody
                    )
                }
                if flow.cameraDeclined {
                    OnboardingRow(
                        .cameraOff,
                        title: L10n.onboardingDeclinedCameraTitle,
                        supporting: L10n.onboardingDeclinedCameraBody
                    )
                }
            }
        }
    }
}

/// The column in the middle, scrolling when large text needs it, and the actions always in reach
/// at the bottom.
private struct OnboardingPage<Actions: View, Content: View>: View {
    private let onBack: (() -> Void)?
    private let actions: Actions
    private let content: Content

    /// `onBack` nil leaves the Back arrow out (the first screen).
    init(onBack: (() -> Void)?, @ViewBuilder actions: () -> Actions, @ViewBuilder content: () -> Content) {
        self.onBack = onBack
        self.actions = actions()
        self.content = content()
    }

    /// The footer's height as laid out (its buttons at their full height, whatever the text size).
    @State private var footerHeight: CGFloat = 0

    var body: some View {
        VStack(spacing: 0) {
            // Outside the scrolling, so Back stays in reach whatever the text size. The arrow
            // keeps its size; only the page below it grows.
            if let onBack {
                LarderIconButton(.arrowLeft, label: L10n.backButton, action: onBack)
                    .frame(maxWidth: onboardingColumnWidth + 2 * LarderSpace.s6 - 2 * LarderSpace.s2, alignment: .leading)
                    .padding(.horizontal, LarderSpace.s2)
                    .padding(.top, LarderSpace.s1)
                    .frame(maxWidth: .infinity)
            }
            pages
        }
        .background(LarderColor.background.ignoresSafeArea())
    }

    private var pages: some View {
        GeometryReader { page in
            // The footer takes what its buttons need and the content scrolls in the rest. When the
            // buttons would leave the content less than a third of the page (the largest text sizes
            // on a small phone), the whole page scrolls instead, with the buttons at its end, so
            // nothing is ever clipped.
            if footerHeight <= page.size.height * 2 / 3 {
                VStack(spacing: 0) {
                    GeometryReader { proxy in
                        ScrollView {
                            column
                                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
                        }
                        .scrollBounceBehavior(.basedOnSize)
                        .clipsBelowStatusBar()
                    }
                    footer
                }
            } else {
                ScrollView {
                    VStack(spacing: 0) {
                        column.frame(maxWidth: .infinity)
                        footer
                    }
                }
                .scrollBounceBehavior(.basedOnSize)
                .clipsBelowStatusBar()
            }
        }
    }

    private var column: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .frame(maxWidth: onboardingColumnWidth, alignment: .leading)
            .padding(.top, onBack == nil ? LarderSpace.s6 : LarderSpace.s2)
            .padding(.bottom, LarderSpace.s4)
            .padding(.horizontal, LarderSpace.s6)
    }

    private var footer: some View {
        VStack(spacing: LarderSpace.s1) { actions }
            .frame(maxWidth: onboardingColumnWidth)
            .padding(.top, LarderSpace.s2)
            .padding(.bottom, LarderSpace.s6)
            .padding(.horizontal, LarderSpace.s6)
            // Never squeezed: the buttons always get their whole height.
            .fixedSize(horizontal: false, vertical: true)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { footerHeight = $0 }
    }
}

private struct OnboardingTitle: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .larderFont(.display)
            .foregroundStyle(LarderColor.onBackground)
            .accessibilityAddTraits(.isHeader)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.bottom, LarderSpace.s3)
    }
}

private struct OnboardingBody: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .larderFont(.body)
            .foregroundStyle(LarderColor.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.bottom, LarderSpace.s5)
    }
}

struct OnboardingRow: View {
    let icon: LarderIconName
    let title: String
    let supporting: String

    init(_ icon: LarderIconName, title: String, supporting: String) {
        self.icon = icon
        self.title = title
        self.supporting = supporting
    }

    var body: some View {
        LarderListItem(title, supporting: supporting) {
            LarderThumb(imageReference: nil, placeholderIcon: icon)
        } trailing: {
            EmptyView()
        }
    }
}

/// Shows what the permission buys, in place of an illustration.
private struct SampleReminder: View {
    var body: some View {
        HStack(alignment: .top, spacing: LarderSpace.s3) {
            LarderIcon(.bell, size: .custom(18))
                .foregroundStyle(LarderColor.onPrimaryContainer)
                .frame(width: 32, height: 32)
                .background(LarderColor.primaryContainer, in: Circle())
            VStack(alignment: .leading, spacing: 0) {
                Text(L10n.onboardingSampleSource(L10n.appName))
                    .larderFont(.caption)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                Text(L10n.onboardingSampleTitle)
                    .larderFont(.label)
                    .foregroundStyle(LarderColor.onSurface)
                Text(L10n.onboardingSampleBody)
                    .larderFont(.bodySm)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, LarderSpace.s3)
        .padding(.horizontal, LarderSpace.s4)
        .background(LarderColor.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: LarderRadius.lg))
        .overlay(RoundedRectangle(cornerRadius: LarderRadius.lg).strokeBorder(LarderColor.outlineVariant))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L10n.onboardingSampleDescription(L10n.onboardingSampleTitle, L10n.onboardingSampleBody))
    }
}

/// The app's jar, drawn in theme colours from the design's 120-unit artwork.
private struct JarMark: View {
    var body: some View {
        Canvas { context, size in
            let unit = size.width / 120
            func rect(_ x: CGFloat, _ y: CGFloat, _ width: CGFloat, _ height: CGFloat, radius: CGFloat, color: Color) {
                let frame = CGRect(x: x * unit, y: y * unit, width: width * unit, height: height * unit)
                context.fill(Path(roundedRect: frame, cornerRadius: radius * unit), with: .color(color))
            }
            rect(0, 0, 120, 120, radius: 27, color: LarderColor.primary)
            rect(36, 38, 48, 56, radius: 10, color: LarderColor.onPrimary)
            rect(42, 26, 36, 12, radius: 4, color: LarderColor.primaryContainer)
            rect(36, 56, 48, 20, radius: 0, color: LarderColor.soonContainer)
            var tick = Path()
            tick.move(to: CGPoint(x: 50 * unit, y: 66 * unit))
            tick.addLine(to: CGPoint(x: 57 * unit, y: 73 * unit))
            tick.addLine(to: CGPoint(x: 70 * unit, y: 59 * unit))
            context.stroke(
                tick,
                with: .color(LarderColor.primary),
                style: StrokeStyle(lineWidth: 5 * unit, lineCap: .round, lineJoin: .round)
            )
        }
        .frame(width: onboardingMarkSize, height: onboardingMarkSize)
        .accessibilityHidden(true)
    }
}

extension OnboardingFlow {
    /// A flow standing at `step`, as if the screens before had been answered: previews and the
    /// Debug-only `-demoOnboardingStep`.
    static func at(_ step: OnboardingStep, remindersDeclined: Bool = false, cameraDeclined: Bool = false) -> OnboardingFlow {
        var flow = OnboardingFlow()
        let camera = OnboardingAsks(notifications: false, camera: true)
        switch step {
        case .welcome:
            break
        case .notifications:
            flow.start(OnboardingAsks(notifications: true, camera: true))
        case .camera:
            flow.start(camera)
        case .staples:
            flow.notificationsAnswered(nil, camera)
            flow.cameraAnswered(nil)
        case .ready, .declined:
            flow.notificationsAnswered(remindersDeclined ? false : nil, camera)
            flow.cameraAnswered(cameraDeclined ? false : nil)
            flow.staplesDone()
        }
        return flow
    }
}

#Preview("Welcome") { OnboardingView(startingAt: .at(.welcome)) { _ in } }
#Preview("Welcome, dark") { OnboardingView(startingAt: .at(.welcome)) { _ in }.preferredColorScheme(.dark) }
#Preview("Notifications") { OnboardingView(startingAt: .at(.notifications)) { _ in } }
#Preview("Camera") { OnboardingView(startingAt: .at(.camera)) { _ in } }
#Preview("Staples") { OnboardingView(startingAt: .at(.staples)) { _ in } }
#Preview("Ready") { OnboardingView(startingAt: .at(.ready)) { _ in } }
#Preview("Declined, both") {
    OnboardingView(startingAt: .at(.declined, remindersDeclined: true, cameraDeclined: true)) { _ in }
}
#Preview("Declined, reminders") { OnboardingView(startingAt: .at(.declined, remindersDeclined: true)) { _ in } }
