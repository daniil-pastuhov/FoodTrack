import SwiftUI

/// Root of the app: hosts the four peer tabs (Home/Calendar/Shopping List/Settings) behind a
/// floating pill nav bar, plus the two floating round buttons (scan / add item) that hover
/// above it and stay reachable from every tab. Owns the single shared `NativePantryViewModel`
/// so all four tabs (and the scan/add sheets) operate on the same pantry state.
struct RootTabView: View {
    @StateObject private var viewModel = NativePantryViewModel()
    @ObservedObject private var router = AppRouter.shared
    @State private var selectedTab: RootTab = .home
    @State private var isDashboardScrollingDown: Bool = false

    @State private var showScanner: Bool = false
    @State private var showAddEdit: Bool = false
    @State private var editingItem: PantryItem? = nil
    @State private var prefillBarcode: String? = nil
    @State private var prefillName: String? = nil
    @State private var prefillBrand: String? = nil
    @State private var prefillImageUrl: String? = nil
    @State private var prefillDate: Date? = nil
    @State private var prefillCategory: String? = nil

    var body: some View {
        // Full-size, edge-to-edge tab content with the pill nav bar and (Home-only) scan/add
        // buttons overlaid on top in a ZStack — not a `.safeAreaInset`, which under-reserved
        // space here because the buttons' `.offset` (a purely visual shift) doesn't change the
        // height SwiftUI accounts for. Each screen reserves its own bottom content padding
        // (see their `.safeAreaInset` on their List/Form) so the last row still scrolls clear.
        ZStack(alignment: .bottom) {
            Group {
                switch selectedTab {
                case .home:
                    PantryDashboardView(
                        viewModel: viewModel,
                        onEditItem: { presentAddEdit(editing: $0) },
                        onScrollingDownChanged: { isDashboardScrollingDown = $0 }
                    )
                    .transition(.opacity)
                case .calendar:
                    CalendarView(viewModel: viewModel)
                        .transition(.opacity)
                case .shoppingList:
                    ShoppingListView(viewModel: viewModel)
                        .transition(.opacity)
                case .settings:
                    SettingsView(viewModel: viewModel, onDataCleared: { selectedTab = .home })
                        .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.2), value: selectedTab)
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            FloatingTabBar(selectedTab: $selectedTab)
                .padding(.bottom, 8)

            // Scan/Add are only meaningful from the pantry list, so they only show on Home —
            // floating out (fade + slide toward the corner they hover over) on every other tab,
            // and also collapsing on the same downward scroll that collapses the dashboard's
            // own metrics row, reappearing on the way back up.
            if selectedTab == .home && !isDashboardScrollingDown {
                HStack {
                    Spacer()
                    ScanAndAddButtons(
                        onScan: { presentScanner() },
                        onAdd: { presentAddEdit(editing: nil) }
                    )
                    .padding(.trailing, 16)
                    .padding(.bottom, 72)
                }
                .transition(.move(edge: .trailing).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.8), value: selectedTab == .home)
        .animation(.easeInOut(duration: 0.2), value: isDashboardScrollingDown)
        .sheet(isPresented: $showScanner) {
            ScannerSheetView(viewModel: viewModel) { barcode, name, brand, imageUrl, date, category in
                editingItem = nil
                prefillBarcode = barcode
                prefillName = name
                prefillBrand = brand
                prefillImageUrl = imageUrl
                prefillDate = date
                prefillCategory = category
                showAddEdit = true
            }
        }
        .sheet(isPresented: $showAddEdit) {
            AddEditItemView(
                viewModel: viewModel,
                editingItem: editingItem,
                prefillBarcode: prefillBarcode,
                prefillName: prefillName,
                prefillBrand: prefillBrand,
                prefillImageUrl: prefillImageUrl,
                prefillDate: prefillDate,
                prefillCategory: prefillCategory
            )
        }
        .onChange(of: router.openShoppingList) { _, shouldOpen in
            guard shouldOpen else { return }
            selectedTab = .shoppingList
            router.openShoppingList = false
        }
    }

    private func presentScanner() {
        editingItem = nil
        showScanner = true
    }

    private func presentAddEdit(editing item: PantryItem?) {
        editingItem = item
        prefillBarcode = nil
        prefillName = nil
        prefillBrand = nil
        prefillImageUrl = nil
        prefillDate = nil
        prefillCategory = nil
        showAddEdit = true
    }
}
