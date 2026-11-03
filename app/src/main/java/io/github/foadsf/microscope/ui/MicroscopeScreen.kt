package io.github.foadsf.microscope.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.foadsf.microscope.MicroscopeViewModel
import io.github.foadsf.microscope.TorchMode
import io.github.foadsf.microscope.UiState
import io.github.foadsf.microscope.gl.Filter
import io.github.foadsf.microscope.gl.PreviewView
import io.github.foadsf.microscope.gl.RenderParams
import io.github.foadsf.microscope.imaging.CaptureMode
import io.github.foadsf.microscope.imaging.Optics
import kotlinx.coroutines.delay
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

@Composable
fun MicroscopeScreen(vm: MicroscopeViewModel, onRequestPermission: () -> Unit) {
    val s by vm.ui.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        when {
            s.fatal != null -> Message(s.fatal!!)
            !s.hasPermission -> PermissionPrompt(onRequestPermission)
            else -> Column(Modifier.fillMaxSize()) {
                if (s.showInfo) InfoDialog(vm)
                // The live view keeps the sensor's exact aspect ratio (so what is seen is what is
                // captured) and takes whatever height the controls leave.
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                    Viewfinder(s, vm)
                }
                Controls(s, vm)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Live view
// ---------------------------------------------------------------------------------------------

@Composable
private fun Viewfinder(s: UiState, vm: MicroscopeViewModel) {
    val g = s.geometry
    val aspect = g?.uprightAspect ?: 0.75f
    var tap by remember { mutableStateOf<Offset?>(null) }
    var calibrating by remember { mutableStateOf(false) }
    val busy = s.processing != null

    Box(
        Modifier
            .aspectRatio(aspect)
            .background(Color.Black)
            .pointerInput(busy) {
                if (busy) return@pointerInput
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f) vm.setZoom(vm.ui.value.zoom * zoom)
                    if (pan != Offset.Zero) vm.pan(pan.x / size.width, pan.y / size.height)
                }
            }
            .pointerInput(busy) {
                if (busy) return@pointerInput
                detectTapGestures(
                    onTap = { o ->
                        tap = o
                        vm.focusAt(o.x / size.width, o.y / size.height)
                    },
                    onDoubleTap = { vm.resetView() },
                )
            },
    ) {
        CameraSurface(s, vm)
        Overlay(s)
        if (s.measuring) RulerOverlay(s, vm)
        tap?.let { FocusReticle(it) { tap = null } }
        TopBar(s, vm)
        TorchLevel(s, vm)
        if (s.measuring) MeasureBar(s) { calibrating = true }
        ToolButtons(s, vm, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp))
        if (s.showExposure) ExposurePanel(s, vm)
        NotePill(s, vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
    }
    if (calibrating) CalibrateDialog(s, vm) { calibrating = false }
}

@Composable
private fun CameraSurface(s: UiState, vm: MicroscopeViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var view by remember { mutableStateOf<PreviewView?>(null) }
    AndroidView(
        factory = { ctx -> PreviewView(ctx) { st -> vm.onTexture(st) }.also { view = it } },
        modifier = Modifier.fillMaxSize(),
        update = { v ->
            val g = s.geometry ?: return@AndroidView
            val preview = s.streams?.previewSize ?: return@AndroidView
            v.renderer.update(
                RenderParams(
                    rotation = g.lens.sensorOrientation,
                    glZoom = g.glZoom,
                    offsetX = g.glOffsetX,
                    offsetY = g.glOffsetY,
                    bufferWidth = preview.width,
                    bufferHeight = preview.height,
                    filter = s.filter,
                    peaking = s.peaking,
                    zebra = s.showExposure,
                ),
            )
        },
    )
    DisposableEffect(lifecycle, view) {
        val v = view
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> v?.onResume()
                Lifecycle.Event.ON_PAUSE -> v?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** Centre mark and an approximate scale bar. */
@Composable
private fun Overlay(s: UiState) {
    val measurer = rememberTextMeasurer()
    val fov = s.fieldOfViewUm
    val approx = if (s.scale?.calibrated == true) "" else "≈ "
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2, size.height / 2)
        val mark = Color(0x66FFFFFF)
        val r = 9.dp.toPx()
        drawLine(mark, c.copy(x = c.x - r), c.copy(x = c.x + r), 1.dp.toPx())
        drawLine(mark, c.copy(y = c.y - r), c.copy(y = c.y + r), 1.dp.toPx())

        if (fov != null && fov.isFinite() && fov > 0f) {
            val umPerPx = fov / size.width
            val length = Optics.niceFloor(0.28f * size.width * umPerPx)
            val px = length / umPerPx
            val label = measurer.measure(approx + Optics.formatLength(length), Type.Value.copy(fontSize = 12.sp))
            val pad = 8.dp.toPx()
            val x0 = 16.dp.toPx() + pad
            val y0 = size.height - 14.dp.toPx() - pad
            val tick = 5.dp.toPx()
            val stroke = 2.dp.toPx()
            // A dark backing so the bar reads on bright and dark specimens alike.
            drawRoundRect(
                Palette.Scrim,
                topLeft = Offset(x0 - pad, y0 - tick - 6.dp.toPx() - label.size.height - pad / 2),
                size = Size(max(px, label.size.width.toFloat()) + 2 * pad, label.size.height + tick * 2 + 6.dp.toPx() + pad * 1.5f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(pad, pad),
            )
            drawLine(Palette.Text, Offset(x0, y0), Offset(x0 + px, y0), stroke, StrokeCap.Round)
            drawLine(Palette.Text, Offset(x0, y0 - tick), Offset(x0, y0 + tick), stroke)
            drawLine(Palette.Text, Offset(x0 + px, y0 - tick), Offset(x0 + px, y0 + tick), stroke)
            drawText(label, topLeft = Offset(x0, y0 - tick - 4.dp.toPx() - label.size.height))
        }
    }
}

@Composable
private fun FocusReticle(at: Offset, onDone: () -> Unit) {
    val anim = remember(at) { Animatable(1.6f) }
    val alpha = remember(at) { Animatable(1f) }
    LaunchedEffect(at) {
        anim.animateTo(1f, tween(220))
        delay(700)
        alpha.animateTo(0f, tween(300))
        onDone()
    }
    Canvas(Modifier.fillMaxSize()) {
        val r = 30.dp.toPx() * anim.value
        drawCircle(Palette.Accent.copy(alpha = alpha.value), r, at, style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
private fun BoxScope.TopBar(s: UiState, vm: MicroscopeViewModel) {
    Row(
        Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Readout(s) { vm.showInfo(true) }
        Spacer(Modifier.weight(1f))
        val lens = s.lens
        if (lens != null && lens.apertures.size > 1) {
            Chip(text = "f/" + "%.1f".format(s.aperture ?: lens.apertures.last()), active = false, description = "Aperture") { vm.cycleAperture() }
            Spacer(Modifier.width(8.dp))
        }
        if (lens?.hasFlash == true) {
            RoundIcon(
                active = s.torchOn,
                badge = if (s.torchMode == TorchMode.AUTO) "A" else null,
                description = "Torch ${s.torchMode.name.lowercase()}",
                onClick = vm::cycleTorch,
            ) { drawBolt(it, s.torchMode != TorchMode.OFF) }
            Spacer(Modifier.width(8.dp))
        }
        RoundIcon(active = s.filter != Filter.COLOUR, badge = null, description = "Filter ${s.filter.label}", onClick = vm::cycleFilter) {
            drawFilter(it, s.filter)
        }
        Spacer(Modifier.width(8.dp))
        RoundIcon(active = s.peaking, badge = null, description = "Focus peaking", onClick = vm::togglePeaking) { drawPeaking(it) }
    }
}

@Composable
private fun Readout(s: UiState, onClick: () -> Unit) {
    val lens = s.lens ?: return
    val parts = ArrayList<String>()
    if (lens.manualFocus && lens.focusIsMetric && s.effectiveFocus > 0.05f) {
        parts += "WD ≈ " + "%.0f".format(Optics.distanceMm(s.effectiveFocus)) + " mm"
    }
    s.fieldOfViewUm?.let { parts += (if (s.scale?.calibrated == true) "FOV " else "FOV ≈ ") + Optics.formatLength(Optics.niceRound(it)) }
    if (parts.isEmpty()) parts += lens.label
    Text(
        parts.joinToString("  ·  ") + "  ⓘ",
        style = Type.Value.copy(fontSize = 12.sp),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Scrim)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Camera details" }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun NotePill(s: UiState, vm: MicroscopeViewModel, modifier: Modifier) {
    val note = s.note
    LaunchedEffect(note?.id) {
        if (note != null) {
            delay(2600)
            vm.clearNote(note.id)
        }
    }
    AnimatedVisibility(note != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(
            note?.text ?: "",
            style = Type.Value,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(50)).background(Palette.Scrim)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------------------------

@Composable
private fun Controls(s: UiState, vm: MicroscopeViewModel) {
    val busy = s.processing != null
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FocusControl(s, vm, enabled = !busy)
        ZoomControl(s, vm, enabled = !busy)
        Spacer(Modifier.height(2.dp))
        ModeTabs(s, vm, enabled = !busy)
        Spacer(Modifier.height(6.dp))
        ShutterRow(s, vm)
    }
}

@Composable
private fun FocusControl(s: UiState, vm: MicroscopeViewModel, enabled: Boolean) {
    val lens = s.lens
    val max = lens?.minFocusDiopters ?: 0f
    val usable = lens?.manualFocus == true && max > 0f
    val value = when {
        lens == null -> ""
        !usable -> "fixed"
        lens.focusIsMetric -> if (s.focus > 0.05f) "%.0f mm".format(Optics.distanceMm(s.focus)) else "∞"
        else -> "%.0f %%".format(100 * s.focus / max)
    }
    SliderRow(
        lead = {
            if (lens?.afAuto == true) {
                Chip("AF", active = false, description = "Autofocus at the centre", enabled = enabled) { vm.focusAt(0.5f, 0.5f) }
            } else {
                Text("FOCUS", style = Type.Label)
            }
        },
        value = value,
    ) {
        // Left is far, right is near: the microscope end of the range is under the thumb.
        Slider(
            value = s.focus.coerceIn(0f, max(max, 0.01f)),
            onValueChange = vm::setFocus,
            valueRange = 0f..max(max, 0.01f),
            enabled = enabled && usable,
            colors = sliderColors(),
            modifier = Modifier.semantics { contentDescription = "Focus, far to near" },
        )
    }
}

@Composable
private fun ZoomControl(s: UiState, vm: MicroscopeViewModel, enabled: Boolean) {
    val maxZ = MicroscopeViewModel.MAX_ZOOM
    val digital = s.geometry?.let { it.glZoom > 1.01f } == true
    SliderRow(
        lead = { Text("ZOOM", style = Type.Label) },
        value = "%.1f×".format(s.zoom),
        valueColor = if (digital) Palette.Warn else Palette.Text,
    ) {
        // Logarithmic: equal slider travel per doubling.
        Slider(
            value = (ln(s.zoom) / ln(maxZ)).coerceIn(0f, 1f),
            onValueChange = { vm.setZoom(maxZ.pow(it)) },
            enabled = enabled,
            colors = sliderColors(),
            modifier = Modifier.semantics { contentDescription = "Zoom" },
        )
    }
}

@Composable
private fun SliderRow(
    lead: @Composable () -> Unit,
    value: String,
    valueColor: Color = Palette.Text,
    slider: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(58.dp), contentAlignment = Alignment.CenterStart) { lead() }
        Box(Modifier.weight(1f).padding(horizontal = 6.dp)) { slider() }
        Text(value, style = Type.Value.copy(color = valueColor), textAlign = TextAlign.End, modifier = Modifier.width(62.dp))
    }
}

@Composable
private fun ModeTabs(s: UiState, vm: MicroscopeViewModel, enabled: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            for ((mode, name) in listOf(CaptureMode.PHOTO to "PHOTO", CaptureMode.SUPER_RES to "SUPER-RES", CaptureMode.FOCUS_STACK to "STACK")) {
                val selected = s.mode == mode
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled, role = Role.Tab) { vm.setMode(mode) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(name, style = Type.Tab.copy(color = if (selected) Palette.Accent else Palette.TextDim))
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.size(4.dp).clip(CircleShape).background(if (selected) Palette.Accent else Color.Transparent))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val caption = s.processing?.label ?: modeCaption(s, vm)
        Text(
            caption,
            style = Type.Small.copy(color = if (s.processing != null) Palette.Accent else Palette.TextDim),
            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                .clickable(enabled = enabled && s.mode != CaptureMode.PHOTO) { vm.cycleFrames() }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun modeCaption(s: UiState, vm: MicroscopeViewModel): String = when (s.mode) {
    CaptureMode.PHOTO -> "One frame, as the sensor sees it"
    CaptureMode.SUPER_RES -> {
        val roi = s.geometry?.let { g -> s.streams?.let { g.captureRoi(it.captureSize) } }
        val scale = roi?.let { io.github.foadsf.microscope.imaging.Pipeline.superResScale(it.width() * it.height()) } ?: 2
        "${s.burstFrames} frames · ${if (scale == 2) "2× resolution" else "denoise"}  ⟳"
    }
    CaptureMode.FOCUS_STACK -> {
        val plan = vm.stackPlan(s)
        val lens = s.lens
        val depth = if (lens != null && lens.focusIsMetric && plan.size > 1 && plan.last() > 0.05f) {
            " · ≈ %.1f mm deep".format(Optics.distanceMm(plan.last()) - Optics.distanceMm(plan.first()))
        } else {
            ""
        }
        val height = if (lens != null && lens.manualFocus && lens.focusIsMetric) " + height map" else ""
        "${s.stackFrames} slices$depth$height  ⟳"
    }
}

@Composable
private fun ShutterRow(s: UiState, vm: MicroscopeViewModel) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        // Last capture
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Palette.SurfaceHigh).border(1.dp, Palette.Outline, CircleShape)
                .clickable(enabled = s.lastUri != null) {
                    val uri: Uri = s.lastUri ?: return@clickable
                    val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(view) }
                }
                .semantics { contentDescription = "Open last capture" },
        ) {
            s.thumbnail?.let {
                Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Shutter(s, vm)
        // Lens
        val lens = s.lens
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Palette.SurfaceHigh).border(1.dp, Palette.Outline, CircleShape)
                .clickable(enabled = s.lenses.size > 1 && s.processing == null) { vm.nextLens() }
                .semantics { contentDescription = "Switch lens, now ${lens?.label}" },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(lens?.equivalentMm?.takeIf { it > 0 }?.toString() ?: "–", style = Type.Value.copy(fontWeight = FontWeight.Bold))
                Text("mm", style = Type.Small.copy(fontSize = 9.sp))
            }
        }
    }
}

@Composable
private fun Shutter(s: UiState, vm: MicroscopeViewModel) {
    val p = s.processing
    val progress by animateFloatAsState(p?.progress ?: 0f, label = "progress")
    val ready = s.streams != null && p == null
    Box(
        Modifier.size(78.dp).clip(CircleShape).clickable(enabled = ready, role = Role.Button) { vm.shutter() }
            .semantics { contentDescription = "Capture" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            drawCircle(Palette.Text.copy(alpha = if (ready || p != null) 1f else 0.3f), size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
            if (p != null) {
                drawArc(
                    Palette.Accent, -90f, 360f * progress, false,
                    topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            val inner = size.minDimension / 2 - 8.dp.toPx()
            drawCircle(if (p != null) Palette.AccentDim else if (ready) Palette.Text else Palette.Outline, inner)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Small parts
// ---------------------------------------------------------------------------------------------

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Palette.Text,
    activeTrackColor = Palette.Accent,
    inactiveTrackColor = Palette.Outline,
    disabledThumbColor = Palette.Outline,
    disabledActiveTrackColor = Palette.Outline,
    disabledInactiveTrackColor = Palette.SurfaceHigh,
    // Stepped sliders show their value as text; tick dots only add clutter.
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
    disabledActiveTickColor = Color.Transparent,
    disabledInactiveTickColor = Color.Transparent,
)

@Composable
private fun Chip(text: String, active: Boolean, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        style = Type.Value.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (active) Palette.Background else Palette.Text),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (active) Palette.Accent else Palette.Scrim)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

@Composable
private fun RoundIcon(active: Boolean, badge: String?, description: String, onClick: () -> Unit, draw: DrawScope.(Color) -> Unit) {
    val tint = if (active) Palette.Background else Palette.Text
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(if (active) Palette.Accent else Palette.Scrim)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) { draw(tint) }
        if (badge != null) {
            Text(badge, style = Type.Small.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold, color = tint),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 7.dp, bottom = 5.dp))
        }
    }
}

private fun DrawScope.drawBolt(c: Color, filled: Boolean) {
    val w = size.width
    val h = size.height
    val p = Path().apply {
        moveTo(w * 0.58f, 0f); lineTo(w * 0.12f, h * 0.58f); lineTo(w * 0.48f, h * 0.58f)
        lineTo(w * 0.40f, h); lineTo(w * 0.88f, h * 0.40f); lineTo(w * 0.52f, h * 0.40f); close()
    }
    if (filled) drawPath(p, c) else drawPath(p, c, style = Stroke(1.5.dp.toPx()))
}

private fun DrawScope.drawFilter(c: Color, f: Filter) {
    val r = size.minDimension / 2
    drawCircle(c, r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
    when (f) {
        Filter.COLOUR -> drawArc(c, 90f, 180f, true, topLeft = Offset(1.dp.toPx(), 1.dp.toPx()), size = Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()))
        Filter.MONO -> drawCircle(c, r * 0.45f)
        Filter.EDGES -> drawCircle(c, r * 0.45f, style = Stroke(1.5.dp.toPx()))
    }
}

private fun DrawScope.drawPeaking(c: Color) {
    val w = size.width
    val h = size.height
    val k = w * 0.3f
    val st = 1.6.dp.toPx()
    for ((x, y, dx, dy) in listOf(listOf(0f, 0f, 1f, 1f), listOf(w, 0f, -1f, 1f), listOf(0f, h, 1f, -1f), listOf(w, h, -1f, -1f))) {
        drawLine(c, Offset(x, y), Offset(x + dx * k, y), st, StrokeCap.Round)
        drawLine(c, Offset(x, y), Offset(x, y + dy * k), st, StrokeCap.Round)
    }
    drawCircle(c, w * 0.12f, Offset(w / 2, h / 2))
}

/** Torch brightness, shown only while the torch is on and the device can dim it in a session. */
@Composable
private fun BoxScope.TorchLevel(s: UiState, vm: MicroscopeViewModel) {
    val lens = s.lens ?: return
    if (!s.torchOn || lens.torchStrengthMax <= 1) return
    Row(
        Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 58.dp, end = 12.dp)
            .clip(RoundedCornerShape(50)).background(Palette.Scrim).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(14.dp)) { drawBolt(Palette.Text, true) }
        Slider(
            value = s.torchLevel.toFloat(),
            onValueChange = { vm.setTorchLevel(Math.round(it)) },
            valueRange = 1f..lens.torchStrengthMax.toFloat(),
            colors = sliderColors(),
            modifier = Modifier.width(160.dp).padding(start = 8.dp).semantics { contentDescription = "Torch brightness" },
        )
    }
}

/** Everything the app detected about the phone and its lenses, with a copy button for bug reports. */
@Composable
private fun InfoDialog(vm: MicroscopeViewModel) {
    val context = LocalContext.current
    val text = remember { vm.diagnostics() }
    Dialog(onDismissRequest = { vm.showInfo(false) }) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(20.dp)).background(Palette.Surface)
                .border(1.dp, Palette.Outline, RoundedCornerShape(20.dp)).padding(20.dp),
        ) {
            Text("Camera details", style = Type.Value.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            Text("Detected from this phone. Paste it into a bug report.", style = Type.Small)
            Spacer(Modifier.height(12.dp))
            Text(
                text,
                style = Type.Small.copy(fontSize = 11.sp, color = Palette.Text, fontFamily = FontFamily.Monospace, lineHeight = 15.sp),
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Chip("Copy", active = false, description = "Copy camera details") {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("Microscope camera details", text))
                    vm.note("Copied")
                }
                Spacer(Modifier.width(10.dp))
                Chip("Close", active = true, description = "Close") { vm.showInfo(false) }
            }
        }
    }
}

/** Tools that act on the image: exposure and the measuring line. */
@Composable
private fun ToolButtons(s: UiState, vm: MicroscopeViewModel, modifier: Modifier) {
    val lens = s.lens ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (lens.evRange.first < lens.evRange.last) {
            val label = if (s.ev == 0) "EV" else "EV " + signed(s.ev * lens.evStep)
            Chip(label, active = s.showExposure, description = "Exposure compensation", onClick = vm::toggleExposure)
            Spacer(Modifier.width(8.dp))
        }
        RoundIcon(active = s.measuring, badge = null, description = "Measure", onClick = vm::toggleMeasure) { drawRuler(it) }
    }
}

private fun signed(v: Float) = (if (v > 0f) "+" else if (v < 0f) "−" else "±") + "%.1f".format(kotlin.math.abs(v))

/** Exposure compensation, with clipped highlights striped in the live view while it is open. */
@Composable
private fun BoxScope.ExposurePanel(s: UiState, vm: MicroscopeViewModel) {
    val lens = s.lens ?: return
    val range = lens.evRange
    Row(
        Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 60.dp)
            .clip(RoundedCornerShape(50)).background(Palette.Scrim).padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("EV", style = Type.Label)
        Slider(
            value = s.ev.toFloat(),
            onValueChange = { vm.setEv(Math.round(it)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            colors = sliderColors(),
            modifier = Modifier.width(180.dp).padding(horizontal = 8.dp).semantics { contentDescription = "Exposure compensation" },
        )
        Text(signed(s.ev * lens.evStep), style = Type.Value, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
    }
}

/** The measuring line: drag either end; its length is shown beside it. */
@Composable
private fun RulerOverlay(s: UiState, vm: MicroscopeViewModel) {
    val measurer = rememberTextMeasurer()
    val um = s.rulerUm
    val label = when {
        um == null -> "no scale: calibrate"
        s.scale?.calibrated == true -> Optics.formatMeasure(um)
        else -> "≈ " + Optics.formatMeasure(um)
    }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            val grab = 36.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val r = vm.ui.value.ruler
                val d0 = (down.position - Offset(r.x0 * size.width, r.y0 * size.height)).getDistance()
                val d1 = (down.position - Offset(r.x1 * size.width, r.y1 * size.height)).getDistance()
                // Only a touch on an end belongs to the ruler; anything else pans, zooms or focuses.
                val end = if (d0 <= d1 && d0 < grab) 0 else if (d1 < grab) 1 else return@awaitEachGesture
                down.consume()
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    vm.moveRuler(end, change.position.x / size.width, change.position.y / size.height)
                    change.consume()
                }
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = s.ruler
            val a = Offset(r.x0 * size.width, r.y0 * size.height)
            val b = Offset(r.x1 * size.width, r.y1 * size.height)
            val line = Palette.Warn
            drawLine(Color(0x99000000), a, b, 4.dp.toPx(), StrokeCap.Round)
            drawLine(line, a, b, 2.dp.toPx(), StrokeCap.Round)
            for (p in listOf(a, b)) {
                drawCircle(line.copy(alpha = 0.25f), 18.dp.toPx(), p)
                drawCircle(line, 18.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
                drawCircle(line, 2.5.dp.toPx(), p)
            }
            val text = measurer.measure(label, Type.Value.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
            val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
            val pad = 6.dp.toPx()
            val tl = Offset(
                (mid.x - text.size.width / 2).coerceIn(pad, size.width - text.size.width - pad),
                (mid.y - text.size.height - 16.dp.toPx()).coerceIn(pad, size.height - text.size.height - pad),
            )
            drawRoundRect(
                Palette.Scrim, topLeft = tl - Offset(pad, pad / 2),
                size = Size(text.size.width + 2 * pad, text.size.height + pad),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(pad, pad),
            )
            drawText(text, topLeft = tl)
        }
    }
}

/** Calibration state of the scale, with the way to calibrate it. */
@Composable
private fun BoxScope.MeasureBar(s: UiState, onCalibrate: () -> Unit) {
    val status = when {
        s.scale?.calibrated == true -> "Calibrated"
        s.calibration != null -> "Calibrated at another focus"
        s.scale != null -> "Estimated from the lens"
        else -> "No scale for this lens"
    }
    Row(
        Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 58.dp, start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            status, style = Type.Small.copy(color = Palette.Text),
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.Scrim).padding(horizontal = 12.dp, vertical = 7.dp),
        )
        Spacer(Modifier.width(8.dp))
        Chip("Calibrate", active = false, description = "Calibrate against a known length", onClick = onCalibrate)
    }
}

/** Asks what the measuring line spans on a reference (a ruler division, a pin pitch). */
@Composable
private fun CalibrateDialog(s: UiState, vm: MicroscopeViewModel, onDone: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDone) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.Surface)
                .border(1.dp, Palette.Outline, RoundedCornerShape(20.dp)).padding(20.dp),
        ) {
            Text("Calibrate scale", style = Type.Value.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(6.dp))
            Text(
                "Lay the line across a known length, such as divisions of a steel ruler, then enter it. " +
                    "The calibration is kept for this lens and holds at this focus setting.",
                style = Type.Small,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Known length (mm)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (s.calibration != null) {
                    Chip("Forget", active = false, description = "Forget the calibration") {
                        vm.clearCalibration()
                        onDone()
                    }
                    Spacer(Modifier.weight(1f))
                }
                Chip("Cancel", active = false, description = "Cancel", onClick = onDone)
                Spacer(Modifier.width(10.dp))
                Chip("Calibrate", active = true, description = "Calibrate") {
                    val mm = text.trim().replace(',', '.').toFloatOrNull()
                    if (mm != null && vm.calibrate(mm * 1000f)) onDone() else vm.note("Enter a length and make the line longer")
                }
            }
        }
    }
}

private fun DrawScope.drawRuler(c: Color) {
    val w = size.width
    val h = size.height
    val st = 1.5.dp.toPx()
    drawRect(c, topLeft = Offset(0f, h * 0.3f), size = Size(w, h * 0.4f), style = Stroke(st))
    for (i in 1..4) {
        val x = w * i / 5f
        drawLine(c, Offset(x, h * 0.3f), Offset(x, h * (if (i % 2 == 0) 0.55f else 0.45f)), st)
    }
}

// ---------------------------------------------------------------------------------------------
// Other screens
// ---------------------------------------------------------------------------------------------

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Microscope", style = Type.Value.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(12.dp))
        Text(
            "Turns your phone's cameras into a close-up inspection tool. It needs the camera; nothing leaves your phone.",
            style = Type.Small.copy(fontSize = 14.sp), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Chip("Allow camera", active = true, description = "Allow camera") { onRequest() }
        Spacer(Modifier.height(14.dp))
        Text(
            "Open app settings",
            style = Type.Small,
            modifier = Modifier.clickable {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }.padding(8.dp),
        )
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Type.Value, textAlign = TextAlign.Center)
    }
}
