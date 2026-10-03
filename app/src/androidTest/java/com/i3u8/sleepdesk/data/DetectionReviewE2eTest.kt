package com.i3u8.sleepdesk.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.i3u8.sleepdesk.MainActivity
import com.i3u8.sleepdesk.R
import com.i3u8.sleepdesk.SleepTrackingService
import com.i3u8.sleepdesk.audio.ClassificationStatus
import com.i3u8.sleepdesk.audio.ClipStatus
import com.i3u8.sleepdesk.audio.NightEvent
import com.i3u8.sleepdesk.audio.NightEventType
import com.i3u8.sleepdesk.ui.EventDetailBottomSheet
import com.i3u8.sleepdesk.ui.EventsAdapter
import com.i3u8.sleepdesk.ui.SegmentsAdapter
import com.i3u8.sleepdesk.ui.NightTimelineHeuristics
import org.hamcrest.Matchers.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import android.view.View
import android.widget.TextView
import org.hamcrest.Matcher

@RunWith(AndroidJUnit4::class)
class DetectionReviewE2eTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var store: SessionStore
    private var backup: ByteArray? = null
    private val files = mutableListOf<File>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var detailOpen = false
    private val animationSettings = linkedMapOf<String, String?>()

    private fun onView(matcher: Matcher<View>) = Espresso.onView(matcher).let {
        if (detailOpen) it.inRoot(isDialog()) else it
    }

    @Before fun setup() {
        for (name in listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale")) {
            animationSettings[name] = Settings.Global.getString(context.contentResolver, name)
            shell("settings put global $name 0")
        }
        store = SessionStore(context)
        backup = store.sessionsFile().takeIf { it.exists() }?.readBytes()
        store.sessionsFile().writeText("""{"running":false,"history":[]}""")
        SessionStore.invalidateCache()
    }

    @After fun teardown() {
        scenario?.close()
        files.forEach { it.delete() }
        backup?.let { store.sessionsFile().writeBytes(it) } ?: store.sessionsFile().delete()
        SessionStore.invalidateCache()
        animationSettings.forEach { (name, value) ->
            shell(if (value == null) "settings delete global $name" else "settings put global $name $value")
        }
    }

    private fun shell(command: String) {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    private fun saveValidationScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Unable to capture validation screenshot"
        }
        try {
            File(context.filesDir, name).outputStream().use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            screenshot.recycle()
        }
    }

    private fun candidate(session: SleepSession, id: String = "detected") = NightEvent(
        id, NightEventType.UNKNOWN, session.startMs, session.startMs + 1000, 0f,
        detectionConfidence = .85f, classificationStatus = ClassificationStatus.PENDING,
        clipStatus = ClipStatus.PENDING, sessionId = session.id
    )

    private fun launchDetail(session: SleepSession, eventId: String) {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        detailOpen = true
        scenario!!.onActivity { activity ->
            EventDetailBottomSheet.newInstance(store.loadSession(session.id)!!.events.single { it.id == eventId }, session.id)
                .showNow(activity.supportFragmentManager, EventDetailBottomSheet.TAG)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun detectedUnknownVisibleAndUpsertedWithoutDuplicates() {
        val session = store.startNew()
        val event = candidate(session)
        store.appendNightEvent(event, session.id)
        assertEquals(listOf(event.id), session.ensureSegments().single().eventIds)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        fun bindRows() {
            scenario!!.onActivity { activity ->
                activity.setContentView(RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity)
                    adapter = EventsAdapter(store.loadSession(session.id)!!.events.toList()) { }
                })
            }
        }
        bindRows()
        onView(withId(R.id.tvEventType)).check(matches(withText(R.string.event_unknown)))
        onView(withId(R.id.tvEventMeta)).check(matches(withText(containsString("分析中"))))
        scenario!!.onActivity { activity ->
            activity.setContentView(RecyclerView(activity).apply {
                layoutManager = LinearLayoutManager(activity)
                adapter = SegmentsAdapter(session.ensureSegments()) { }
            })
        }
        onView(withId(R.id.tvSegLabel)).check(matches(withText(R.string.event_unknown)))
        store.appendNightEvent(event.copy(revision = 1, clipStatus = ClipStatus.SAVED, clipRelativePath = wav(session.id)), session.id)
        store.appendNightEvent(event.copy(type = NightEventType.BREATHING, confidence = .7f,
            classificationStatus = ClassificationStatus.SUGGESTED, revision = 2), session.id)
        assertEquals(1, store.loadSession(session.id)!!.events.size)
        assertNotNull(store.loadSession(session.id)!!.events.single().clipRelativePath)
        bindRows()
        onView(withId(R.id.tvEventType)).check(matches(withText(R.string.event_breathing)))
        onView(withId(R.id.tvEventMeta)).check(matches(withText(containsString("模型建议"))))
    }

    @Test fun openPendingDetailRefreshesWithoutResettingReviewEdits() {
        val session = store.startNew()
        val event = candidate(session)
        store.appendNightEvent(event)
        launchDetail(session, event.id)
        onView(withId(R.id.checkReviewImportant)).perform(scrollTo(), click())
        store.appendNightEvent(event.copy(type = NightEventType.BREATHING, confidence = .6f,
            classificationStatus = ClassificationStatus.SUGGESTED,
            clipRelativePath = wav(session.id), clipStatus = ClipStatus.SAVED))
        onView(isRoot()).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isRoot()
            override fun getDescription() = "Wait for persisted classification to refresh"
            override fun perform(uiController: UiController, view: View) {
                repeat(40) {
                    val status = view.findViewById<TextView>(R.id.tvDetailStatus)
                    if (status?.text?.contains(context.getString(R.string.status_suggested)) == true) return
                    uiController.loopMainThreadForAtLeast(50)
                }
                throw AssertionError("Classification did not refresh")
            }
        })
        onView(withId(R.id.checkReviewImportant)).check(matches(isChecked()))
        onView(withId(R.id.btnPlayPause)).perform(scrollTo()).check(matches(isEnabled()))
        assertEquals(1, store.loadSession(session.id)!!.events.size)
    }

    @Test fun playbackPauseAndResume() {
        val session = store.startNew()
        val event = candidate(session).copy(clipRelativePath = wav(session.id), clipStatus = ClipStatus.SAVED,
            classificationStatus = ClassificationStatus.FAILED)
        store.appendNightEvent(event, session.id)
        launchDetail(session, event.id)
        onView(withId(R.id.btnPlayPause)).perform(scrollTo(), click())
        onView(withId(R.id.btnPlayPause)).check(matches(withText(R.string.btn_pause)))
        onView(withId(R.id.btnPlayPause)).perform(click())
        onView(withId(R.id.btnPlayPause)).check(matches(withText(R.string.btn_play)))
        onView(withId(R.id.btnPlayPause)).perform(click())
        onView(withId(R.id.btnPlayPause)).check(matches(withText(R.string.btn_pause)))
    }

    @Test fun uncertainCandidatesHaveSeparateSupportAndMissingClipFeedback() {
        val session = store.startNew()
        val event = candidate(session).copy(classificationStatus = ClassificationStatus.UNCERTAIN,
            confidence = .45f, classScores = mapOf("BREATHING" to .45f),
            suggestedTypes = listOf("BREATHING"), clipStatus = ClipStatus.SAVED,
            clipRelativePath = "audio_clips/${session.id}/missing.wav")
        store.appendNightEvent(event)
        launchDetail(session, event.id)
        onView(withId(R.id.tvDetailStatus)).check(matches(withText(containsString("待确认"))))
        onView(withId(R.id.tvDetailMeta)).check(matches(withText(containsString("检测支持度 85%"))))
        onView(withId(R.id.tvDetailMeta)).check(matches(withText(containsString("分类支持度 45%"))))
        onView(withId(R.id.tvDetailCandidates)).check(matches(withText(containsString("呼吸声"))))
        saveValidationScreenshot("validation-pending.png")
        onView(withId(R.id.btnPlayPause)).perform(scrollTo(), click())
        onView(withId(R.id.tvDetailMessage)).perform(scrollTo()).check(matches(withText(R.string.clip_missing)))
    }

    @Test fun shortPostRollIsVisibleButSavedClipStillPlays() {
        val session = store.startNew()
        val event = candidate(session).copy(
            classificationStatus = ClassificationStatus.UNCERTAIN,
            clipStatus = ClipStatus.SAVED,
            clipRelativePath = wav(session.id),
            features = mapOf(
                "context.PRE_ROLL_SHORT" to 0f,
                "context.POST_ROLL_SHORT" to 1f,
                "context.CLIP_LIMIT" to 1f
            )
        )
        store.appendNightEvent(event)
        SessionStore.invalidateCache()
        launchDetail(session, event.id)
        onView(withId(R.id.tvDetailStatus)).check(matches(withText(allOf(
            containsString(context.getString(R.string.clip_status_saved)),
            containsString(context.getString(R.string.clip_context_post_short)),
            containsString(context.getString(R.string.clip_context_limit)),
            not(containsString(context.getString(R.string.clip_context_pre_short)))
        ))))
        onView(withId(R.id.btnPlayPause)).perform(scrollTo(), click())
        onView(withId(R.id.btnPlayPause)).check(matches(withText(R.string.btn_pause)))
        onView(withId(R.id.btnPlayPause)).perform(click())
        onView(withId(R.id.btnPlayPause)).check(matches(withText(R.string.btn_play)))
        onView(withId(R.id.tvDetailStatus)).perform(scrollTo())
            .check(matches(withText(containsString(context.getString(R.string.clip_context_post_short)))))
    }

    @Test fun stopRecoversPendingAndDisabledRecordingRoundTrips() {
        val session = store.startNew()
        store.appendNightEvent(candidate(session, "saved").copy(clipRelativePath = wav(session.id)), session.id)
        store.appendNightEvent(candidate(session, "missing"), session.id)
        store.appendNightEvent(candidate(session, "disabled").copy(clipStatus = ClipStatus.DISABLED), session.id)
        store.stop()
        SessionStore.invalidateCache()
        val events = store.loadSession(session.id)!!.events.associateBy { it.id }
        events.values.forEach {
            assertEquals(ClassificationStatus.FAILED, it.classificationStatus)
            assertEquals("UNKNOWN", it.type)
            assertEquals("stopped_before_analysis", it.classificationReason)
        }
        assertEquals(ClipStatus.SAVED, events.getValue("saved").clipStatus)
        assertEquals(ClipStatus.FAILED, events.getValue("missing").clipStatus)
        assertEquals(ClipStatus.DISABLED, events.getValue("disabled").clipStatus)
        launchDetail(session, "disabled")
        onView(withId(R.id.tvDetailStatus)).check(matches(withText(containsString("录音：已关闭"))))
        onView(withId(R.id.btnPlayPause)).perform(scrollTo()).check(matches(not(isEnabled())))
    }

    @Test fun manualReviewSurvivesReopenAndAutomaticClassification() {
        val session = store.startNew()
        val event = candidate(session)
        store.appendNightEvent(event, session.id)
        launchDetail(session, event.id)
        onView(withId(R.id.spinnerReviewLabel)).perform(scrollTo(), click())
        onData(equalTo(context.getString(R.string.event_contact))).inRoot(isPlatformPopup()).perform(click())
        onView(withId(R.id.checkReviewImportant)).perform(scrollTo(), click())
        onView(withId(R.id.btnSaveReview)).perform(scrollTo(), click())
        scenario!!.close()
        scenario = null
        store.stop()
        store.appendNightEvent(event.copy(type = NightEventType.COUGH,
            classificationStatus = ClassificationStatus.SUGGESTED, revision = 2), session.id)
        SessionStore.invalidateCache()
        assertEquals(NightEventType.CONTACT_SOUND.name, store.loadSession(session.id)!!.events.single().userLabel)
        launchDetail(session, event.id)
        onView(withId(R.id.spinnerReviewLabel)).perform(scrollTo())
            .check(matches(withSpinnerText(R.string.event_contact)))
        onView(withId(R.id.checkReviewImportant)).perform(scrollTo()).check(matches(isChecked()))
        saveValidationScreenshot("validation-reviewed.png")
        onView(withId(R.id.checkReviewImportant)).perform(click())
        onView(withId(R.id.spinnerReviewLabel)).perform(scrollTo(), click())
        onData(equalTo(context.getString(R.string.review_unset))).inRoot(isPlatformPopup()).perform(click())
        onView(withId(R.id.btnSaveReview)).perform(scrollTo(), click())
        store.appendNightEvent(event.copy(userLabel = "COUGH",
            classificationStatus = ClassificationStatus.SUGGESTED, revision = 2,
            reviewFlags = setOf(SleepEvent.REVIEW_IMPORTANT)), session.id)
        SessionStore.invalidateCache()
        val saved = store.loadSession(session.id)!!.events.single()
        assertNull(saved.userLabel)
        assertFalse(SleepEvent.REVIEW_IMPORTANT in saved.reviewFlags)
    }

    @Test fun correctionChangesTitleListAndStoppedAggregationNotModelProvenance() {
        val session = store.startNew()
        val event = candidate(session).copy(type = NightEventType.COUGH,
            classificationStatus = ClassificationStatus.SUGGESTED, revision = 2)
        store.appendNightEvent(event)
        launchDetail(session, event.id)
        onView(withId(R.id.spinnerReviewLabel)).perform(scrollTo(), click())
        onData(equalTo(context.getString(R.string.event_snore))).inRoot(isPlatformPopup()).perform(click())
        onView(withId(R.id.btnSaveReview)).perform(scrollTo(), click())
        val title = context.getString(R.string.event_manual_title, context.getString(R.string.event_snore))
        onView(withId(R.id.tvDetailType)).perform(scrollTo()).check(matches(withText(title)))
        scenario!!.close()
        scenario = null
        store.stop()
        store.appendNightEvent(event.copy(type = NightEventType.SPEECH, confidence = .9f))
        SessionStore.invalidateCache()
        val updated = store.ensureSegmentsPersisted(session.id)!!
        assertEquals("SPEECH", updated.events.single().type)
        assertEquals("SNORE", updated.events.single().effectiveType)
        assertEquals(1, updated.snoreCount())
        assertEquals(0, updated.countByType("SPEECH"))
        assertEquals("SNORE", updated.segments.single().primaryLabel)
        assertEquals(mapOf("SNORE" to 1), updated.segments.single().labels)
        launchDetail(updated, event.id)
        onView(withId(R.id.tvDetailType)).check(matches(withText(title)))
        scenario!!.onActivity { activity ->
            (activity.supportFragmentManager.findFragmentByTag(EventDetailBottomSheet.TAG) as EventDetailBottomSheet)
                .dismissNow()
            detailOpen = false
            activity.setContentView(RecyclerView(activity).apply {
                layoutManager = LinearLayoutManager(activity)
                adapter = EventsAdapter(updated.events) { }
            })
        }
        onView(withId(R.id.tvEventType)).check(matches(withText(title)))
    }

    @Test fun candidatesAreThreeDistinctChineseSuggestionsNotRawScores() {
        val session = store.startNew()
        val event = candidate(session).copy(classificationStatus = ClassificationStatus.SUGGESTED,
            suggestedTypes = listOf("SNORE", "SNORE", "BREATHING", "BED_MOVEMENT", "COUGH", "Snoring"),
            classScores = (0..520).associate { "raw-$it" to .1f } + ("Snoring" to .8f))
        store.appendNightEvent(event)
        launchDetail(session, event.id)
        val expected = listOf(R.string.event_snore, R.string.event_breathing, R.string.event_bed_movement)
            .joinToString("、") { context.getString(it) }
        onView(withId(R.id.tvDetailCandidates))
            .check(matches(withText(context.getString(R.string.event_candidates, expected))))
    }

    @Test fun invalidManualLabelIsRejectedWithoutChangingStoredReview() {
        val session = store.startNew()
        store.appendNightEvent(candidate(session))
        assertTrue(store.updateEventReview(session.id, "detected", "SNORE", false))
        val before = store.sessionsFile().readText()
        assertFalse(store.updateEventReview(session.id, "detected", "SLEEP_APNEA", true))
        assertFalse(store.updateEventReview(session.id, "detected", "", true))
        assertEquals(before, store.sessionsFile().readText())
        SessionStore.invalidateCache()
        launchDetail(session, "detected")
        onView(withId(R.id.spinnerReviewLabel)).perform(scrollTo())
            .check(matches(withSpinnerText(R.string.event_snore)))
        onView(withId(R.id.checkReviewImportant)).perform(scrollTo()).check(matches(not(isChecked())))
    }

    @Test fun sameEventBroadcastStagesOnlyShowOneSnackbar() {
        val session = store.startNew()
        val event = candidate(session)
        store.appendNightEvent(event)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        fun broadcast(type: NightEventType, status: ClassificationStatus) {
            scenario!!.onActivity { activity ->
                activity.sendBroadcast(Intent(SleepTrackingService.ACTION_EVENT).setPackage(activity.packageName)
                    .putExtra(SleepTrackingService.EXTRA_EVENT_ID, event.id)
                    .putExtra(SleepTrackingService.EXTRA_EVENT_TYPE, type.name)
                    .putExtra(SleepTrackingService.EXTRA_CLASSIFICATION_STATUS, status.name))
            }
        }
        val initialMessage = context.getString(R.string.event_live_toast, context.getString(R.string.event_unknown))
        broadcast(NightEventType.UNKNOWN, ClassificationStatus.PENDING)
        onView(isRoot()).perform(waitForText(com.google.android.material.R.id.snackbar_text, initialMessage))
        broadcast(NightEventType.COUGH, ClassificationStatus.SUGGESTED)
        onView(com.google.android.material.R.id.snackbar_text.let { withId(it) })
            .check(matches(withText(initialMessage)))
    }

    @Test fun stoppedBroadcastShowsStorageError() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val message = context.getString(R.string.notif_storage_error)
        scenario!!.onActivity { activity ->
            activity.sendBroadcast(Intent(SleepTrackingService.ACTION_STOPPED).setPackage(activity.packageName)
                .putExtra(SleepTrackingService.EXTRA_ERROR, message))
        }
        onView(isRoot()).perform(waitForText(com.google.android.material.R.id.snackbar_text, message))
        onView(withId(com.google.android.material.R.id.snackbar_text)).check(matches(withText(message)))
    }

    private fun waitForText(id: Int, text: String) = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isRoot()
        override fun getDescription() = "Wait for expected UI text"
        override fun perform(uiController: UiController, view: View) {
            repeat(40) {
                if (view.findViewById<TextView>(id)?.text?.toString() == text) return
                uiController.loopMainThreadForAtLeast(50)
            }
            throw AssertionError("Expected text did not appear")
        }
    }

    @Test fun lateUpdateNeverLeaksIntoAnotherNight() {
        val first = store.startNew()
        val event = candidate(first)
        store.appendNightEvent(event, first.id)
        store.stop()
        val second = store.startNew()
        store.appendNightEvent(event.copy(classificationStatus = ClassificationStatus.FAILED))
        store.appendNightEvent(event.copy(id = "unseen-old-event", sessionId = "deleted-session"))
        assertTrue(store.loadSession(second.id)!!.events.isEmpty())
        assertEquals(ClassificationStatus.FAILED, store.loadSession(first.id)!!.events.single().classificationStatus)
        assertTrue(store.loadSession(first.id)!!.ensureSegments().isNotEmpty())
        assertTrue(store.deleteOne(first.id))
        store.appendNightEvent(event.copy(classificationStatus = ClassificationStatus.SUGGESTED))
        assertTrue(store.loadSession(second.id)!!.events.isEmpty())
        assertNull(store.loadSession(first.id))
    }

    @Test fun oldOwnerStopCannotStopNewCurrentSession() {
        val old = store.startNew()
        assertNotNull(store.stop(old.id))
        val current = store.startNew()
        store.appendNightEvent(candidate(current))
        assertNull(store.stop(old.id))
        SessionStore.invalidateCache()
        assertEquals(current.id, store.loadCurrent()!!.id)
        assertNull(store.loadCurrent()!!.endMs)
        assertEquals(ClassificationStatus.PENDING, store.loadCurrent()!!.events.single().classificationStatus)
        assertNotNull(store.stop(current.id))
        assertNull(store.loadCurrent())
    }

    @Test fun outOfOrderDetectionClipAndFinalSnapshotsDoNotRegress() {
        val session = store.startNew()
        val pending = candidate(session)
        val clip = pending.copy(revision = 1, clipStatus = ClipStatus.SAVED, clipRelativePath = wav(session.id))
        val final = clip.copy(revision = 2, type = NightEventType.COUGH,
            classificationStatus = ClassificationStatus.SUGGESTED, confidence = .8f)
        store.appendNightEvent(pending)
        store.appendNightEvent(clip)
        store.appendNightEvent(pending)
        assertEquals(1L, store.loadCurrent()!!.events.single().revision)
        assertEquals(clip.clipRelativePath, store.loadCurrent()!!.events.single().clipRelativePath)
        assertTrue(store.updateEventReview(session.id, pending.id, "SNORE", true))
        store.appendNightEvent(final)
        store.appendNightEvent(clip)
        store.appendNightEvent(pending.copy(revision = 2))
        SessionStore.invalidateCache()
        val restored = store.loadCurrent()!!.events.single()
        assertEquals(2L, restored.revision)
        assertEquals(ClassificationStatus.SUGGESTED, restored.classificationStatus)
        assertEquals("COUGH", restored.type)
        assertEquals("SNORE", restored.effectiveType)
        assertEquals(clip.clipRelativePath, restored.clipRelativePath)
        assertTrue(SleepEvent.REVIEW_IMPORTANT in restored.reviewFlags)
    }

    @Test fun endedHistorySettlesLatePendingAndAcceptsSameRevisionFinal() {
        val session = store.startNew()
        val pending = candidate(session)
        store.appendNightEvent(pending.copy(revision = 1,
            clipStatus = ClipStatus.SAVED, clipRelativePath = wav(session.id)))
        store.stop(session.id)
        val stopped = store.loadSession(session.id)!!.events.single()
        assertEquals(2L, stopped.revision)
        assertEquals(ClassificationStatus.FAILED, stopped.classificationStatus)
        store.appendNightEvent(pending)
        assertEquals(stopped, store.loadSession(session.id)!!.events.single())
        store.appendNightEvent(pending.copy(revision = 2, type = NightEventType.BREATHING,
            classificationStatus = ClassificationStatus.SUGGESTED))
        val corrected = store.loadSession(session.id)!!.events.single()
        assertEquals(ClassificationStatus.SUGGESTED, corrected.classificationStatus)
        assertEquals(stopped.clipRelativePath, corrected.clipRelativePath)
        assertEquals(ClipStatus.SAVED, corrected.clipStatus)
        store.appendNightEvent(pending.copy(id = "late-detection", clipRelativePath = wav(session.id, 1)))
        val late = store.loadSession(session.id)!!.events.single { it.id == "late-detection" }
        assertEquals(ClassificationStatus.FAILED, late.classificationStatus)
        assertEquals(2L, late.revision)
        assertEquals(ClipStatus.SAVED, late.clipStatus)
        SessionStore.invalidateCache()
        assertEquals(2L, store.loadSession(session.id)!!.events.first().revision)
    }

    @Test fun interruptedAtomicWriteKeepsPreviousCompleteSession() {
        val session = store.startNew()
        store.appendNightEvent(candidate(session))
        val file = AtomicFile(store.sessionsFile())
        // Simulate process death after a partial write, without finishWrite/failWrite.
        file.startWrite().use { it.write("{incomplete".toByteArray()); it.fd.sync() }
        SessionStore.invalidateCache()
        assertEquals(session.id, store.loadCurrent()!!.id)
        assertEquals("detected", store.loadCurrent()!!.events.single().id)
        assertTrue(store.updateEventReview(session.id, "detected", "SNORE", true))
        SessionStore.invalidateCache()
        assertEquals("SNORE", store.loadCurrent()!!.events.single().effectiveType)
    }

    @Test fun exportIncludesAllReviewClipsBeyondRepresentativeQuota() {
        val session = store.startNew()
        repeat(8) { i ->
            val event = candidate(session, "review-$i").copy(
                clipRelativePath = wav(session.id, i), clipStatus = ClipStatus.SAVED,
                classificationStatus = ClassificationStatus.SUGGESTED,
                classScores = mapOf("COUGH" to .4f, "BAD" to Float.NaN),
                suggestedTypes = listOf("COUGH"), modelVersion = "synthetic-test",
                classificationReason = "test", detectionConfidence = Float.NaN)
            store.appendNightEvent(event, session.id)
            assertTrue(store.updateEventReview(session.id, event.id, "CONTACT_SOUND", true))
        }
        store.stop()
        SessionStore.invalidateCache()
        val restored = store.loadSession(session.id)!!.events.first()
        assertEquals(0f, restored.detectionConfidence)
        assertFalse(restored.classScores.containsKey("BAD"))
        assertEquals("synthetic-test", restored.modelVersion)
        assertEquals(mapOf("COUGH" to .4f), restored.classScores)
        assertEquals(listOf("COUGH"), restored.suggestedTypes)
        val exported = SessionExporter(context).exportOne(session.id)
        files.add(exported.zipFile)
        assertEquals(8, exported.clipCount)
        ZipFile(exported.zipFile).use { zip ->
            val json = JSONObject(zip.getInputStream(zip.getEntry("sessions.json")).bufferedReader().readText())
            val events = json.getJSONArray("sessions").getJSONObject(0).getJSONArray("events")
            assertEquals(8, events.length())
            repeat(events.length()) { i ->
                val e = events.getJSONObject(i)
                assertEquals("CONTACT_SOUND", e.getString("userLabel"))
                assertTrue(e.getJSONArray("reviewFlags").toString().contains(SleepEvent.REVIEW_IMPORTANT))
                assertNotNull(zip.getEntry("clips/" + e.getString("clipRelativePath").removePrefix("audio_clips/")))
            }
        }
    }

    @Test fun legacyJsonDefaultsAndEnvironmentDoesNotDriveWakeBands() {
        store.sessionsFile().writeText("""{"running":false,"history":[{"id":"old","startMs":1,"endMs":7200001,"events":[{"id":"legacy","timeMs":2,"type":"UNKNOWN","peakLevel":0}]}]}""")
        SessionStore.invalidateCache()
        val session = store.loadSession("old")!!
        val event = session.events.single()
        assertEquals(ClassificationStatus.LEGACY, event.classificationStatus)
        assertEquals(ClipStatus.LEGACY, event.clipStatus)
        assertEquals(0L, event.revision)
        assertTrue(session.ensureSegments().isNotEmpty())
        val quiet = session.copy(events = mutableListOf())
        val env = quiet.copy(events = (0..100).map { i ->
            event.copy(id = "env-$i", timeMs = 1L + i * 60_000L, type = "ENV_NOISE")
        }.toMutableList())
        assertEquals(NightTimelineHeuristics.experimentalCycles(quiet), NightTimelineHeuristics.experimentalCycles(env))
        launchDetail(session, event.id)
        onView(withId(R.id.tvDetailStatus)).check(matches(withText(containsString("历史分类"))))
        onView(withId(R.id.btnPlayPause)).perform(scrollTo()).check(matches(not(isEnabled())))
    }

    /** Synthetic silence, not a private recording; long enough for pause/resume assertions. */
    private fun wav(sessionId: String, index: Int = 0): String {
        val relative = "audio_clips/$sessionId/test-$index.wav"
        val file = File(context.filesDir, relative)
        file.parentFile!!.mkdirs()
        val size = 16_000 * 2 * 30
        val buffer = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
        file.writeBytes(buffer.array())
        files.add(file)
        return relative
    }
}
