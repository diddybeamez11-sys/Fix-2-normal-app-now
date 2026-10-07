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
import dev.sora.relay.utils.logError
import kotlin.math.abs

class OverlayManager : ServiceListener {

	var currentContext: Context? = null

	// the service context is only set while AppService is alive; falling back to the application
	// context keeps building the overlay possible instead of throwing a NullPointerException
	val ctx: Context
		get() = currentContext ?: MyApplication.instance

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

		// a drawable without an intrinsic size reports -1, which used to produce a zero/negative
		// sized bitmap and throw, aborting the whole overlay before anything was added
		val drawable = ResourcesCompat.getDrawable(ctx.resources, R.mipmap.ic_launcher, ctx.theme)
		val iconWidth = ((drawable?.intrinsicWidth ?: 0) * 0.7).toInt()
		val iconHeight = ((drawable?.intrinsicHeight ?: 0) * 0.7).toInt()
		if (drawable != null && iconWidth > 0 && iconHeight > 0) {
			val bitmap = Bitmap.createBitmap(iconWidth, iconHeight, Bitmap.Config.ARGB_8888)
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

		// each part of the overlay is added independently: a failure in one of them must not
		// stop the rest from showing up, otherwise the GUI silently never loads at all
		try {
			renderLayerView = RenderLayerView(ctx, wm, MinecraftRelay.session)
		} catch (t: Throwable) {
			logError("render layer", t)
		}

		try {
			menu.visibility = false
			menu.display(wm, ctx)
		} catch (t: Throwable) {
			logError("configure menu", t)
		}

		shortcuts.forEach {
			try {
				it.display(wm)
			} catch (t: Throwable) {
				logError("shortcut ${it.module.name}", t)
			}
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
		// views may be missing when the overlay was only partially built, and removeView throws
		// for anything that is not attached - tear down every part independently
		entranceView?.let {
			try {
				wm.removeView(it)
			} catch (t: Throwable) {
				logError("remove entrance view", t)
			}
		}
		entranceView = null
		try {
			renderLayerView?.destroy()
		} catch (t: Throwable) {
			logError("destroy render layer", t)
		}
		renderLayerView = null
		try {
			menu.destroy(wm)
		} catch (t: Throwable) {
			logError("destroy configure menu", t)
		}
		shortcuts.forEach {
			try {
				it.remove(wm)
			} catch (t: Throwable) {
				logError("remove shortcut ${it.module.name}", t)
			}
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
