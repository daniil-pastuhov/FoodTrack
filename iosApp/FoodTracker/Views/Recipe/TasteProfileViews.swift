import SwiftUI
import UIKit

// The taste profile's screens: the row in Recipe settings, the promo sheet, and the quiz, drawn
// from whatever definition the server sent (`TasteQuiz`). Design: docs/design "Taste profile".
// Android: `TasteProfileScreen.kt`.

// MARK: Settings row

/// "Taste profile" on its own between Language and the account card, only where the paid route
/// is and the server takes taste profiles (`TasteProfile.entry`). Its supporting line is the
/// summary, at most two lines. A tap opens the quiz or the promo sheet; the row calls nothing.
struct TasteProfileRow: View {
    @ObservedObject var model: RecipeViewModel

    var body: some View {
        LarderGroup {
            Button { model.tasteRowTapped() } label: {
                HStack(spacing: LarderSpace.s3) {
                    LarderIcon(.heart).foregroundStyle(LarderColor.onSurfaceVariant)
                    let summary = L10n.tasteSummary(model.taste, seen: model.tasteSeen, quiz: model.tasteQuiz)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(L10n.tasteTitle).larderFont(.body).foregroundStyle(LarderColor.onSurface)
                        // Two lines at most; the new-question note is never the part cut.
                        Text(summary.line)
                            .larderFont(.bodySm)
                            .foregroundStyle(LarderColor.onSurfaceVariant)
                            .lineLimit(summary.newQuestions == nil ? 2 : 1)
                        if let new = summary.newQuestions {
                            Text("\u{00B7} " + new)
                                .larderFont(.bodySm)
                                .fontWeight(.semibold)
                                .foregroundStyle(LarderColor.primary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    // New questions with no profile yet: the header's dot, here too.
                    if model.taste.isEmpty && model.hasNewTasteQuestions {
                        Circle()
                            .fill(LarderColor.primary)
                            .frame(width: 8, height: 8)
                            .accessibilityLabel(L10n.tasteSettingsBadge)
                    }
                    LarderIcon(.chevronRight).foregroundStyle(LarderColor.onSurfaceVariant)
                }
                .padding(.vertical, LarderSpace.s2)
                .padding(.horizontal, LarderSpace.s4)
                .frame(minHeight: 56)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }
}

// MARK: Promo sheet

/// What a taste profile does, for someone with no recipes in a pack and no saved profile. Static:
/// shown without any call, no price, no count of questions. "See recipe packs" opens the Buy sheet.
struct TastePromoSheet: View {
    @ObservedObject var model: RecipeViewModel

    var body: some View {
        LarderSheet {
            TasteEmblem(.heart)
            Text(L10n.tasteTitle)
                .larderFont(.title)
                .foregroundStyle(LarderColor.onSurface)
                .accessibilityAddTraits(.isHeader)
            Text(L10n.tastePromoLede)
                .larderFont(.bodySm)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            LarderGroup {
                OnboardingRow(.listChecks, title: L10n.tastePromoQuestionsTitle, supporting: L10n.tastePromoQuestionsBody)
                OnboardingRow(.sparkles, title: L10n.tastePromoTasteTitle, supporting: L10n.tastePromoTasteBody)
                OnboardingRow(.smartphone, title: L10n.tastePromoDeviceTitle, supporting: L10n.tastePromoDeviceBody)
            }
            VStack(spacing: LarderSpace.s1) {
                LarderButton(L10n.recipeIntroPacksAction, fullWidth: true) { model.tastePromoToPacks() }
                LarderButton(L10n.cancelButton, variant: .text, fullWidth: true) { model.showsTastePromo = false }
            }
            .padding(.top, LarderSpace.s2)
        }
    }
}

/// The tile at the head of the promo and of each quiz step.
struct TasteEmblem: View {
    let icon: LarderIconName
    @ScaledMetric(relativeTo: .body) private var side: CGFloat = 56

    init(_ icon: LarderIconName) { self.icon = icon }

    var body: some View {
        LarderIcon(icon, size: .custom(28))
            .foregroundStyle(LarderColor.onPrimaryContainer)
            .frame(width: side, height: side)
            .background(LarderColor.primaryContainer, in: RoundedRectangle(cornerRadius: LarderRadius.md))
            .accessibilityHidden(true)
    }
}

extension TasteQuiz.Step {
    /// The step's icon from the bundled Lucide set; a neutral one for a name the app doesn't have.
    var larderIcon: LarderIconName { icon.flatMap(LarderIconName.init(rawValue:)) ?? .utensils }
}

// MARK: Quiz

/// The quiz, its own full screen over Recipe settings: close and title on top with the step
/// and its progress, the question scrolling, Back and Skip / Next / Save fixed at the foot. Nothing
/// is kept unless saved; leaving with changes asks first. The definition shown is the one it
/// opened with (`RecipeViewModel.tasteQuizLoad`), never swapped while open.
struct TasteQuizView: View {
    @ObservedObject var model: RecipeViewModel

    var body: some View {
        Group {
            switch model.tasteQuizLoad {
            case .loading:
                TasteQuizChrome(onClose: close) {
                    VStack(spacing: LarderSpace.s4) {
                        LarderSpinner()
                        Text(L10n.tasteLoading)
                            .larderFont(.body)
                            .foregroundStyle(LarderColor.onSurfaceVariant)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityElement(children: .combine)
                }
            case .failed:
                TasteQuizChrome(onClose: close) {
                    // Centred like the loader; scrolls when large text makes it taller than the screen.
                    ViewThatFits(in: .vertical) {
                        loadFailed.frame(maxHeight: .infinity)
                        ScrollView { loadFailed.padding(.vertical, LarderSpace.s6) }
                    }
                }
            case .ready(let quiz):
                TasteQuizSteps(model: model, quiz: quiz)
                    // A new definition (after Retry) starts afresh.
                    .id(quiz.version + quiz.language)
            }
        }
        .background(LarderColor.background.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .navigationBarBackButtonHidden(true)
        .shellChromeEffect(hideNav: true)
    }

    /// T5 in the quiz's frame: what failed, Retry (back to the loader) and Close.
    private var loadFailed: some View {
        ScreenError(.wifiOff, title: L10n.tasteErrorTitle, message: L10n.tasteErrorBody) {
            LarderButton(L10n.tasteRetry) { model.retryTasteQuiz() }
            LarderButton(L10n.tasteClose, variant: .text) { close() }
        }
        .frame(maxWidth: 480)
        .frame(maxWidth: .infinity)
    }

    private func close() {
        if model.path.last == .tasteQuiz { model.path.removeLast() }
    }
}

/// The top bar alone, for the loading and error states.
private struct TasteQuizChrome<Content: View>: View {
    let onClose: () -> Void
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) {
            TasteTopBar(onClose: onClose, onSave: nil)
            content
        }
    }
}

private struct TasteTopBar: View {
    let onClose: () -> Void
    let onSave: (() -> Void)?

    var body: some View {
        HStack(spacing: LarderSpace.s1) {
            LarderIconButton(.x, label: L10n.tasteClose, color: LarderColor.onSurface, action: onClose)
            Text(L10n.tasteTitle)
                .larderFont(.title)
                .foregroundStyle(LarderColor.onSurface)
                .accessibilityAddTraits(.isHeader)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let onSave {
                LarderButton(L10n.tasteSave, variant: .text, action: onSave)
            }
        }
        .padding(.horizontal, LarderSpace.s1)
        .padding(.trailing, LarderSpace.s2)
        .frame(minHeight: 56)
    }
}

private struct TasteQuizSteps: View {
    @ObservedObject var model: RecipeViewModel
    let quiz: TasteQuiz

    @Environment(\.showSnackbar) private var showShellSnackbar
    @State private var draft: TasteProfile
    @State private var index: Int
    @State private var seen: Set<String>
    @State private var confirmingLeave = false
    @State private var snackbar: LarderSnackbarContent?
    @AccessibilityFocusState private var helperFocused: Bool
    @AccessibilityFocusState private var questionFocused: Bool

    init(model: RecipeViewModel, quiz: TasteQuiz) {
        self.model = model
        self.quiz = quiz
        let start = TasteProfile.firstStep(seen: model.tasteSeen, quiz: quiz)
        #if DEBUG
        let opening = model.demoTasteStep.map { min(max($0, 0), quiz.steps.count - 1) } ?? start
        #else
        let opening = start
        #endif
        _draft = State(initialValue: model.taste.filtered(by: quiz))
        _index = State(initialValue: opening)
        _seen = State(initialValue: [quiz.steps[opening].id])
    }

    private var step: TasteQuiz.Step { quiz.steps[index] }
    private var isLast: Bool { index == quiz.steps.count - 1 }
    /// A profile is saved: Save sits in the top bar too, and Clear at the foot of the first step.
    private var isEditing: Bool { !model.taste.isEmpty }
    /// Steps added since the profile was saved: the "New question" tag.
    private var newStepIDs: Set<String> { Set(quiz.steps.filter { TasteProfile.isNewStep($0, seen: model.tasteSeen) }.map(\.id)) }
    private var changed: Bool { draft.filtered(by: quiz).answers != model.taste.filtered(by: quiz).answers }

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: LarderSpace.s2) {
                TasteTopBar(onClose: requestLeave, onSave: isEditing ? save : nil)
                progress.padding(.horizontal, LarderSpace.s4)
            }
            .frame(maxWidth: 640 + 2 * LarderSpace.s4)
            .frame(maxWidth: .infinity)
            ScrollView {
                TasteStepContent(
                    step: step,
                    quiz: quiz,
                    draft: draft,
                    isFirst: index == 0,
                    showsIntro: index == 0,
                    showsClear: index == 0 && isEditing,
                    isNew: newStepIDs.contains(step.id),
                    onTap: tap,
                    onClear: clear,
                    helperFocused: $helperFocused,
                    questionFocused: $questionFocused
                )
                .padding(.horizontal, LarderSpace.s4)
                .padding(.top, LarderSpace.s4)
                .padding(.bottom, LarderSpace.s6)
                .frame(maxWidth: 640 + 2 * LarderSpace.s4)
                .frame(maxWidth: .infinity)
            }
            // A new scroll view per step, so each opens at its top.
            .id(step.id)
            .overlay(alignment: .bottom) {
                LarderSnackbarHost(content: $snackbar).padding(.bottom, LarderSpace.s3)
            }
            foot
        }
        .onChange(of: index) { _, new in
            seen.insert(quiz.steps[new].id)
            questionFocused = true
        }
        .larderDialog(
            L10n.discardChangesTitle,
            isPresented: $confirmingLeave,
            message: L10n.tasteDiscardMessage,
            actions: [.cancel(L10n.discardChangesKeep), .destructive(L10n.discardChangesConfirm) { leave() }]
        )
    }

    private var progress: some View {
        VStack(alignment: .leading, spacing: LarderSpace.s2) {
            Text(L10n.tasteProgress(index + 1, of: quiz.steps.count))
                .larderFont(.caption)
                .foregroundStyle(LarderColor.onSurfaceVariant)
            HStack(spacing: LarderSpace.s1) {
                ForEach(quiz.steps.indices, id: \.self) { i in
                    Capsule()
                        .fill(i <= index ? LarderColor.primary : LarderColor.outlineVariant)
                        .frame(height: 4)
                }
            }
            .accessibilityHidden(true)
        }
    }

    private var foot: some View {
        VStack(spacing: 0) {
            Rectangle().fill(LarderColor.outlineVariant).frame(height: 1)
            HStack(spacing: LarderSpace.s3) {
                if index > 0 {
                    LarderButton(L10n.tasteBack, icon: .arrowLeft, variant: .text) { index -= 1 }
                }
                Spacer(minLength: 0)
                if isLast {
                    LarderButton(L10n.tasteSave, icon: .check, action: save)
                } else if draft.selected(step.id).isEmpty {
                    LarderButton(L10n.tasteSkip, variant: .outline) { index += 1 }
                } else {
                    LarderButton(L10n.tasteNext) { index += 1 }
                }
            }
            .padding(.horizontal, LarderSpace.s4)
            .padding(.vertical, LarderSpace.s3)
            .frame(maxWidth: 640 + 2 * LarderSpace.s4)
            .frame(maxWidth: .infinity)
        }
        .background(LarderColor.background.ignoresSafeArea(edges: .bottom))
    }

    // MARK: Actions

    private func tap(_ option: TasteQuiz.Option) {
        let step = step
        guard draft.canToggle(step, option.id) else {
            // At the cap: nothing changes; the helper line is read again.
            UIAccessibility.post(notification: .announcement, argument: L10n.tasteCapReached(step.max))
            helperFocused = true
            return
        }
        let before = draft
        let moved = draft.selected(step.id).contains(option.id) ? nil : draft.conflictChosen(step, option.id, in: quiz)
        withAnimation(.easeInOut(duration: 0.15)) { draft = draft.toggled(step, option.id, in: quiz) }
        if let moved {
            let offSkip = quiz.step(moved.step)?.tone == .avoid
            let label = quiz.step(moved.step)?.option(moved.option)?.label ?? option.label
            snackbar = LarderSnackbarContent(
                message: offSkip ? L10n.tasteMovedOffSkip(label) : L10n.tasteMovedOffLove(label),
                actionTitle: L10n.undoAction,
                // Only the inverse of this move, so choices made since stay.
                action: { [quiz] in draft = draft.undoneMove(quiz: quiz, step: step.id, option: option.id, movedFrom: moved) }
            )
        }
        if step.kind == .multi, draft.selected(step.id).count == step.max, before.selected(step.id).count < step.max {
            UIAccessibility.post(notification: .announcement, argument: L10n.tasteCapReached(step.max))
        }
    }

    private func requestLeave() {
        if changed { confirmingLeave = true } else { leave() }
    }

    /// Nothing kept but the steps seen (the row's "new question" note goes).
    private func leave() {
        model.leaveTasteQuiz(seen: seen)
        pop()
    }

    /// Nothing chosen is the same as closing (no profile yet) or clearing (one saved).
    private func save() {
        if draft.filtered(by: quiz).isEmpty {
            if isEditing { clear() } else { leave() }
            return
        }
        model.saveTaste(draft, quiz: quiz, visited: seen)
        showShellSnackbar(L10n.tasteSavedSnackbar)
        pop()
    }

    /// Reversible, so no dialog: gone at once, with Undo.
    private func clear() {
        let previous = model.clearTaste()
        showShellSnackbar(L10n.tasteClearedSnackbar, undo: { [model] in model.restoreTaste(previous) })
        pop()
    }

    private func pop() {
        if model.path.last == .tasteQuiz { model.path.removeLast() }
    }
}

/// One step: emblem, question, the intro on the first, the step's note, the helper with its live
/// count, then the choices: chips for `multi` (under headings when grouped), cards for `single`.
private struct TasteStepContent: View {
    let step: TasteQuiz.Step
    let quiz: TasteQuiz
    let draft: TasteProfile
    let isFirst: Bool
    let showsIntro: Bool
    let showsClear: Bool
    let isNew: Bool
    let onTap: (TasteQuiz.Option) -> Void
    let onClear: () -> Void
    var helperFocused: AccessibilityFocusState<Bool>.Binding
    var questionFocused: AccessibilityFocusState<Bool>.Binding

    private var chosen: [String] { draft.selected(step.id) }
    private var atCap: Bool { step.kind == .multi && chosen.count >= step.max }

    var body: some View {
        VStack(alignment: .leading, spacing: LarderSpace.s3) {
            TasteEmblem(step.larderIcon)
            if isNew {
                Text(L10n.tasteNewTag)
                    .larderFont(.caption)
                    .foregroundStyle(LarderColor.onPrimaryContainer)
                    .padding(.horizontal, LarderSpace.s2)
                    .padding(.vertical, 2)
                    .background(LarderColor.primaryContainer, in: Capsule())
            }
            Text(step.question)
                .larderFont(.headline)
                .foregroundStyle(LarderColor.onSurface)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
                .accessibilityFocused(questionFocused)
            if showsIntro {
                Text(L10n.tasteIntro)
                    .larderFont(.bodySm)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
            helper
            if let note = step.note { noteBox(note) }
            choices
            oppositeNote
            if showsClear {
                LarderButton(L10n.tasteClear, variant: .dangerText, action: onClear)
                    .frame(maxWidth: .infinity)
                    .padding(.top, LarderSpace.s4)
            }
        }
    }

    private var helper: some View {
        HStack(alignment: .firstTextBaseline, spacing: LarderSpace.s3) {
            Text(helperText)
                .larderFont(.label)
                .foregroundStyle(LarderColor.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            if step.kind == .multi, !chosen.isEmpty {
                Text(atCap ? L10n.tasteChosenOf(chosen.count, step.max) : L10n.tasteChosen(chosen.count))
                    .larderFont(.label)
                    .foregroundStyle(LarderColor.onSurface)
                    .monospacedDigit()
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityFocused(helperFocused)
    }

    private var helperText: String {
        if step.kind == .single { return L10n.tasteChooseOne }
        return atCap ? L10n.tasteCapReached(step.max) : L10n.tasteChooseUpTo(step.max)
    }

    private func noteBox(_ note: String) -> some View {
        HStack(alignment: .top, spacing: LarderSpace.s2) {
            LarderIcon(.circleAlert, size: .custom(18)).foregroundStyle(LarderColor.onSurfaceVariant)
            Text(note)
                .larderFont(.bodySm)
                .foregroundStyle(LarderColor.onSurface)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(LarderSpace.s3)
        .overlay { RoundedRectangle(cornerRadius: LarderRadius.md).strokeBorder(LarderColor.outlineVariant, lineWidth: 1) }
    }

    @ViewBuilder
    private var choices: some View {
        switch step.kind {
        case .single:
            VStack(spacing: LarderSpace.s2) {
                ForEach(step.options) { option in
                    TasteCard(option: option, isSelected: chosen.contains(option.id)) { onTap(option) }
                }
            }
        case .multi:
            let grouped = step.groups.count > 1 || step.groups.contains { $0.title != nil }
            VStack(alignment: .leading, spacing: LarderSpace.s3) {
                ForEach(Array(step.groups.enumerated()), id: \.offset) { _, group in
                    if grouped, let title = group.title {
                        Text(title)
                            .larderFont(.label)
                            .foregroundStyle(LarderColor.onSurfaceVariant)
                            .accessibilityAddTraits(.isHeader)
                    }
                    FlowLayout(spacing: LarderSpace.s2, rowSpacing: LarderSpace.s3) {
                        ForEach(group.options) { option in chip(option) }
                    }
                }
            }
        }
    }

    private func chip(_ option: TasteQuiz.Option) -> some View {
        let isSelected = chosen.contains(option.id)
        let opposite = isSelected ? nil : draft.conflictChosen(step, option.id, in: quiz)
        let oppositeAvoids = opposite.map { quiz.step($0.step)?.tone == .avoid }
        let dimmed = !isSelected && atCap
        var label = option.label
        if let oppositeAvoids { label = oppositeAvoids ? L10n.tasteChipOnSkip(label) : L10n.tasteChipOnLove(label) }
        if dimmed { label = L10n.tasteChipAtCap(option.label) }
        return TasteChip(
            title: option.label,
            isSelected: isSelected,
            selectedIcon: step.tone == .avoid ? .ban : .check,
            mark: oppositeAvoids.map { $0 ? .ban : .heart },
            isDimmed: dimmed,
            accessibilityText: label
        ) { onTap(option) }
    }

    /// One quiet line when a chip here carries the other list's mark.
    @ViewBuilder
    private var oppositeNote: some View {
        let marks = step.options.compactMap { option -> TasteQuiz.Choice? in
            chosen.contains(option.id) ? nil : draft.conflictChosen(step, option.id, in: quiz)
        }
        if let first = marks.first {
            let onSkip = quiz.step(first.step)?.tone == .avoid
            HStack(alignment: .top, spacing: LarderSpace.s2) {
                LarderIcon(onSkip ? .ban : .heart, size: .custom(16)).foregroundStyle(LarderColor.onSurfaceVariant)
                Text(onSkip ? L10n.tasteOnSkipNote : L10n.tasteOnLoveNote)
                    .larderFont(.bodySm)
                    .foregroundStyle(LarderColor.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.top, LarderSpace.s1)
        }
    }
}

/// A large choice chip: 40 high (more with large text), a check when chosen (a ban sign on a
/// "rather skip" step), the other list's small mark, dimmed at the cap but still focusable.
private struct TasteChip: View {
    let title: String
    let isSelected: Bool
    let selectedIcon: LarderIconName
    let mark: LarderIconName?
    let isDimmed: Bool
    let accessibilityText: String
    let action: () -> Void

    @ScaledMetric(relativeTo: .body) private var height: CGFloat = 40

    var body: some View {
        Button(action: action) {
            HStack(spacing: LarderSpace.s1) {
                if isSelected {
                    LarderIcon(selectedIcon, size: .custom(16))
                } else if let mark {
                    LarderIcon(mark, size: .custom(14))
                }
                Text(title).larderFont(.body)
            }
            .foregroundStyle(isSelected ? LarderColor.onPrimaryContainer : LarderColor.onSurface)
            .padding(.horizontal, LarderSpace.s4)
            .frame(minHeight: height)
            .background(isSelected ? LarderColor.primaryContainer : Color.clear, in: Capsule())
            .overlay {
                if !isSelected {
                    Capsule().strokeBorder(mark != nil ? LarderColor.outline : LarderColor.outlineVariant, lineWidth: 1)
                }
            }
            .opacity(isDimmed ? 0.38 : 1)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// A `single` step's card: a radio, the label and its second line, and `level` flames. Tapping
/// the chosen card again clears it.
private struct TasteCard: View {
    let option: TasteQuiz.Option
    let isSelected: Bool
    let action: () -> Void

    @ScaledMetric(relativeTo: .body) private var radio: CGFloat = 22

    var body: some View {
        Button(action: action) {
            HStack(spacing: LarderSpace.s3) {
                ZStack {
                    if isSelected {
                        Circle().fill(LarderColor.primary)
                        LarderIcon(.check, size: .custom(14)).foregroundStyle(LarderColor.onPrimary)
                    } else {
                        Circle().strokeBorder(LarderColor.outline, lineWidth: 1.5)
                    }
                }
                .frame(width: radio, height: radio)
                VStack(alignment: .leading, spacing: 0) {
                    Text(option.label).larderFont(.titleSm).foregroundStyle(LarderColor.onSurface)
                    if let sub = option.sub {
                        Text(sub).larderFont(.bodySm).foregroundStyle(LarderColor.onSurfaceVariant)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let level = option.level {
                    HStack(spacing: 0) {
                        ForEach(0..<level, id: \.self) { _ in LarderIcon(.flame, size: .custom(18)) }
                    }
                    .foregroundStyle(LarderColor.primary)
                    .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, LarderSpace.s4)
            .padding(.vertical, LarderSpace.s3)
            .frame(minHeight: 64)
            .background(isSelected ? LarderColor.primaryContainer : LarderColor.surface, in: RoundedRectangle(cornerRadius: LarderRadius.md))
            .overlay {
                RoundedRectangle(cornerRadius: LarderRadius.md)
                    .strokeBorder(isSelected ? LarderColor.primary : LarderColor.outlineVariant, lineWidth: isSelected ? 2 : 1)
            }
            .contentShape(RoundedRectangle(cornerRadius: LarderRadius.md))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }
}

// MARK: Previews

#if DEBUG
@MainActor
private func previewModel(answers: [String: [String]], state: TasteQuizLoad? = nil, language: String = "en") -> RecipeViewModel {
    let model = RecipeViewModel()
    let quiz = TasteQuizSample.quiz(language: language)
    model.showTasteDemo(quiz: quiz, profile: TasteProfile(answers: answers, version: quiz.version), seen: Set(quiz.steps.map(\.id)), failing: state == .failed)
    return model
}

private let previewAnswers: [String: [String]] = [
    "cuisines": ["italian", "nordic"], "heat": ["medium"], "flavours": ["garlic", "citrus"],
    "favourites": ["tomatoes", "eggs", "berries"], "avoid": ["mushrooms", "olives"], "methods": ["oven"],
]

#Preview("Quiz, step 1") { NavigationStack { TasteQuizView(model: previewModel(answers: [:])) } }
#Preview("Quiz, heat, dark") {
    let model = previewModel(answers: previewAnswers)
    model.demoTasteStep = 1
    return NavigationStack { TasteQuizView(model: model) }.preferredColorScheme(.dark)
}
#Preview("Quiz, favourites") {
    let model = previewModel(answers: previewAnswers)
    model.demoTasteStep = 3
    return NavigationStack { TasteQuizView(model: model) }
}
#Preview("Quiz, error") { NavigationStack { TasteQuizView(model: previewModel(answers: [:], state: .failed)) } }
#Preview("Promo sheet") { TastePromoSheet(model: previewModel(answers: [:])).background(LarderColor.surfaceContainerHigh) }
#Preview("Settings row") {
    VStack {
        TasteProfileRow(model: previewModel(answers: [:]))
        TasteProfileRow(model: previewModel(answers: previewAnswers))
    }
    .padding()
    .background(LarderColor.background)
}
#endif
