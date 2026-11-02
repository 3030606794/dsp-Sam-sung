package me.timschneeberger.rootlessjamesdsp.peq.importer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.peq.PeqPresetCodec
import me.timschneeberger.rootlessjamesdsp.peq.PeqPresetStore
import me.timschneeberger.rootlessjamesdsp.peq.PeqState
import me.timschneeberger.rootlessjamesdsp.ui.glass.GlassAlertDialogBuilder
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/** Produces an independent favorite; it has no access to the active PEQ repository or switches. */
class GraphicEqImportDialog(
    private val context: Context,
    private val inflater: LayoutInflater,
    private val scope: CoroutineScope,
    private val readCurrent: () -> PeqState,
    private val readSampleRate: () -> Double,
    private val onPickDocument: () -> Unit,
    private val onSaved: () -> Unit,
    private val store: PeqPresetStore = PeqPresetStore(context),
    private val fitTask: (GraphicEqCurve, PeqState, GraphicEqFitOptions,
        (GraphicEqFitProgress) -> Unit, () -> Boolean) -> GraphicEqFitResult = { curve, state, options, progress, cancelled ->
        GraphicEqFitter.fit(curve, state, options, progress, cancelled)
    }
) {
    private data class Preview(val result: GraphicEqFitResult, val options: GraphicEqFitOptions, val revision: Long)
    private var mainDialog: AlertDialog? = null
    private var confirmation: AlertDialog? = null
    private var view: View? = null
    private var fitJob: Job? = null
    private var cancelFlag: AtomicBoolean? = null
    private var hostCompletion: DisposableHandle? = null
    private var revision = 0L
    private var documentRequest = 0L
    private var preview: Preview? = null
    private var maxBands = 30
    private var targetError = 0.5

    fun show() {
        if (mainDialog?.isShowing == true) return
        if (scope.coroutineContext[Job]?.isActive == false) return
        val content = inflater.inflate(R.layout.peq_graphiceq_import_dialog, null)
        view = content
        maxBands = 30
        targetError = 0.5
        val dialog = GlassAlertDialogBuilder(context).setTitle(R.string.geq_import_title)
            .setView(content).setPositiveButton(R.string.geq_import_save, null)
            .setNegativeButton(R.string.close, null).create()
        mainDialog = dialog
        val text = content.findViewById<TextInputEditText>(R.id.geq_import_text)
        text.doAfterTextChanged {
            content.findViewById<TextInputLayout>(R.id.geq_import_text_box).error = null
            invalidatePreview()
        }
        content.findViewById<TextInputEditText>(R.id.geq_import_name).doAfterTextChanged {
            content.findViewById<TextInputLayout>(R.id.geq_import_name_box).error = null
            documentRequest++
            // A name edit does not change the response, but it invalidates a pending confirmation.
            confirmation?.dismiss()
        }
        content.findViewById<View>(R.id.geq_import_pick).setOnClickListener {
            try { onPickDocument() } catch (error: Exception) {
                status(context.getString(R.string.geq_import_document_error, message(error)))
            }
        }
        val advanced = content.findViewById<MaterialButton>(R.id.geq_import_advanced)
        advanced.setOnClickListener {
            val panel = content.findViewById<View>(R.id.geq_import_advanced_content)
            panel.isVisible = !panel.isVisible
            advanced.setText(if (panel.isVisible) R.string.geq_import_advanced_collapse else R.string.geq_import_advanced_expand)
        }
        content.findViewById<ChipGroup>(R.id.geq_import_bands).setOnCheckedStateChangeListener { _, ids ->
            maxBands = when (ids.firstOrNull()) {
                R.id.geq_import_band_10 -> 10
                R.id.geq_import_band_20 -> 20
                R.id.geq_import_band_60 -> 60
                R.id.geq_import_band_130 -> 130
                else -> 30
            }
            invalidatePreview()
            updateOptions()
        }
        content.findViewById<ChipGroup>(R.id.geq_import_errors).setOnCheckedStateChangeListener { _, ids ->
            targetError = when (ids.firstOrNull()) {
                R.id.geq_import_error_025 -> 0.25
                R.id.geq_import_error_010 -> 0.1
                else -> 0.5
            }
            invalidatePreview()
            updateOptions()
        }
        content.findViewById<View>(R.id.geq_import_fit).setOnClickListener { startFit() }
        content.findViewById<View>(R.id.geq_import_cancel_fit).setOnClickListener {
            invalidatePreview()
            status(context.getString(R.string.geq_import_cancelled))
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { confirmSave() }
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnDismissListener {
            if (mainDialog === dialog) {
                stopCalculation()
                confirmation?.dismiss()
                confirmation = null
                preview = null
                view = null
                mainDialog = null
                hostCompletion?.dispose()
                hostCompletion = null
            }
        }
        updateOptions()
        dialog.show()
        hostCompletion = scope.coroutineContext[Job]?.invokeOnCompletion {
            Handler(Looper.getMainLooper()).post { if (mainDialog === dialog) dismissAll() }
        }
    }

    /** Late document results are ignored after the host closes this importer. */
    fun setDocument(text: String, suggestedName: String) {
        if (mainDialog?.isShowing != true) return
        val content = view ?: return
        content.findViewById<TextInputEditText>(R.id.geq_import_text).setText(text)
        val stem = suggestedName.substringBeforeLast('.', suggestedName).trim()
        if (stem.isNotBlank()) content.findViewById<TextInputEditText>(R.id.geq_import_name).setText(stem)
    }

    /** Bind asynchronous picker/IO work to the exact window and input it was requested for. */
    fun documentReceiver(): (String, String) -> Unit {
        val requestedDialog = mainDialog
        val inputRevision = revision
        val request = ++documentRequest
        return { text, name ->
            if (requestedDialog != null && mainDialog === requestedDialog && requestedDialog.isShowing &&
                inputRevision == revision && request == documentRequest) {
                // A receiver is single-use, including when the loaded text happens to be identical.
                documentRequest++
                setDocument(text, name)
            }
        }
    }

    fun dismissAll() {
        stopCalculation()
        confirmation?.dismiss()
        confirmation = null
        mainDialog?.dismiss()
        hostCompletion?.dispose()
        hostCompletion = null
    }

    private fun updateOptions() {
        view?.findViewById<TextView>(R.id.geq_import_options_summary)?.text =
            context.getString(R.string.geq_import_options, maxBands, number(targetError))
    }

    private fun stopCalculation() {
        revision++
        cancelFlag?.set(true)
        cancelFlag = null
        fitJob?.cancel()
        fitJob = null
    }

    private fun invalidatePreview() {
        val hadResult = preview != null || fitJob != null
        stopCalculation()
        preview = null
        confirmation?.dismiss()
        confirmation = null
        view?.findViewById<View>(R.id.geq_import_result)?.isVisible = false
        setRunning(false)
        mainDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        status(if (hadResult) context.getString(R.string.geq_import_changed) else null)
    }

    private fun startFit() {
        val content = view ?: return
        val text = content.findViewById<TextInputEditText>(R.id.geq_import_text).text?.toString().orEmpty()
        invalidatePreview()
        if (text.isBlank()) {
            content.findViewById<TextInputLayout>(R.id.geq_import_text_box).error = context.getString(R.string.geq_import_invalid_text)
            return
        }
        val sampleRate = runCatching { readSampleRate() }.getOrDefault(Double.NaN)
        if (!sampleRate.isFinite() || sampleRate !in 8000.0..768000.0) {
            status(context.getString(R.string.geq_import_invalid_rate))
            return
        }
        val options = GraphicEqFitOptions(maxBands = maxBands, targetErrorDb = targetError,
            sampleRate = sampleRate, minFrequency = 20.0, maxFrequency = minOf(20000.0, sampleRate / 2.0))
        val baseState = try { readCurrent() } catch (error: Exception) {
            status(context.getString(R.string.geq_import_failed, message(error)))
            return
        }
        val token = revision
        val cancelled = AtomicBoolean(false)
        cancelFlag = cancelled
        status(null)
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(content.windowToken, 0)
        setRunning(true)
        content.findViewById<LinearProgressIndicator>(R.id.geq_import_progress).progress = 0
        content.findViewById<TextView>(R.id.geq_import_progress_text).text = context.getString(R.string.geq_import_fitting, 0)
        fitJob = scope.launch(Dispatchers.Main.immediate) {
            // Conflation keeps a fast numerical loop from flooding the main-thread message queue.
            val updates = Channel<GraphicEqFitProgress>(Channel.CONFLATED)
            val progressJob = launch {
                for (progress in updates) {
                    if (token == revision && mainDialog?.isShowing == true) {
                        val fraction = progress.fraction.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
                        content.findViewById<LinearProgressIndicator>(R.id.geq_import_progress).progress = (fraction * 1000).roundToInt()
                        content.findViewById<TextView>(R.id.geq_import_progress_text).text =
                            context.getString(R.string.geq_import_fitting, (fraction * 100).roundToInt())
                    }
                }
            }
            try {
                val fitted = withContext(Dispatchers.Default) {
                    val worker = coroutineContext
                    val curve = GraphicEqParser.parse(text)
                    fitTask(curve, baseState, options, { updates.trySend(it) }, { cancelled.get() || !worker.isActive })
                }
                if (token == revision && !cancelled.get() && mainDialog?.isShowing == true) {
                    preview = Preview(fitted, options, token)
                    renderPreview(preview!!)
                }
            } catch (_: CancellationException) {
                // Closing, replacing the text, and explicit cancellation all invalidate the token first.
            } catch (error: Exception) {
                if (token == revision && mainDialog?.isShowing == true)
                    status(context.getString(R.string.geq_import_failed, message(error)))
            } finally {
                updates.close()
                progressJob.cancel()
                if (token == revision) {
                    fitJob = null
                    cancelFlag = null
                    setRunning(false)
                }
            }
        }
    }

    private fun renderPreview(value: Preview) {
        val content = view ?: return
        val result = value.result
        val options = value.options
        val summary = context.getString(R.string.geq_import_summary,
            result.meanAbsoluteErrorDb, result.rmsErrorDb, result.maxErrorDb, number(result.maxErrorFrequency),
            result.state.bands.size, options.maxBands, number(options.sampleRate),
            number(result.frequencies.firstOrNull() ?: options.minFrequency),
            number(result.frequencies.lastOrNull() ?: options.maxFrequency), result.elapsedMillis / 1000.0)
        content.findViewById<TextView>(R.id.geq_import_summary).text = summary
        content.findViewById<GraphicEqFitChart>(R.id.geq_import_chart).setCurves(result.frequencies,
            result.targetDb, result.fittedDb, context.getString(R.string.geq_import_chart_description, summary))
        val notes = buildList {
            if (result.clippedPointCount > 0) add(context.getString(R.string.geq_import_excluded, result.clippedPointCount))
            if (result.offsetBandCount > 0) add(context.getString(R.string.geq_import_offsets, result.offsetBandCount))
        }
        content.findViewById<TextView>(R.id.geq_import_result_note).apply {
            text = notes.joinToString("\n")
            isVisible = notes.isNotEmpty()
        }
        content.findViewById<View>(R.id.geq_import_result).isVisible = true
        content.findViewById<MaterialButton>(R.id.geq_import_fit).setText(R.string.geq_import_refit)
        status(when {
            result.targetMet -> context.getString(R.string.geq_import_target_met, number(options.targetErrorDb))
            result.state.bands.size >= options.maxBands -> context.getString(R.string.geq_import_limit_unmet, options.maxBands, number(options.targetErrorDb))
            else -> context.getString(R.string.geq_import_target_unmet, number(options.targetErrorDb))
        })
        mainDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
        content.post {
            if (preview === value && mainDialog?.isShowing == true)
                (content as? ScrollView)?.smoothScrollTo(0, content.findViewById<View>(R.id.geq_import_status).top)
        }
    }

    private fun setRunning(running: Boolean) {
        view?.findViewById<View>(R.id.geq_import_progress_group)?.isVisible = running
        view?.findViewById<View>(R.id.geq_import_fit)?.isEnabled = !running
    }

    private fun usable(value: Preview): Boolean {
        if (mainDialog?.isShowing != true || preview !== value || revision != value.revision) return false
        val currentRate = runCatching { readSampleRate() }.getOrDefault(Double.NaN)
        if (!currentRate.isFinite() || abs(currentRate - value.options.sampleRate) > 0.01) {
            invalidatePreview()
            status(context.getString(R.string.geq_import_rate_changed))
            return false
        }
        return true
    }

    private fun confirmSave() {
        val value = preview ?: return
        if (!usable(value)) return
        val content = view ?: return
        val box = content.findViewById<TextInputLayout>(R.id.geq_import_name_box)
        val name = try { PeqPresetCodec.normalizeName(content.findViewById<TextInputEditText>(R.id.geq_import_name).text?.toString().orEmpty()) }
        catch (_: IllegalArgumentException) { box.error = context.getString(R.string.geq_import_name_invalid); return }
        try {
            val saved = store.load()
            if (saved.size >= PeqPresetStore.MAX_PRESETS) { status(context.getString(R.string.geq_import_full)); return }
            if (saved.any { it.name == name }) { box.error = context.getString(R.string.geq_import_duplicate); return }
        } catch (error: Exception) { status(context.getString(R.string.geq_import_save_error, message(error))); return }
        confirmation?.dismiss()
        val dialog = GlassAlertDialogBuilder(context).setTitle(R.string.geq_import_confirm_title)
            .setMessage(context.getString(R.string.geq_import_confirm, name, value.result.state.bands.size) +
                if (value.result.targetMet) "" else "\n\n${context.getString(R.string.geq_import_confirm_unmet)}")
            .setPositiveButton(R.string.geq_import_save, null).setNegativeButton(android.R.string.cancel, null).create()
        confirmation = dialog
        dialog.setOnDismissListener { if (confirmation === dialog) confirmation = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (confirmation !== dialog || !dialog.isShowing) return@setOnClickListener
                if (!usable(value)) { dialog.dismiss(); return@setOnClickListener }
                try {
                    // Store performs its own atomic capacity/name checks again before writing.
                    store.save(name, value.result.state)
                } catch (error: Exception) {
                    dialog.dismiss()
                    status(context.getString(R.string.geq_import_save_error, message(error)))
                    return@setOnClickListener
                }
                dismissAll()
                Toast.makeText(context, R.string.geq_import_saved, Toast.LENGTH_LONG).show()
                onSaved()
            }
        }
        dialog.show()
    }

    private fun status(text: String?) {
        view?.findViewById<TextView>(R.id.geq_import_status)?.apply {
            this.text = text.orEmpty()
            isVisible = !text.isNullOrEmpty()
        }
    }

    private fun message(error: Exception) = error.localizedMessage?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.geq_import_unknown_error)

    private fun number(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(java.util.Locale.getDefault(), "%.2f", value).trimEnd('0').trimEnd('.', ',')
}
