package dev.sora.protohax.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.net.VpnService
import android.view.*
import android.widget.ImageView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isInvisible
import dev.sora.protohax.MyApplication
import dev.sora.protohax.R
import dev.sora.protohax.relay.MinecraftRelay
import dev.sora.protohax.relay.service.ServiceListener
import dev.sora.protohax.ui.overlay.menu.ConfigureMenu
import dev.sora.relay.cheat.module.CheatModule
import kotlin.math.abs

class OverlayManager : ServiceListener {

	var currentContext: Context? = null

	val ctx: Context
		get() = currentContext!!

	private var entranceView: View? = null
	var renderLayerView: RenderLayerView? = null
		private set

	private val menu = ConfigureMenu(this)
	val shortcuts = mutableListOf<Shortcut>()

	@SuppressLint("ClickableViewAccessibility")
	override fun onServiceStarted() {
		val wm = MyApplication.instance.getSystemService(VpnService.WINDOW_SERVICE) as WindowManager
		val params = WindowManager.LayoutParams(
			WindowManager.LayoutParams.WRAP_CONTENT,
			WindowManager.LayoutParams.WRAP_CONTENT,
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
			PixelFormat.TRANSLUCENT
		)
		params.gravity = Gravity.TOP or Gravity.START
		params.x = 0
		params.y = 100

		val imageView = ImageView(ctx)

		val drawable = ResourcesCompat.getDrawable(
			ctx.resources, R.mipmap.ic_launcher, ctx.theme
		)
		if (drawable != null) {
			// Adaptive icons and some other drawables do not report an intrinsic size, and
			// Bitmap.createBitmap would throw for a 0x0 bitmap, so fall back to the standard
			// 108dp launcher icon size.
			val fallbackSize = (108 * ctx.resources.displayMetrics.density * 0.7f).toInt().coerceAtLeast(1)
			val width = (drawable.intrinsicWidth * 0.7f).toInt().takeIf { it > 0 } ?: fallbackSize
			val height = (drawable.intrinsicHeight * 0.7f).toInt().takeIf { it > 0 } ?: fallbackSize
			val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
			val canvas = Canvas(bitmap)
			drawable.setBounds(0, 0, canvas.width, canvas.height)
			drawable.draw(canvas)
			imageView.setImageBitmap(bitmap)
		} else {
			imageView.setImageResource(R.drawable.notification_icon)
		}
		imageView.setOnClickListener {
			menu.visibility = !menu.visibility
		}

		imageView.draggable(params, wm)

		this.entranceView = imageView
		wm.addView(imageView, params)

		renderLayerView = RenderLayerView(ctx, wm, MinecraftRelay.session)
		menu.visibility = false
		menu.display(wm, ctx)

		shortcuts.forEach {
			it.display(wm)
		}
	}

	fun toggleRenderLayerViewVisibility(state: Boolean) {
		val view = renderLayerView ?: return
		if (state != !view.isInvisible) { // value changed
			view.isInvisible = !state
		}
	}

	override fun onServiceStopped() {
		val wm = MyApplication.instance.getSystemService(VpnService.WINDOW_SERVICE) as WindowManager
		entranceView?.let { wm.removeView(it) }
		entranceView = null
		renderLayerView?.destroy()
		renderLayerView = null
		menu.destroy(wm)
		shortcuts.forEach {
			it.remove(wm)
		}
	}

	fun hasShortcut(module: CheatModule): Boolean {
		return shortcuts.any { it.module == module }
	}

	fun removeShortcut(module: CheatModule): Boolean {
		return shortcuts.removeIf { (it.module == module).also { v ->
			if (v) {
				it.remove(MyApplication.instance.getSystemService(VpnService.WINDOW_SERVICE) as WindowManager)
			}
		} }
	}

	fun addShortcut(shortcut: Shortcut) {
		if (hasShortcut(shortcut.module)) {
			throw IllegalStateException("Shortcut already exists for module: ${shortcut.module.name}")
		}

		shortcuts.add(shortcut)

		if (renderLayerView != null) {
			shortcut.display(MyApplication.instance.getSystemService(VpnService.WINDOW_SERVICE) as WindowManager)
		}
	}

	fun View.draggable(params: WindowManager.LayoutParams, windowManager: WindowManager) {
		var dragPosX = 0f
		var dragPosY = 0f
		var dragging = false
		var pressDownTime = System.currentTimeMillis()
		setOnTouchListener { v, event ->
			when (event.action) {
				MotionEvent.ACTION_DOWN -> {
					dragPosX = event.rawX
					dragPosY = event.rawY
					pressDownTime = System.currentTimeMillis()
					dragging = false
					true
				}
				MotionEvent.ACTION_UP -> {
					if (System.currentTimeMillis() - pressDownTime < 500) {
						v.performClick()
					}
					true
				}
				MotionEvent.ACTION_MOVE -> {
					if (dragging || System.currentTimeMillis() - pressDownTime > 500) {
						params.x += (event.rawX - dragPosX).toInt()
						params.y += (event.rawY - dragPosY).toInt()
						dragPosX = event.rawX
						dragPosY = event.rawY
						windowManager.updateViewLayout(this, params)
						true
					} else {
						if (abs(dragPosX - event.rawX) > 100 || abs(dragPosY - event.rawY) > 100) {
							dragging = true
						}
						false
					}
				}
				else -> false
			}
		}
	}
}
