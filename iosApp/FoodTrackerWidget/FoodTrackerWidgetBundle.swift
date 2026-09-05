import WidgetKit
import SwiftUI

@main
struct FoodTrackerWidgetBundle: WidgetBundle {
    var body: some Widget {
        ExpiringItemsWidget()
        ShoppingListWidget()
        OverviewWidget()
    }
}
