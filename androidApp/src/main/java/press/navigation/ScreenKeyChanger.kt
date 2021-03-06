package press.navigation

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.core.view.children
import androidx.core.view.doOnLayout
import flow.Direction
import flow.Direction.BACKWARD
import flow.Direction.FORWARD
import flow.Direction.REPLACE
import flow.KeyChanger
import flow.State
import flow.TraversalCallback
import me.saket.inboxrecyclerview.page.StandaloneExpandablePageLayout
import me.saket.press.shared.ui.ScreenKey
import press.extensions.doOnCollapse
import press.extensions.doOnExpand
import press.extensions.findChild
import press.navigation.BackPressInterceptor.InterceptResult.Ignored
import press.navigation.ScreenTransition.TransitionResult
import press.navigation.ScreenTransition.TransitionResult.Handled
import press.widgets.dp

/**
 * Inflates screen Views in response to backstack changes.
 *
 * Keeps multiple screen Views stacked on top of each other so that they can interact
 * together. For screens of type [ExpandableScreenKey], pulling a foreground screen will
 * reveal its background screen.
 */
class ScreenKeyChanger(
  private val hostView: () -> ViewGroup,
  private val formFactor: FormFactor,
  transitions: List<ScreenTransition>
) : KeyChanger {
  private val transitions = transitions + BasicTransition()
  private var previousKey: ScreenKey? = null

  override fun changeKey(
    outgoingState: State?,
    incomingState: State,
    direction: Direction,
    incomingContexts: Map<Any, Context>,
    callback: TraversalCallback
  ) {
    val incomingKey = incomingState.getKey<ScreenKey>()

    if (outgoingState == null && direction == REPLACE) {
      // Short circuit if we would just be showing the same view again. Flow
      // intentionally calls changeKey() again on onResume() with the same values.
      // See: https://github.com/square/flow/issues/173.
      if (previousKey == incomingKey) {
        callback.onTraversalCompleted()
        return
      }
    }
    previousKey = incomingKey

    if (incomingKey !is CompositeScreenKey) {
      // FYI PlaceholderScreenKey gets discarded here.
      callback.onTraversalCompleted()
      return
    }

    fun findOrCreateView(key: ScreenKey): View {
      val existing = hostView().children.firstOrNull { it.screenKey<ScreenKey>() == key }
      if (existing != null) return existing

      val context = incomingContexts[key]!!
      return formFactor.createView(context, key).also {
        warnIfIdIsMissing(it)
        incomingState.restore(it)
        hostView().addView(it)
      }
    }

    val oldForegroundView = hostView().children.lastOrNull()
    val newBackgroundView = incomingKey.background?.let(::findOrCreateView)
    val newForegroundView = incomingKey.foreground.let(::findOrCreateView)

    // The incoming or outgoing View must be drawn last.
    newForegroundView.bringToFront()
    if (direction == BACKWARD) {
      oldForegroundView?.bringToFront()
    }
    dispatchFocusChangeCallback()

    val leftOverViews = hostView().children.filter { it !== newBackgroundView && it !== newForegroundView }
    val removeLeftOverViews = {
      leftOverViews.forEach {
        outgoingState?.save(it)
        hostView().removeView(it)
      }
      dispatchFocusChangeCallback()
    }

    // When animating forward, the background View can be discarded immediately.
    // When animating backward, the foreground View is discarded after the transition.
    var onTransitionEnd = {}
    when (direction) {
      FORWARD, REPLACE -> removeLeftOverViews()
      BACKWARD -> onTransitionEnd = removeLeftOverViews
    }.javaClass

    val children = hostView().children.toList()
    val forwardTransition = direction != BACKWARD
    val fromView: View? = if (forwardTransition) children.secondLast() else children.last()
    val toView: View = if (forwardTransition) children.last() else children.secondLast()!!

    if (fromView != null) {
      if (!forwardTransition && newBackgroundView != null) {
        // The transition that handles this transition may not be the same class
        // that handles the background View, so all transitions must be called.
        transitions.forEach {
          it.prepareBackground(
            background = newBackgroundView,
            foreground = toView,
            foregroundKey = toView.screenKey()
          )
        }
      }

      transitions.first {
        it.transition(
          fromView = fromView,
          fromKey = fromView.screenKey(),
          toView = toView,
          toKey = toView.screenKey(),
          newBackground = newBackgroundView,
          goingForward = forwardTransition,
          onComplete = onTransitionEnd
        ) == Handled
      }
    } else {
      onTransitionEnd()
    }

    callback.onTraversalCompleted()
  }

  private fun <T> List<T>.secondLast(): T? {
    return if (size >= 2) this[lastIndex - 1] else null
  }

  private fun warnIfIdIsMissing(incomingView: View) {
    check(incomingView.id != View.NO_ID) {
      "${incomingView::class.simpleName} needs an ID for persisting View state."
    }
  }

  private fun dispatchFocusChangeCallback() {
    val children = hostView().children.toList()
    val foregroundView = children.lastOrNull()

    children
      .filterIsInstance<ScreenFocusChangeListener>()
      .forEach {
        it.onScreenFocusChanged(focusedScreen = foregroundView)
      }
  }

  fun onInterceptBackPress(): BackPressInterceptor.InterceptResult {
    val foreground = hostView().children.lastOrNull()
    val interceptor = (foreground as? ViewGroup)?.findChild<BackPressInterceptor>()
    return interceptor?.onInterceptBackPress() ?: return Ignored
  }
}

private class BasicTransition : ScreenTransition {
  override fun transition(
    fromView: View,
    fromKey: ScreenKey,
    toView: View,
    toKey: ScreenKey,
    newBackground: View?,
    goingForward: Boolean,
    onComplete: () -> Unit
  ): TransitionResult {
    if (goingForward && toView is StandaloneExpandablePageLayout) {
      toView.doOnLayout {
        toView.expandFrom(Rect(0, fromView.dp(56), toView.width, fromView.dp(56)))
        toView.doOnExpand(onComplete)
      }

    } else if (!goingForward && fromView is StandaloneExpandablePageLayout) {
      fromView.collapseTo(Rect(0, fromView.dp(56), toView.width, fromView.dp(56)))
      fromView.doOnCollapse(onComplete)
    }
    return Handled
  }
}
