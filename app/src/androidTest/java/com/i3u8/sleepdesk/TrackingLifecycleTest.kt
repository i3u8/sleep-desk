package com.i3u8.sleepdesk

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.i3u8.sleepdesk.data.SessionStore
import org.hamcrest.Matcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on the dedicated no-audio emulator, never a personal device with live room audio. */
@RunWith(AndroidJUnit4::class)
class TrackingLifecycleTest {
    @get:Rule val microphonePermission = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test fun stopAndImmediateRestartCannotLetOldCleanupEndTheNewNight() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SessionStore(context)
        store.stop()
        store.clearAll()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                awaitState(context.getString(R.string.btn_start)) { store.loadCurrent() == null }
                onView(withId(R.id.btnToggle)).perform(click())
                awaitState(context.getString(R.string.btn_stop)) { store.loadCurrent() != null }
                val first = store.loadCurrent()!!.id
                onView(withId(R.id.btnToggle)).perform(click())
                awaitState(context.getString(R.string.btn_start)) { store.loadCurrent() == null }
                onView(withId(R.id.btnToggle)).perform(click())
                awaitState(context.getString(R.string.btn_stop)) { store.loadCurrent() != null }
                val second = store.loadCurrent()!!.id
                assertNotEquals(first, second)
                onView(isRoot()).perform(object : ViewAction {
                    override fun getConstraints(): Matcher<View> = isRoot()
                    override fun getDescription() = "Allow any old service cleanup to finish"
                    override fun perform(uiController: UiController, view: View) {
                        uiController.loopMainThreadForAtLeast(1200)
                    }
                })
                assertEquals(second, store.loadCurrent()!!.id)
                onView(withId(R.id.btnToggle)).perform(click())
                awaitState(context.getString(R.string.btn_start)) { store.loadCurrent() == null }
                assertTrue(store.loadHistory().map { it.id }.containsAll(listOf(first, second)))
            } finally {
                scenario.onActivity {
                    if (store.loadCurrent() != null) it.startService(
                        Intent(it, SleepTrackingService::class.java).setAction(SleepTrackingService.ACTION_STOP)
                    )
                }
            }
        }
    }

    private fun awaitState(text: String, predicate: () -> Boolean) {
        onView(isRoot()).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isRoot()
            override fun getDescription() = "Wait for tracking state $text"
            override fun perform(uiController: UiController, view: View) {
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (SystemClock.uptimeMillis() < deadline) {
                    val button = view.findViewById<TextView>(R.id.btnToggle)
                    if (predicate() && button?.text?.toString() == text) return
                    uiController.loopMainThreadForAtLeast(50)
                }
                throw AssertionError("Tracking state did not become $text")
            }
        })
    }
}
