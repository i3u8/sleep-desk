package com.i3u8.sleepdesk.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real CPU runtime tests; synthetic silence only, no private recordings. */
@RunWith(AndroidJUnit4::class)
class YamNetSoundModelTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun testFrozenAssetHashAndSize() {
        val bytes = instrumentation.targetContext.assets.open(YamNetSoundModel.MODEL_ASSET)
            .use { it.readBytes() }
        assertEquals(YamNetSoundModel.MODEL_BYTES, bytes.size)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(YamNetSoundModel.MODEL_SHA256, digest)
    }

    @Test fun testSilenceHas521FiniteScoresAndMatchesRawRuntimeOutputZero() {
        val context = instrumentation.targetContext
        val bytes = context.assets.open(YamNetSoundModel.MODEL_ASSET).use { it.readBytes() }
        val labels = context.assets.open(YamNetSoundModel.LABEL_ASSET)
            .bufferedReader().use { it.readLines() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes).rewind()
        val expected = Array(1) { FloatArray(521) }
        Interpreter(buffer).use { raw ->
            assertEquals(3, raw.outputTensorCount)
            raw.resizeInput(0, intArrayOf(15600))
            raw.allocateTensors()
            raw.runForMultipleInputsOutputs(
                arrayOf<Any>(FloatArray(15600)), mutableMapOf<Int, Any>(0 to expected)
            )
            assertTrue(raw.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 521)))
            assertEquals(1024, raw.getOutputTensor(1).shape().last())
            assertEquals(64, raw.getOutputTensor(2).shape().last())
        }
        YamNetSoundModel(context).use { model ->
            val evidence = model.infer(ShortArray(15600), 16000)
            assertEquals(model.version, evidence.version)
            assertEquals(1, evidence.frames.size)
            val frame = evidence.frames.single()
            assertEquals(0, frame.startSample)
            assertEquals(15600, frame.endSample)
            assertEquals(521, frame.scores.size)
            labels.forEachIndexed { index, label ->
                val actual = frame.scores.getValue(label)
                assertTrue(actual.isFinite() && actual in 0f..1f)
                assertEquals(expected[0][index], actual, 0.0001f)
            }
        }
    }

    @Test fun testTailPaddingAndActualValidEnd() {
        YamNetSoundModel(instrumentation.targetContext).use { model ->
            val frames = model.infer(ShortArray(16000), 16000).frames
            assertEquals(2, frames.size)
            assertEquals(0, frames[0].startSample)
            assertEquals(15600, frames[0].endSample)
            assertEquals(7680, frames[1].startSample)
            assertEquals(16000, frames[1].endSample)
            val short = model.infer(ShortArray(2400), 16000).frames.single()
            assertEquals(2400, short.endSample)
            assertEquals(521, short.scores.size)
        }
    }

    @Test fun testPcmScalingAndPaddingMatchDirectInferenceWithoutNormalization() {
        val context = instrumentation.targetContext
        val pcm = ShortArray(3200) { index ->
            when (index % 4) {
                0 -> Short.MIN_VALUE
                1 -> Short.MAX_VALUE
                2 -> 123
                else -> -456
            }
        }
        val bytes = context.assets.open(YamNetSoundModel.MODEL_ASSET).use { it.readBytes() }
        val labels = context.assets.open(YamNetSoundModel.LABEL_ASSET)
            .bufferedReader().use { it.readLines() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        buffer.put(bytes).rewind()
        val expected = Array(1) { FloatArray(521) }
        Interpreter(buffer).use { raw ->
            raw.resizeInput(0, intArrayOf(15600))
            raw.allocateTensors()
            val input = FloatArray(15600) { if (it < pcm.size) pcm[it] / 32768f else 0f }
            raw.runForMultipleInputsOutputs(
                arrayOf<Any>(input), mutableMapOf<Int, Any>(0 to expected)
            )
        }
        YamNetSoundModel(context).use { model ->
            val frame = model.infer(pcm, 16000).frames.single()
            assertEquals(pcm.size, frame.endSample)
            labels.forEachIndexed { index, label ->
                assertEquals(expected[0][index], frame.scores.getValue(label), 0.0001f)
            }
        }
    }

    @Test fun testClosedAndWrongRateThrowOrdinaryExceptions() {
        val model = YamNetSoundModel(instrumentation.targetContext)
        try {
            model.infer(ShortArray(100), 8000)
            fail("Expected rate rejection")
        } catch (_: IllegalArgumentException) {
            // Expected: resampling must be explicit upstream.
        } finally {
            model.close()
        }
        model.close()
        try {
            model.infer(ShortArray(100), 16000)
            fail("Expected closed model rejection")
        } catch (_: IllegalStateException) {
            // Expected pipeline-visible Exception.
        }
    }

    @Test fun testInterruptedThreadRejectsInferenceWithoutClearingInterrupt() {
        YamNetSoundModel(instrumentation.targetContext).use { model ->
            try {
                Thread.currentThread().interrupt()
                model.infer(ShortArray(16000), 16000)
                fail("Expected cancellation before the first window")
            } catch (_: InterruptedException) {
                assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
            assertEquals(1, model.infer(ShortArray(2400), 16000).frames.size)
        }
    }

    @Test fun testRunningLongJobStopsOnInterruptAndModelRemainsReusable() {
        YamNetSoundModel(instrumentation.targetContext).use { model ->
            val pcm = ShortArray(16000 * 120)
            val started = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val worker = Thread {
                started.countDown()
                try {
                    model.infer(pcm, 16000)
                    failure.set(AssertionError("Long inference completed instead of being cancelled"))
                } catch (error: Throwable) {
                    failure.set(error)
                    if (error is InterruptedException && !Thread.currentThread().isInterrupted) {
                        failure.set(AssertionError("Interrupt flag was cleared"))
                    }
                }
            }
            worker.start()
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var enteredNativeRuntime = false
                while (worker.isAlive && System.nanoTime() < deadline && !enteredNativeRuntime) {
                    enteredNativeRuntime = worker.stackTrace.any {
                        it.className.startsWith("org.tensorflow.lite.")
                    }
                    if (!enteredNativeRuntime) Thread.sleep(5)
                }
                assertTrue("Worker should enter real model inference", enteredNativeRuntime)
            } finally {
                worker.interrupt()
                worker.join(10_000)
            }
            assertFalse("Cancellation should stop at a window boundary", worker.isAlive)
            assertTrue("Expected InterruptedException, got ${failure.get()}", failure.get() is InterruptedException)
            assertEquals(1, model.infer(ShortArray(2400), 16000).frames.size)
        }
    }
}
