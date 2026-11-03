package com.inhuman.ui.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.inhuman.core.common.diag.Diag
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * Draws a JLaTeXMath drawable at the bitmap size the formula asked for.
 *
 * JLaTeXMath's Android port renders through the canvas, not through an image
 * file, so a thin Painter is all that stands between it and Compose. The
 * intrinsic size is the formula's own; the placeholder config in the Markdown
 * renderer scales it down when the line is too narrow.
 */
private class MathPainter(private val drawable: JLatexMathDrawable) : Painter() {

	override val intrinsicSize: Size
		get() = Size(
			drawable.intrinsicWidth.toFloat().coerceAtLeast(1f),
			drawable.intrinsicHeight.toFloat().coerceAtLeast(1f),
		)

	override fun DrawScope.onDraw() {
		val width = size.width.toInt().coerceAtLeast(1)
		val height = size.height.toInt().coerceAtLeast(1)
		drawable.setBounds(0, 0, width, height)
		drawIntoCanvas { canvas -> drawable.draw(canvas.nativeCanvas) }
	}
}

private object MathFonts {
	private var initialized = false

	fun ensure(context: Context) {
		if (initialized) return
		// The AAR also declares a ContentProvider that calls this; doing it
		// lazily makes a preview or a test that never starts the provider work
		// too. It only loads the bundled fonts from assets.
		JLatexMathAndroid.init(context.applicationContext)
		initialized = true
	}
}

/**
 * Turns the image links produced by [LatexMarkdown] into rendered formulas.
 *
 * Returning null for any other URL keeps the renderer's default behaviour for
 * real images (currently a placeholder; the app's assistant text has no image
 * loader, by design -- a reply must not fetch a URL).
 */
@Composable
internal fun rememberMathImageTransformer(): ImageTransformer {
	val context = LocalContext.current
	val density = LocalDensity.current
	val inlineSize = with(density) { 18.sp.toPx() }
	val displaySize = with(density) { 22.sp.toPx() }
	val color = MaterialTheme.colorScheme.onSurface.toArgb()

	return remember(context, inlineSize, displaySize, color) {
		object : ImageTransformer {
			@Composable
			override fun transform(link: String): ImageData? {
				val math = parseMathUrl(link) ?: return null
				MathFonts.ensure(context)
				// The drawable depends only on the formula and the theme-derived
				// sizes, not on the reply around it. It used to be rebuilt on
				// every recomposition of the row -- a full TeX parse and font
				// metrics per formula, several times a second while streaming.
				val drawable = remember(link, inlineSize, displaySize, color) {
					runCatching {
						JLatexMathDrawable.builder(math.latex)
							.textSize(if (math.display) displaySize else inlineSize)
							.color(color)
							.align(JLatexMathDrawable.ALIGN_LEFT)
							.build()
					}.onFailure {
						// No formula text in the log: diagnostics deliberately do
						// not record answer content.
						Diag.w("latex", "render failed (${it.javaClass.simpleName})")
					}.getOrNull()
				} ?: return null

				return ImageData(
					painter = MathPainter(drawable),
					modifier = Modifier,
					contentDescription = math.latex,
					alignment = Alignment.CenterStart,
					contentScale = ContentScale.Fit,
				)
			}
		}
	}
}