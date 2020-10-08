@file:OptIn(ExperimentalStdlibApi::class)

package press.widgets.popup

import android.annotation.SuppressLint
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import android.view.ViewGroup
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.appcompat.view.menu.SubMenuBuilder
import androidx.core.view.iterator
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import press.widgets.popup.CascadeMenu.Styler
import press.widgets.popup.CascadeMenuAdapter.ItemType.Header
import press.widgets.popup.CascadeMenuAdapter.ItemType.Item
import press.widgets.popup.CascadePopupWindow.ThemeAttributes

@SuppressLint("RestrictedApi")
internal class CascadeMenuAdapter(
  menu: Menu,
  private val styler: Styler,
  private val themeAttrs: ThemeAttributes,
  private val onTitleClick: (SubMenuBuilder) -> Unit,
  private val onItemClick: (MenuItem) -> Unit
) : Adapter<ViewHolder>() {

  sealed class ItemType {
    class Header(val menu: SubMenuBuilder) : ItemType()
    class Item(val item: MenuItemImpl) : ItemType()
  }

  private val items: List<ItemType> = buildList {
    if (menu is SubMenu) {
      add(Header(menu as SubMenuBuilder))
    }
    for (item in menu) {
      add(Item(item as MenuItemImpl))
    }
  }

  private val hasSubMenuItems = items
      .filterIsInstance<Item>()
      .any { it.item.hasSubMenu() }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    return when (viewType) {
      VIEW_TYPE_HEADER -> MenuHeaderViewHolder.inflate(parent).apply {
        itemView.setBackgroundResource(themeAttrs.touchFeedbackRes)
        itemView.setOnClickListener { onTitleClick(menu) }
      }
      VIEW_TYPE_ITEM -> MenuItemViewHolder.inflate(parent, hasSubMenuItems).apply {
        itemView.setBackgroundResource(themeAttrs.touchFeedbackRes)
        itemView.setOnClickListener { onItemClick(item) }
      }
      else -> TODO()
    }
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    when (holder) {
      is MenuHeaderViewHolder -> {
        holder.menu = (items[position] as Header).menu
        holder.render()
        styler.menuTitle(holder)
      }

      is MenuItemViewHolder -> {
        holder.item = (items[position] as Item).item
        holder.render()
        styler.menuItem(holder)
      }
    }
  }

  override fun getItemViewType(position: Int): Int {
    return when (items[position]) {
      is Header -> VIEW_TYPE_HEADER
      is Item -> VIEW_TYPE_ITEM
    }
  }

  override fun getItemCount(): Int {
    return items.size
  }

  companion object {
    const val VIEW_TYPE_HEADER = 0
    const val VIEW_TYPE_ITEM = 1
  }
}