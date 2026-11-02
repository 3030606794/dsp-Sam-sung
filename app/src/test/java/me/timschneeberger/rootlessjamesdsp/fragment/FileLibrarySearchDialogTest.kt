package me.timschneeberger.rootlessjamesdsp.fragment

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.OpenableColumns
import android.view.MenuItem
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.fakes.RoboCursor
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h820dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FileLibrarySearchDialogTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<Host>? = null
    private val host get() = controller!!.get()
    private val preference get() = (host.supportFragmentManager.findFragmentByTag("preferences") as LibraryHost).library
    private val dialog get() = (host.supportFragmentManager.findFragmentByTag("library") as FileLibraryDialogFragment).dialog as AlertDialog
    private val list get() = dialog.listView
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun search(text: String) { dialog.findViewById<EditText>(R.id.filelibrary_search)!!.setText(text); layout() }
    private fun entries() = (0 until list.adapter.count).map { list.adapter.getItem(it) as FileLibraryDialogFragment.Entry }
    private fun source(type: String, name: String) = File(app.getExternalFilesDir(null), "$type/$name")
    private val identity = "SR_44100:1,0,0,0,0\nSR_48000:1,0,0,0,0\n"

    private fun launch(type: String = "DDC", selected: String = "$type/Z-current.vdc") {
        source(type, "").mkdirs()
        val extension = if (type == "DDC") "vdc" else "irs"
        repeat(80) { source(type, "A-${it.toString().padStart(3, '0')}.$extension").writeText(identity) }
        source(type, "Z-current.$extension").writeText(identity)
        source(type, "Keep.$extension").writeText(identity)
        controller = Robolectric.buildActivity(Host::class.java, Intent(app, Host::class.java).putExtra("type", type)).setup().visible()
        preference.value = selected
        preference.showDialog()
        idle(); layout()
    }

    private fun layout(height: Int = 620) {
        repeat(2) {
            idle()
            dialog.window!!.decorView.apply {
                measure(View.MeasureSpec.makeMeasureSpec(288, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
                layout(0, 0, measuredWidth, measuredHeight)
            }
        }
        idle()
    }

    private fun click(index: Int) {
        val adapter = list.adapter
        assertTrue(list.performItemClick(adapter.getView(index, null, list), index, adapter.getItemId(index)))
        idle()
    }

    private fun contextAction(actionId: Int) {
        val view = requireNotNull(list.getChildAt(0))
        assertTrue(list.onItemLongClickListener.onItemLongClick(list, view, 0, list.adapter.getItemId(0)))
        idle()
        fun findList(view: View): ListView? = if (view is ListView) view else if (view is ViewGroup)
            (0 until view.childCount).firstNotNullOfOrNull { findList(view.getChildAt(it)) } else null
        val popup = requireNotNull(shadowOf(app).latestPopupWindow)
        val menu = requireNotNull(findList(popup.contentView))
        val index = (0 until menu.adapter.count).first { (menu.adapter.getItem(it) as MenuItem).itemId == actionId }
        assertTrue(menu.performItemClick(menu.adapter.getView(index, null, menu), index, menu.adapter.getItemId(index)))
        idle()
    }

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        idle()
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun openingLocatesSavedSelectionAndSearchClickUsesFullOriginalPath() {
        launch()
        val selected = entries().indexOfFirst { it.value == preference.value }
        assertEquals(selected, list.checkedItemPosition)
        assertTrue("The saved file must be visible on entry", selected in list.firstVisiblePosition..list.lastVisiblePosition)
        val originalId = list.adapter.getItemId(selected)
        search("z-CURRENT.vdc")
        assertEquals(1, list.adapter.count)
        assertEquals(originalId, list.adapter.getItemId(0))
        assertEquals(0, list.checkedItemPosition)
        preference.value = "DDC/Keep.vdc"
        click(0)
        assertEquals("DDC/Z-current.vdc", preference.value)
    }

    @Test fun locateClearsAFilterHidingTheCurrentFileIncludingAnEmptyResult() {
        launch()
        search("Keep")
        assertEquals(ListView.INVALID_POSITION, list.checkedItemPosition)
        assertEquals("DDC/Z-current.vdc", preference.value)
        search("不存在的文件")
        assertEquals(0, list.adapter.count)
        assertEquals(View.VISIBLE, dialog.findViewById<View>(R.id.filelibrary_search_empty)!!.visibility)
        dialog.findViewById<View>(R.id.filelibrary_locate)!!.performClick()
        layout()
        assertEquals("", dialog.findViewById<EditText>(R.id.filelibrary_search)!!.text.toString())
        assertEquals("DDC/Z-current.vdc", entries()[list.checkedItemPosition].value)
        assertTrue(list.checkedItemPosition in list.firstVisiblePosition..list.lastVisiblePosition)
        // A later resize must not pull the list back after deliberate navigation.
        assertTrue(list.requestFocus())
        list.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
        list.setSelectionFromTop(0, 0)
        layout()
        assertEquals(0, list.firstVisiblePosition)
        layout(height = 540)
        assertEquals(0, list.firstVisiblePosition)
        assertTrue(list.checkedItemPosition > list.lastVisiblePosition)
    }

    @Test fun filteredRenameAndDeleteOnlyTouchTheChosenFileAndResetActiveDdc() {
        launch()
        search("Z-current")
        contextAction(R.id.rename_selection)
        val rename = ShadowDialog.getLatestDialog() as AlertDialog
        rename.findViewById<EditText>(android.R.id.text1)!!.setText("Z-renamed")
        rename.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle(); layout()
        assertFalse(source("DDC", "Z-current.vdc").exists())
        assertTrue(source("DDC", "Z-renamed.vdc").exists())
        assertTrue(source("DDC", "A-000.vdc").exists())
        assertEquals("DDC/Z-renamed.vdc", preference.value)
        assertEquals(0, list.adapter.count)
        dialog.findViewById<View>(R.id.filelibrary_locate)!!.performClick(); layout()
        assertEquals("DDC/Z-renamed.vdc", entries()[list.checkedItemPosition].value)
        preference.sharedPreferences!!.edit().putBoolean(app.getString(R.string.key_ddc_enable), true).commit()
        search("Z-renamed")
        contextAction(R.id.delete_selection)
        layout()
        assertFalse(source("DDC", "Z-renamed.vdc").exists())
        assertTrue(source("DDC", "Keep.vdc").exists())
        assertTrue(source("DDC", "A-000.vdc").exists())
        assertEquals("", preference.value)
        assertFalse(preference.sharedPreferences!!.getBoolean(app.getString(R.string.key_ddc_enable), true))
        assertEquals(ListView.INVALID_POSITION, list.checkedItemPosition)
        assertEquals(0, list.adapter.count)
    }

    @Test fun importDuringSearchKeepsTheFilterAndSelectingTheNewResultUsesItsPath() {
        launch()
        search("Imported")
        val uri = Uri.parse("content://file-library-test/imported")
        val resolver = shadowOf(app.contentResolver)
        resolver.setCursor(uri, RoboCursor().apply {
            setColumnNames(listOf(OpenableColumns.DISPLAY_NAME))
            setResults(arrayOf(arrayOf<Any>("Imported.vdc")))
        })
        resolver.registerInputStreamSupplier(uri) { identity.byteInputStream() }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        idle()
        val request = shadowOf(host).nextStartedActivityForResult
        host.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(uri))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        layout()
        assertTrue(source("DDC", "Imported.vdc").exists())
        assertEquals("Imported", dialog.findViewById<EditText>(R.id.filelibrary_search)!!.text.toString())
        assertEquals(listOf("DDC/Imported.vdc"), entries().map { it.value.toString() })
        assertEquals("DDC/Z-current.vdc", preference.value)
        click(0)
        assertEquals("DDC/Imported.vdc", preference.value)
    }

    @Test fun impulseLibrarySupportsTheSameSearchWithoutChangingSelection() {
        launch("Convolver", "Convolver/Z-current.irs")
        val selected = entries().indexOfFirst { it.value == preference.value }
        assertEquals(selected, list.checkedItemPosition)
        search(".IRS")
        assertTrue(entries().all { it.value.toString().endsWith(".irs", ignoreCase = true) })
        search("Keep")
        click(0)
        assertEquals("Convolver/Keep.irs", preference.value)
    }

    @Test fun compactLayoutKeepsSearchLocateAndImportVisibleAtLargeFont() {
        RuntimeEnvironment.setFontScale(1.5f)
        launch()
        for (id in listOf(R.id.filelibrary_search, R.id.filelibrary_locate)) {
            val view = dialog.findViewById<View>(id)!!
            assertTrue(view.isShown)
            assertTrue(view.width > 0 && view.height >= 48)
        }
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isShown)
        assertTrue(list.height > 0)
        val decor = dialog.window!!.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        try {
            decor.draw(Canvas(bitmap))
            val output = File("build/reports/filelibrary-search").apply { mkdirs() }
            File(output, "ddc-search-320dp-150font.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    class Host : FragmentActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_RootlessJamesDSP)
            super.onCreate(state)
            setContentView(FrameLayout(this).apply { id = R.id.parentPanel })
            if (state == null) supportFragmentManager.beginTransaction().add(R.id.parentPanel, LibraryHost(), "preferences").commitNow()
        }
    }

    class LibraryHost : PreferenceFragmentCompat() {
        lateinit var library: FileLibraryPreference
        override fun onCreatePreferences(state: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = "file_library_search_test"
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
            library = FileLibraryPreference(requireContext(), null).apply {
                key = "library"
                title = "文件库"
                dialogTitle = "文件库"
                type = requireActivity().intent.getStringExtra("type")!!
            }
            preferenceScreen.addPreference(library)
        }
        @Suppress("DEPRECATION")
        override fun onDisplayPreferenceDialog(preference: Preference) {
            FileLibraryDialogFragment.newInstance(preference.key).apply {
                setTargetFragment(this@LibraryHost, 0)
            }.show(parentFragmentManager, "library")
        }
    }
}
