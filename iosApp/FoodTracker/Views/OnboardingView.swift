import AVFoundation
import SwiftUI
import UserNotifications

/// `.ob-col`: one centred column on every width, so iPads don't stretch it.
private let onboardingColumnWidth: CGFloat = 480
private let onboardingMarkSize: CGFloat = 72

/// The first-run flow: each permission is explained just before the system asks, and nothing
/// depends on the answer. `onFinish` says where to go next.
///
/// The camera is asked here only because this is where it is explained; `ScannerSheetView` still
/// owns every other case. An answer given here carries over through the system's own record, so
/// the scanner never asks a second time.
struct OnboardingView: View {
    let onFinish: (OnboardingExit) -> Void

    @State private var flow: OnboardingFlow
    /// Keeps a second tap from asking twice while the system is answering.
    @State private var isAsking = false

    init(startingAt flow: OnboardingFlow = OnboardingFlow(), onFinish: @escaping (OnboardingExit) -> Void) {
        _flow = State(initialValue: flow)
        self.onFinish = onFinish
    }

    private var cameraIsUnasked: Bool {
        AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined
    }

    var body: some View {
        Group {
            switch flow.step {
            case .welcome: welcome
            case .notifications: notifications
            case .camera: camera
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
        OnboardingPage {
            LarderButton(L10n.onboardingGetStarted, fullWidth: true) {
                ask {
                    let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
                    flow.start(OnboardingAsks(notifications: status == .notDetermined, camera: cameraIsUnasked))
                }
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
        OnboardingPage {
            LarderButton(L10n.onboardingNotificationsAllow, fullWidth: true) {
                ask {
                    let answer = await IOSNotificationManager.shared.requestAuthorization()
                    flow.notificationsAnswered(answer, OnboardingAsks(notifications: false, camera: cameraIsUnasked))
                }
            }
            LarderButton(L10n.onboardingNotNow, variant: .text, fullWidth: true) {
                flow.notificationsAnswered(nil, OnboardingAsks(notifications: false, camera: cameraIsUnasked))
            }
        } content: {
            OnboardingTitle(L10n.onboardingNotificationsTitle)
            OnboardingBody(L10n.onboardingNotificationsBody)
            SampleReminder()
        }
    }

    private var camera: some View {
        OnboardingPage {
            LarderButton(L10n.onboardingCameraAllow, fullWidth: true) {
                ask { flow.cameraAnswered(await AVCaptureDevice.requestAccess(for: .video)) }
            }
            LarderButton(L10n.onboardingNotNow, variant: .text, fullWidth: true) {
                flow.cameraAnswered(nil)
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

    private var ready: some View {
        OnboardingPage {
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
        OnboardingPage {
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
    private let actions: Actions
    private let content: Content

    init(@ViewBuilder actions: () -> Actions, @ViewBuilder content: () -> Content) {
        self.actions = actions()
        self.content = content()
    }

    var body: some View {
        VStack(spacing: 0) {
            GeometryReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) { content }
                        .frame(maxWidth: onboardingColumnWidth, alignment: .leading)
                        .padding(.top, LarderSpace.s6)
                        .padding(.bottom, LarderSpace.s4)
                        .padding(.horizontal, LarderSpace.s6)
                        .frame(maxWidth: .infinity, minHeight: proxy.size.height)
                }
                .scrollBounceBehavior(.basedOnSize)
            }
            VStack(spacing: LarderSpace.s1) { actions }
                .frame(maxWidth: onboardingColumnWidth)
                .padding(.top, LarderSpace.s2)
                .padding(.bottom, LarderSpace.s6)
                .padding(.horizontal, LarderSpace.s6)
        }
        .background(LarderColor.background.ignoresSafeArea())
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

private extension OnboardingFlow {
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
        case .ready, .declined:
            flow.notificationsAnswered(remindersDeclined ? false : nil, camera)
            flow.cameraAnswered(cameraDeclined ? false : nil)
        }
        return flow
    }
}

#Preview("Welcome") { OnboardingView(startingAt: .at(.welcome)) { _ in } }
#Preview("Welcome, dark") { OnboardingView(startingAt: .at(.welcome)) { _ in }.preferredColorScheme(.dark) }
#Preview("Notifications") { OnboardingView(startingAt: .at(.notifications)) { _ in } }
#Preview("Camera") { OnboardingView(startingAt: .at(.camera)) { _ in } }
#Preview("Ready") { OnboardingView(startingAt: .at(.ready)) { _ in } }
#Preview("Declined, both") {
    OnboardingView(startingAt: .at(.declined, remindersDeclined: true, cameraDeclined: true)) { _ in }
}
#Preview("Declined, reminders") { OnboardingView(startingAt: .at(.declined, remindersDeclined: true)) { _ in } }
