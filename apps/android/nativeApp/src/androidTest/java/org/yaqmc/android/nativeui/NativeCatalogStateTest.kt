package org.yaqmc.android.nativeui

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.yaqmc.android.R

/** Fake Core responses exercise cancellation and write policies without touching a real account. */
@UnstableApi
class NativeCatalogStateTest {
    @Test fun bothPlaylistButtonsWaitForAll217SongsAndIgnoreRepeatedClicks() = runBlocking {
        for (shuffle in listOf(false, true)) {
            val moreStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fake = FakeCore { method, params -> when (method) {
                "provider_account_snapshot" -> authenticated()
                "provider_favorite_songs" -> {
                    val offset = params.text("cursor").substringAfter("page:", "0").toInt()
                    if (offset > 0) { moreStarted.complete(Unit); release.await() }
                    val end = (offset + 48).coerceAtMost(217)
                    JSONObject().put("items", org.json.JSONArray((offset until end).map { JSONObject().put("id", "s$it") }))
                        .put("authRevision", 1).put("total", 217).put("nextCursor", if (end < 217) "page:$end" else JSONObject.NULL)
                }
                else -> JSONObject()
            } }
            withModel(fake) { model ->
                main { model.navigate(NativeRoute("favorites")) }
                await { model.page.value.loaded && model.page.value.page.tracks.size == 48 }
                main { model.playPage(model.page.value.page.tracks, shuffle) }
                moreStarted.await()
                main { model.playPage(model.page.value.page.tracks, !shuffle) }
                assertTrue(model.preparingPage.value)
                assertFalse(fake.calls.any { it.first == "player_play_tracks" })
                release.complete(Unit)
                await { !model.preparingPage.value }
                val request = fake.calls.single { it.first == "player_play_tracks" }.second.getJSONObject("request")
                assertEquals(shuffle, request.getBoolean("shuffle"))
                assertEquals((0 until 217).map { "s$it" }, request.getJSONArray("tracks").objects().map { it.text("id") })
                assertEquals(48, model.page.value.page.tracks.size)
            }
        }
    }

    @Test fun failedPlaylistPreparationDoesNotReplaceQueueAndCanRetry() = runBlocking {
        var fail = true
        val fake = FakeCore { method, params -> when (method) {
            "provider_account_snapshot" -> authenticated()
            "provider_favorite_songs" -> if (params.isNull("cursor")) JSONObject("""{"items":[{"id":"1"}],"authRevision":1,"nextCursor":"next"}""")
                else if (fail) throw CoreFailure("timeout", "") else JSONObject("""{"items":[{"id":"2"}],"authRevision":1}""")
            else -> JSONObject()
        } }
        withModel(fake) { model ->
            main { model.navigate(NativeRoute("favorites")) }
            await { model.page.value.loaded }
            main { model.playPage(model.page.value.page.tracks, true) }
            await { model.failedPagePlayback.value != null }
            assertFalse(model.preparingPage.value)
            assertFalse(fake.calls.any { it.first.startsWith("player_") })
            assertEquals(R.string.native_timeout, model.failedPagePlayback.value!!.second.resource)
            fail = false
            main { model.playPage(model.page.value.page.tracks, true) }
            await { fake.calls.any { it.first == "player_play_tracks" } }
            assertEquals(2, fake.calls.single { it.first == "player_play_tracks" }.second.getJSONObject("request").getJSONArray("tracks").length())
            assertNull(model.failedPagePlayback.value)
        }
    }

    @Test fun cancelledPlaylistPreparationCannotPlayAfterNavigationOrAccountSwitch() = runBlocking {
        for (cancel in listOf("cancel", "navigate", "account", "play-other")) {
            var revision = 1L
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fake = FakeCore { method, params -> when (method) {
                "provider_account_snapshot" -> authenticated(revision)
                "provider_favorite_songs" -> if (params.isNull("cursor")) JSONObject("""{"items":[{"id":"1"}],"authRevision":$revision,"nextCursor":"next"}""")
                    else { started.complete(Unit); withContext(NonCancellable) { release.await() }; JSONObject("""{"items":[{"id":"2"}],"authRevision":1}""") }
                else -> JSONObject()
            } }
            withModel(fake) { model ->
                main { model.navigate(NativeRoute("favorites")) }
                await { model.page.value.loaded }
                main { model.playPage(model.page.value.page.tracks, false) }
                started.await()
                main { when (cancel) {
                    "cancel" -> model.cancelPlaybackPreparation()
                    "navigate" -> model.selectRoot("discover")
                    "account" -> { revision = 2; model.refreshAccount() }
                    else -> model.play(listOf(Track(JSONObject("""{"id":"other"}"""))))
                } }
                release.complete(Unit)
                delay(100)
                assertFalse(model.preparingPage.value)
                assertNull(model.failedPagePlayback.value)
                val plays = fake.calls.filter { it.first == "player_play_tracks" }
                assertEquals(if (cancel == "play-other") 1 else 0, plays.size)
                if (plays.isNotEmpty()) assertEquals("other", plays.single().second.getJSONObject("request").getJSONArray("tracks").getJSONObject(0).text("id"))
            }
        }
    }

    @Test fun accountPlaylistCardLoadsEveryPageBeforeStarting() = runBlocking {
        val fake = FakeCore { method, params -> when (method) {
            "provider_account_snapshot" -> authenticated()
            "provider_account_playlist_tracks" -> {
                assertEquals("p", params.getJSONObject("playlist").text("id"))
                val first = params.isNull("cursor")
                JSONObject().put("summary", params.getJSONObject("playlist")).put("tracks", JSONObject()
                    .put("authRevision", 1).put("items", org.json.JSONArray(if (first) """[{"id":"1"}]""" else """[{"id":"2"}]"""))
                    .put("nextCursor", if (first) "next" else JSONObject.NULL))
            }
            else -> JSONObject()
        } }
        withModel(fake) { model ->
            main { model.refreshAccount(); model.playCard(CatalogItem("account-playlist", JSONObject("""{"id":"p"}"""))) }
            await { fake.calls.any { it.first == "player_play_tracks" } }
            assertEquals(2, fake.calls.single { it.first == "player_play_tracks" }.second.getJSONObject("request").getJSONArray("tracks").length())
        }
    }

    @Test fun startupAccountSnapshotLoadsAccountHomeEvenWhenRestoreEventWasMissed() = runBlocking {
        val signedIn = java.util.concurrent.atomic.AtomicBoolean(false)
        val releaseRevalidation = CompletableDeferred<Unit>()
        val fake = FakeCore { method, _ -> when (method) {
            "provider_account_snapshot" -> if (signedIn.get()) authenticated() else JSONObject()
            "provider_account_refresh" -> { releaseRevalidation.await(); JSONObject() }
            "provider_home" -> {
                val id = if (signedIn.get()) "account-home" else "guest-home"
                JSONObject("""{"madeForYou":[{"id":"$id","title":"$id"}]}""")
            }
            else -> JSONObject()
        } }
        withModel(fake) { model ->
            signedIn.set(true)
            main { model.observe() }
            await { model.page.value.page.sections.flatMap { it.cards }.any { it.id == "account-home" } }
            assertFalse(fake.calls.last { it.first == "provider_home" }.second.getBoolean("refresh"))
        }
    }

    @Test fun foregroundSyncFailuresDoNotInterruptHomeOrShowAnActionError() = runBlocking {
        val signedIn = java.util.concurrent.atomic.AtomicBoolean(false)
        val fake = FakeCore { method, _ -> when (method) {
            "player_snapshot", "provider_account_refresh", "continuation_snapshot" -> throw CoreFailure("core.protocol", "private fixture detail")
            "provider_account_snapshot" -> if (signedIn.get()) authenticated() else JSONObject()
            "provider_home" -> {
                val id = if (signedIn.get()) "account-home" else "guest-home"
                JSONObject("""{"madeForYou":[{"id":"$id"}]}""")
            }
            else -> JSONObject()
        } }
        withModel(fake) { model ->
            signedIn.set(true)
            repeat(2) {
                main { model.observe() }
                await { model.page.value.page.sections.firstOrNull()?.cards?.firstOrNull()?.id == "account-home" }
                await { fake.calls.count { it.first == "continuation_snapshot" } == it + 1 }
                main {
                    assertNull(model.message.value)
                    assertNull(model.page.value.error)
                    model.stopObserving()
                }
            }
            assertTrue(fake.calls.any { it.first == "provider_account_refresh" })
            assertEquals(2, fake.calls.count { it.first == "provider_home" })
            val logs = fake.calls.filter { it.first == "diagnostics_log_frontend" }
                .flatMap { it.second.optJSONArray("entries").objects() }
            assertTrue(logs.any { it.text("message") == "player_snapshot · core.protocol" })
            assertTrue(logs.all { it.text("target") == "android.sync" && !it.text("message").contains("private fixture detail") })
        }
    }

    @Test fun automaticHomeLoadKeepsLoadingUntilTheRetrySucceeds() = runBlocking {
        val retryStarted = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        var attempts = 0
        val fake = FakeCore { method, _ -> when (method) {
            "provider_home" -> {
                if (++attempts == 1) throw CoreFailure("core.command_error", "", retryable = true)
                retryStarted.complete(Unit)
                releaseRetry.await()
                JSONObject("""{"madeForYou":[{"id":"recovered"}]}""")
            }
            else -> JSONObject()
        } }
        withModel(fake, waitForHome = false) { model ->
            withTimeout(5000) { retryStarted.await() }
            assertTrue(model.page.value.loading)
            assertNull(model.page.value.error)
            assertNull(model.message.value)
            releaseRetry.complete(Unit)
            await { model.page.value.loaded }
            assertEquals("recovered", model.page.value.page.sections.single().cards.single().id)
        }
    }

    @Test fun repeatedHomeFailureStillOffersManualRetry() = runBlocking {
        val fake = FakeCore { method, params -> when (method) {
            "provider_home" -> {
                if (!params.optBoolean("refresh")) throw CoreFailure("core.command_error", "", retryable = true)
                JSONObject("""{"madeForYou":[{"id":"recovered"}]}""")
            }
            else -> JSONObject()
        } }
        withModel(fake, waitForHome = false) { model ->
            await { model.page.value.error != null }
            assertFalse(model.page.value.loading)
            assertEquals(2, fake.calls.count { it.first == "provider_home" })
            main { model.load(refresh = true) }
            await { model.page.value.loaded }
            assertNull(model.page.value.error)
        }
    }

    @Test fun staleHomeIsAvailableWhileTheNetworkUpdateIsPending() = runBlocking {
        val releaseUpdate = CompletableDeferred<Unit>()
        val fake = FakeCore { method, _ -> when (method) {
            "provider_home_cached" -> JSONObject("""{"snapshot":{"stale":true,"feed":{"madeForYou":[{"id":"cached"}]}}}""")
            "provider_home" -> {
                releaseUpdate.await()
                JSONObject("""{"madeForYou":[{"id":"updated"}]}""")
            }
            else -> JSONObject()
        } }
        withModel(fake, waitForHome = false) { model ->
            await { fake.calls.any { it.first == "provider_home" } && model.page.value.loaded }
            assertEquals("cached", model.page.value.page.sections.single().cards.single().id)
            assertFalse(model.page.value.loading)
            assertNull(model.page.value.error)
            val cached = fake.calls.single { it.first == "provider_home_cached" }.second
            assertEquals("qqmusic", cached.getString("providerId"))
            assertEquals("default", cached.getString("profileId"))
            val update = fake.calls.single { it.first == "provider_home" }.second
            assertTrue(update.getBoolean("requireFresh"))
            assertFalse(update.getBoolean("refresh"))
            releaseUpdate.complete(Unit)
            await { model.page.value.page.sections.single().cards.single().id == "updated" }
        }
    }

    @Test fun freshHomeCacheSkipsTheUpdateButManualRefreshRetainsItsFailure() = runBlocking {
        val fake = FakeCore { method, _ -> when (method) {
            "provider_home_cached" -> JSONObject("""{"snapshot":{"stale":false,"feed":{"madeForYou":[{"id":"cached"}]}}}""")
            "provider_home" -> throw CoreFailure("core.command_error", "", retryable = true)
            else -> JSONObject()
        } }
        withModel(fake) { model ->
            assertFalse(fake.calls.any { it.first == "provider_home" })
            main { model.load(refresh = true) }
            await { model.page.value.error != null }
            val refresh = fake.calls.single { it.first == "provider_home" }.second
            assertTrue(refresh.getBoolean("refresh"))
            assertTrue(refresh.getBoolean("requireFresh"))
            assertEquals("cached", model.page.value.page.sections.single().cards.single().id)
        }
    }

    @Test fun storedAccountPreviewWaitsBeforeStartingOnePersonalUpdate() = runBlocking {
        val finishRestore = CompletableDeferred<Unit>()
        val finishUpdate = CompletableDeferred<Unit>()
        val fake = FakeCore { method, params -> when (method) {
            "provider_home_cached" -> {
                if (params.optBoolean("waitForAccount")) finishRestore.await()
                homeCache("private-cache", awaiting = !finishRestore.isCompleted, refreshRequired = finishRestore.isCompleted)
            }
            "provider_account_snapshot" -> if (finishRestore.isCompleted) authenticated(2) else JSONObject("""{"state":"restoring-session","revision":1}""")
            "provider_home" -> { check(finishRestore.isCompleted); finishUpdate.await(); homeFeed("personal-update") }
            else -> JSONObject()
        } }
        withModel(fake, waitForHome = false) { model ->
            main { model.observe() }
            await { model.page.value.awaitingAccount && model.page.value.loaded && model.account.value.state == "restoring-session" }
            assertEquals("private-cache", model.page.value.page.sections.single().cards.single().id)
            assertFalse(fake.calls.any { it.first == "provider_home" })
            finishRestore.complete(Unit)
            await { fake.calls.any { it.first == "provider_home" } }
            main { model.onCoreEvent(1, "account://changed", "{}") }
            await { model.account.value.authenticated }
            assertEquals(1, fake.calls.count { it.first == "provider_home" })
            finishUpdate.complete(Unit)
            await { model.page.value.page.sections.single().cards.single().id == "personal-update" }
            assertFalse(model.page.value.awaitingAccount)
        }
    }

    @Test fun cancellingHomeAndBlankSearchDoesNotStartGuestRequestsWhileRestoring() = runBlocking {
        val finishRestore = CompletableDeferred<Unit>()
        val fake = FakeCore { method, params -> when (method) {
            "provider_home_cached" -> {
                if (params.optBoolean("waitForAccount")) finishRestore.await()
                homeCache(null, awaiting = !finishRestore.isCompleted, refreshRequired = finishRestore.isCompleted)
            }
            "provider_home" -> { check(finishRestore.isCompleted); homeFeed("restored") }
            else -> JSONObject()
        } }
        withModel(fake, waitForHome = false) { model ->
            await { model.page.value.awaitingAccount }
            main { model.selectRoot("search") }
            await { fake.calls.count { it.first == "provider_home_cached" && it.second.optBoolean("waitForAccount") } == 2 }
            assertFalse(fake.calls.any { it.first == "provider_home" })
            main { model.search("fixture") }
            await { model.page.value.loaded }
            finishRestore.complete(Unit)
            main { assertFalse(fake.calls.any { it.first == "provider_home" }); model.selectRoot("home") }
            await { model.page.value.page.sections.firstOrNull()?.cards?.firstOrNull()?.id == "restored" }
            assertEquals(1, fake.calls.count { it.first == "provider_home" })
        }
    }

    @Test fun returningToHomeJoinsThePersonalUpdateEvenWithAFreshSnapshot() = runBlocking {
        val finishUpdate = CompletableDeferred<Unit>()
        val rebuilds = java.util.concurrent.atomic.AtomicBoolean(false)
        val fake = FakeCore { method, _ -> when (method) {
            "provider_home_cached" -> homeCache(
                if (finishUpdate.isCompleted) "updated" else "old",
                refreshRequired = !finishUpdate.isCompleted,
            )
            "provider_home" -> {
                rebuilds.compareAndSet(false, true)
                finishUpdate.await()
                homeFeed("updated")
            }
            else -> JSONObject()
        } }
        withModel(fake, waitForHome = false) { model ->
            await { rebuilds.get() && model.page.value.loaded }
            main { model.selectRoot("library"); model.selectRoot("home") }
            await { fake.calls.count { it.first == "provider_home" } == 2 }
            assertEquals("old", model.page.value.page.sections.single().cards.single().id)
            finishUpdate.complete(Unit)
            await { model.page.value.page.sections.single().cards.single().id == "updated" }
        }
    }

    @Test fun temporaryRestoreFailureDropsPrivatePreviewAndManualRefreshRetriesAccount() = runBlocking {
        val finishRestore = CompletableDeferred<Unit>()
        val recovered = java.util.concurrent.atomic.AtomicBoolean(false)
        val fake = FakeCore { method, params -> when (method) {
            "provider_home_cached" -> {
                if (params.optBoolean("waitForAccount")) finishRestore.await()
                when {
                    recovered.get() -> homeCache("verified", refreshRequired = true)
                    finishRestore.isCompleted -> homeCache("public-cache")
                    else -> homeCache("private-preview", awaiting = true)
                }
            }
            "provider_account_snapshot" -> if (recovered.get()) authenticated(3) else JSONObject("""{"state":"network-error","revision":2}""")
            "provider_account_refresh" -> JSONObject().also { recovered.set(true) }
            "provider_home" -> { check(recovered.get()); homeFeed("verified") }
            else -> JSONObject()
        } }
        withModel(fake, waitForHome = false) { model ->
            await { model.page.value.awaitingAccount }
            assertEquals("private-preview", model.page.value.page.sections.single().cards.single().id)
            finishRestore.complete(Unit)
            await { !model.page.value.awaitingAccount && model.page.value.loaded }
            assertEquals("public-cache", model.page.value.page.sections.single().cards.single().id)
            assertFalse(fake.calls.any { it.first == "provider_home" })
            main { model.load(refresh = true) }
            await { model.account.value.authenticated && !model.page.value.loading && model.page.value.loaded }
            assertEquals(listOf("provider_account_refresh", "provider_home"),
                fake.calls.map { it.first }.filter { it == "provider_account_refresh" || it == "provider_home" })
            assertEquals("verified", model.page.value.page.sections.single().cards.single().id)
        }
    }

    @Test fun temporaryStartupFailureSchedulesRecoveryAndConfirmedExpiryAllowsGuestHome() = runBlocking {
        val finishInitialRestore = CompletableDeferred<Unit>()
        val explicitlyExpired = java.util.concurrent.atomic.AtomicBoolean(false)
        val fake = FakeCore { method, params -> when (method) {
            "provider_home_cached" -> {
                if (params.optBoolean("waitForAccount")) finishInitialRestore.await()
                homeCache(if (explicitlyExpired.get()) null else "public-cache", awaiting = !finishInitialRestore.isCompleted)
            }
            "provider_account_snapshot" -> when {
                explicitlyExpired.get() -> JSONObject("""{"state":"reauthentication-required","revision":3}""")
                finishInitialRestore.isCompleted -> JSONObject("""{"state":"network-error","revision":2}""")
                else -> JSONObject("""{"state":"restoring-session","revision":1}""")
            }
            "provider_account_refresh" -> {
                explicitlyExpired.set(true)
                throw CoreFailure("core.command_error", "confirm expiry")
            }
            "provider_home" -> { check(explicitlyExpired.get()); homeFeed("guest-update") }
            else -> JSONObject()
        } }
        withModel(fake, waitForHome = false) { model ->
            main { model.observe() }
            await { model.page.value.awaitingAccount && model.account.value.state == "restoring-session" }
            finishInitialRestore.complete(Unit)
            main { model.onCoreEvent(1, "account://changed", "{}") }
            withTimeout(8000) {
                while (model.page.value.page.sections.firstOrNull()?.cards?.firstOrNull()?.id != "guest-update") delay(10)
            }
            assertEquals("reauthentication-required", model.account.value.state)
            assertEquals(1, fake.calls.count { it.first == "provider_home" })
            assertNull(model.message.value)
        }
    }

    @Test fun signingInWhileAGuestNetworkRequestIsRunningReplacesTheGuestPage() = runBlocking {
        val guestStarted = CompletableDeferred<Unit>()
        val guestCancelled = CompletableDeferred<Unit>()
        val signedIn = java.util.concurrent.atomic.AtomicBoolean(false)
        val fake = FakeCore { method, _ -> when (method) {
            "provider_account_snapshot" -> if (signedIn.get()) authenticated(2) else JSONObject("""{"state":"guest","revision":1}""")
            "provider_home" -> {
                if (!signedIn.get()) {
                    guestStarted.complete(Unit)
                    try { awaitCancellation() } finally { guestCancelled.complete(Unit) }
                }
                homeFeed("signed-in")
            }
            else -> JSONObject()
        } }
        withModel(fake, waitForHome = false) { model ->
            withTimeout(5000) { guestStarted.await() }
            signedIn.set(true)
            main { model.onCoreEvent(1, "account://changed", "{}") }
            withTimeout(5000) { guestCancelled.await() }
            await { model.page.value.loaded }
            assertEquals("signed-in", model.page.value.page.sections.single().cards.single().id)
            assertEquals(2, fake.calls.count { it.first == "provider_home" })
        }
    }

    @Test fun failedBackgroundHomeUpdateKeepsPreviouslyLoadedRecommendations() = runBlocking {
        val signedIn = java.util.concurrent.atomic.AtomicBoolean(false)
        val fake = FakeCore { method, _ -> when (method) {
            "provider_account_snapshot" -> if (signedIn.get()) authenticated() else JSONObject()
            "provider_home" -> {
                if (signedIn.get()) throw CoreFailure("core.protocol", "")
                JSONObject("""{"madeForYou":[{"id":"guest-home"}]}""")
            }
            else -> JSONObject()
        } }
        withModel(fake) { model ->
            signedIn.set(true)
            main { model.onCoreEvent(1, "account://changed", "{}") }
            await { fake.calls.count { it.first == "provider_home" } == 2 && !model.page.value.loading }
            assertEquals("guest-home", model.page.value.page.sections.single().cards.single().id)
            assertNull(model.page.value.error)
            assertNull(model.message.value)
        }
    }

    @Test fun accountSwitchInvalidatesCachedHomeEvenWhenRevisionsMatch() = runBlocking {
        val current = java.util.concurrent.atomic.AtomicReference("first")
        val secondHomeStarted = CompletableDeferred<Unit>()
        val releaseSecondHome = CompletableDeferred<Unit>()
        val fake = FakeCore { method, _ -> when (method) {
            "provider_account_snapshot" -> authenticated().apply { getJSONObject("profile").put("maskedIdentity", current.get()) }
            "provider_home" -> {
                if (current.get() == "second") {
                    secondHomeStarted.complete(Unit)
                    releaseSecondHome.await()
                    throw CoreFailure("core.protocol", "")
                }
                JSONObject("""{"madeForYou":[{"id":"first-home"}]}""")
            }
            else -> JSONObject()
        } }
        withModel(fake) { model ->
            main { model.refreshAccount(); model.load() }
            await { model.page.value.loaded && fake.calls.count { it.first == "provider_home" } == 2 }
            current.set("second")
            main { model.onCoreEvent(1, "account://changed", "{}") }
            withTimeout(5000) { secondHomeStarted.await() }
            assertTrue(model.page.value.loading)
            assertTrue(model.page.value.page.sections.isEmpty())
            releaseSecondHome.complete(Unit)
            await { model.page.value.error != null }
            assertTrue(model.page.value.page.sections.isEmpty())
        }
    }

    @Test fun finishingGuestAccountRestoreDoesNotRestartAnInFlightHomeLoad() = runBlocking {
        val state = java.util.concurrent.atomic.AtomicReference("restoring-session")
        val releaseHome = CompletableDeferred<Unit>()
        val fake = FakeCore { method, _ -> when (method) {
            "provider_account_snapshot" -> JSONObject().put("state", state.get()).put("revision", if (state.get() == "guest") 2 else 1)
            "provider_home" -> { releaseHome.await(); JSONObject() }
            else -> JSONObject()
        } }
        withModel(fake, waitForHome = false) { model ->
            main { model.observe() }
            await { model.account.value.state == "restoring-session" }
            state.set("guest")
            main { model.onCoreEvent(1, "account://changed", "{}") }
            await { model.account.value.state == "guest" && model.account.value.revision == 2L }
            releaseHome.complete(Unit)
            await { model.page.value.loaded }
            assertEquals(1, fake.calls.count { it.first == "provider_home" })
        }
    }

    @Test fun restoredAccountUsesCachedHomeWhileManualRefreshStillForcesNetwork() = runBlocking {
        val signedIn = java.util.concurrent.atomic.AtomicBoolean(false)
        val accountHomeStarted = CompletableDeferred<Unit>()
        val releaseAccountHome = CompletableDeferred<Unit>()
        val fake = FakeCore { method, _ -> when (method) {
            "provider_account_snapshot" -> if (signedIn.get()) authenticated() else JSONObject()
            "provider_home" -> {
                val id = if (signedIn.get()) "account-home" else "guest-home"
                if (signedIn.get() && !releaseAccountHome.isCompleted) {
                    accountHomeStarted.complete(Unit)
                    releaseAccountHome.await()
                }
                JSONObject("""{"madeForYou":[{"id":"$id","title":"$id"}]}""")
            }
            else -> JSONObject()
        } }
        withModel(fake) { model ->
            signedIn.set(true)
            main { model.onCoreEvent(1, "account://changed", "{}") }
            accountHomeStarted.await()
            assertTrue(model.page.value.loading)
            assertEquals("guest-home", model.page.value.page.sections.single().cards.single().id)
            releaseAccountHome.complete(Unit)
            await { model.page.value.page.sections.flatMap { it.cards }.any { it.id == "account-home" } }
            val homes = fake.calls.filter { it.first == "provider_home" }
            assertEquals(2, homes.size)
            assertFalse(homes.last().second.getBoolean("refresh"))
            main { model.load(refresh = true) }
            await { fake.calls.count { it.first == "provider_home" } == 3 && model.page.value.loaded && !model.page.value.loading }
            assertTrue(fake.calls.last { it.first == "provider_home" }.second.getBoolean("refresh"))
            main { model.refreshAccountSession() }?.join()
            await { fake.calls.count { it.first == "provider_home" } == 4 && model.page.value.loaded && !model.page.value.loading }
            assertFalse(fake.calls.last { it.first == "provider_home" }.second.getBoolean("refresh"))
        }
    }

    @Test fun accountRevalidationDoesNotCancelAnInFlightHomeRefresh() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fake = FakeCore { method, params -> when (method) {
            "provider_account_snapshot" -> authenticated()
            "provider_home" -> {
                val id = if (params.getBoolean("refresh")) {
                    started.complete(Unit)
                    release.await()
                    "fresh-home"
                } else "cached-home"
                JSONObject("""{"madeForYou":[{"id":"$id","title":"$id"}]}""")
            }
            else -> JSONObject()
        } }
        withModel(fake) { model ->
            main { model.refreshAccount(); model.load(refresh = true) }
            started.await()
            main { model.refreshAccountSession() }?.join()
            assertEquals(2, fake.calls.count { it.first == "provider_home" })
            release.complete(Unit)
            await { model.page.value.page.sections.flatMap { it.cards }.any { it.id == "fresh-home" } }
        }
    }

    @Test fun rootSwitchRestoresSearchCategoryAndChildBackStackWithoutAnotherRequest() = runBlocking {
        val fake = FakeCore { method, _ -> if (method == "provider_search") JSONObject("""{"items":[],"hasMore":false}""") else JSONObject() }
        withModel(fake) { model ->
            main { model.search("music", "album") }
            await { model.page.value.loaded }
            main { model.navigate(NativeRoute("album", "selected")) }
            await { model.page.value.loaded }
            main { model.selectRoot("library"); model.selectRoot("search") }
            assertEquals(NativeRoute("album", "selected"), model.route.value)
            assertEquals("search", model.rootDestination.value)
            main { assertTrue(model.back()) }
            assertEquals("music", model.route.value.query)
            assertEquals("album", model.route.value.searchKind)
            main { model.navigate(NativeRoute("album", "selected")); model.selectRoot("search") }
            assertEquals(NativeRoute("search", query = "music", searchKind = "album"), model.route.value)
            main { assertFalse(model.back()) }
            main { model.selectRoot("search") }
            assertEquals(1, fake.calls.count { it.first == "provider_search" })
        }
    }

    @Test fun cancelledCardCannotClearTheNewPreparationOrPlayItsOldResponse() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        val fake = FakeCore { method, params ->
            if (method == "provider_playlist") {
                val id = params.text("id")
                if (id == "old") { oldStarted.complete(Unit); withContext(NonCancellable) { withTimeout(5000) { oldRelease.await() } } }
                else newRelease.await()
                JSONObject("""{"id":"$id","tracks":[{"id":"$id","title":"$id"}]}""")
            } else JSONObject()
        }
        withModel(fake) { model ->
            main { model.playCard(CatalogItem("playlist", JSONObject().put("id", "old"))) }
            oldStarted.await()
            main { model.playCard(CatalogItem("playlist", JSONObject().put("id", "new"))) }
            await { fake.calls.count { it.first == "provider_playlist" } == 2 }
            oldRelease.complete(Unit)
            delay(100)
            assertEquals("playlist:new", model.preparingCard.value)
            assertNull(model.failedCard.value)
            assertFalse(fake.calls.any { it.first == "player_play_tracks" })
            newRelease.complete(Unit)
            await { model.preparingCard.value == null }
            val play = fake.calls.single { it.first == "player_play_tracks" }
            assertEquals("new", play.second.getJSONObject("request").text("startAtId"))
        }
    }

    @Test fun cancelledSearchCannotOverwriteNewerResults() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val fake = FakeCore { method, params ->
            if (method == "provider_search") {
                val query = params.getString("query")
                if (query == "old") {
                    oldStarted.complete(Unit)
                    withContext(NonCancellable) { delay(200) }
                }
                JSONObject("""{"items":[{"id":"$query"}],"hasMore":false}""")
            } else JSONObject()
        }
        withModel(fake) { model ->
            main { model.search("old") }
            oldStarted.await()
            main { model.search("new") }
            await { model.page.value.page.tracks.firstOrNull()?.id == "new" }
            delay(300)
            assertEquals("new", model.page.value.page.tracks.single().id)
            assertFalse(model.page.value.loading)
        }
    }

    @Test fun pagingFailureKeepsRowsAndRetryAndCachedReturnUseCorrectPage() = runBlocking {
        var failSecond = true
        val pages = mutableListOf<Int>()
        val fake = FakeCore { method, params ->
            if (method != "provider_search") JSONObject() else {
                val number = params.getInt("page")
                pages += number
                if (number == 2 && failSecond) { failSecond = false; throw CoreFailure("timeout", "") }
                JSONObject("""{"items":[{"id":"$number"}],"hasMore":${number < 3}}""")
            }
        }
        withModel(fake) { model ->
            main { model.search("music") }
            await { model.page.value.loaded }
            main { model.load(more = true) }
            await { model.page.value.moreError != null }
            assertEquals(listOf("1"), model.page.value.page.tracks.map { it.id })
            assertNull(model.page.value.error)
            assertFalse(model.page.value.loadingMore)
            main { model.load(more = true) }
            await { model.page.value.page.tracks.size == 2 }
            main { model.navigate(NativeRoute("library")); assertTrue(model.back()); model.load(more = true) }
            await { model.page.value.page.tracks.size == 3 }
            assertEquals(listOf(1, 2, 2, 3), pages)
            assertFalse(model.page.value.page.hasMore)
        }
    }

    @Test fun timeoutEndsInitialLoadingAndRefreshFailureKeepsContent() = runBlocking {
        var fail = true
        val fake = FakeCore { method, _ ->
            if (method == "provider_discover" && fail) throw CoreFailure("timeout", "")
            if (method == "provider_discover") JSONObject("""{"charts":[{"id":"chart","title":"Chart"}]}""") else JSONObject()
        }
        withModel(fake) { model ->
            main { model.navigate(NativeRoute("discover"), true) }
            await { model.page.value.error != null }
            assertFalse(model.page.value.loading)
            assertEquals(R.string.native_timeout, model.page.value.error!!.resource)
            fail = false
            main { model.load(refresh = true) }
            await { model.page.value.loaded }
            fail = true
            main { model.load(refresh = true) }
            await { model.page.value.error != null }
            assertEquals("chart", model.page.value.page.sections.single().cards.single().id)
        }
    }

    @Test fun favoriteWaitStatePreventsDuplicateWritesAndUnknownOutcomeDoesNotApply() = runBlocking {
        val response = CompletableDeferred<Unit>()
        val fake = FakeCore { method, params ->
            when (method) {
                "provider_account_snapshot" -> authenticated()
                "provider_set_favorite" -> {
                    response.await()
                    JSONObject().put("authRevision", 1).put("status", "outcome-unknown").put("favorite", true)
                        .put("clientOperationId", params.getJSONObject("request").getString("clientOperationId"))
                }
                else -> JSONObject()
            }
        }
        withModel(fake) { model ->
            val track = writableTrack()
            main { model.refreshAccount(); model.favorite(track); model.favorite(track) }
            await { fake.calls.count { it.first == "provider_set_favorite" } == 1 }
            assertTrue(track.key in model.pendingFavorites.value)
            response.complete(Unit)
            await { model.pendingFavorites.value.isEmpty() && model.message.value != null }
            assertNull(model.favorites.value[track.key])
            assertEquals(R.string.native_mutation_unknown, model.message.value?.resource)
            assertEquals(1, fake.calls.count { it.first == "provider_set_favorite" })
        }
    }

    @Test fun accountRevisionChangeDropsOldPageAndRejectsLateMutationReceipt() = runBlocking {
        var revision = 1L
        val response = CompletableDeferred<Unit>()
        val fake = FakeCore { method, _ -> when (method) {
            "provider_account_snapshot" -> authenticated(revision)
            "provider_favorite_songs" -> JSONObject("""{"items":[{"id":"private-$revision"}],"authRevision":$revision}""")
            "provider_set_favorite" -> { response.await(); JSONObject("""{"authRevision":1,"status":"applied","favorite":true}""") }
            else -> JSONObject()
        } }
        withModel(fake) { model ->
            main { model.refreshAccount(); model.navigate(NativeRoute("favorites")) }
            await { model.page.value.page.tracks.isNotEmpty() }
            main { model.favorite(writableTrack()) }
            await { model.pendingFavorites.value.isNotEmpty() }
            revision = 2
            main { model.refreshAccount() }
            assertTrue(model.page.value.page.tracks.isEmpty())
            response.complete(Unit)
            await { model.pendingFavorites.value.isEmpty() }
            assertFalse(model.favorites.value.keys.any { it.contains("private-1") || it == writableTrack().key })
            main { model.load(refresh = true) }
            await { model.page.value.page.tracks.firstOrNull()?.id == "private-2" }
        }
    }

    @Test fun lateFavoriteHydrationCannotUndoConfirmedUnfavorite() = runBlocking {
        val oldFavorites = CompletableDeferred<Unit>()
        val fake = FakeCore { method, _ -> when (method) {
            "provider_account_snapshot" -> authenticated()
            "provider_favorite_songs" -> {
                oldFavorites.await()
                JSONObject("""{"items":[{"id":"qqmusic:track:1","isFavorite":true}],"authRevision":1}""")
            }
            "provider_set_favorite" -> JSONObject("""{"authRevision":1,"status":"applied","favorite":false}""")
            else -> JSONObject()
        } }
        withModel(fake) { model ->
            main { model.refreshAccount() }
            await { fake.calls.any { it.first == "provider_favorite_songs" } }
            val track = Track(JSONObject(writableTrack().json.toString()).put("isFavorite", true))
            main { model.favorite(track) }
            await { model.favorites.value[track.key] == false }
            oldFavorites.complete(Unit)
            delay(100)
            assertEquals(false, model.favorites.value[track.key])
            val request = fake.calls.single { it.first == "provider_set_favorite" }.second.getJSONObject("request")
            assertFalse(request.getBoolean("favorite"))
        }
    }

    @Test fun playlistPickerFiltersPermissionsAndPaginatesWithoutWriting() = runBlocking {
        val fake = FakeCore { method, params -> when (method) {
            "provider_account_snapshot" -> authenticated()
            "provider_account_playlists" -> if (params.isNull("cursor")) JSONObject("""{"items":[{"id":"read","ownership":"owned","capabilities":{"canAddTracks":false}},{"id":"collected","ownership":"collected","capabilities":{"canAddTracks":true}}],"nextCursor":"next","authRevision":1}""")
                else JSONObject("""{"items":[{"id":"writable","ownership":"owned","capabilities":{"canAddTracks":true}}],"authRevision":1}""")
            else -> JSONObject()
        } }
        withModel(fake) { model ->
            main { model.refreshAccount(); model.openPicker(writableTrack()) }
            await { model.picker.value?.loading == false }
            assertTrue(model.picker.value!!.playlists.isEmpty())
            assertEquals("next", model.picker.value!!.cursor)
            main { model.loadPicker() }
            await { model.picker.value?.playlists?.size == 1 }
            assertEquals("writable", model.picker.value!!.playlists.single().id)
            assertFalse(fake.calls.any { it.first == "provider_add_playlist_track" })
        }
    }

    @Test fun playlistWriteGuardsAndPendingStateUseCapabilities() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val fake = FakeCore { method, params -> when (method) {
            "provider_account_snapshot" -> authenticated()
            "provider_rename_playlist" -> { release.await(); JSONObject().put("status", "applied").put("authRevision", 1).put("playlist", JSONObject().put("id", "p").put("title", params.getJSONObject("request").getString("title"))) }
            else -> JSONObject()
        } }
        withModel(fake) { model ->
            main {
                model.refreshAccount()
                val readOnly = JSONObject("""{"id":"p","capabilities":{"canRename":false}}""")
                model.mutatePlaylist("rename", readOnly, title = "Name")
                model.mutatePlaylist("delete", readOnly)
                val writable = JSONObject("""{"id":"p","capabilities":{"canRename":true}}""")
                model.mutatePlaylist("rename", writable, title = "\n")
                model.mutatePlaylist("rename", writable, title = " New name ")
                model.mutatePlaylist("rename", writable, title = "Second")
            }
            await { fake.calls.any { it.first == "provider_rename_playlist" } }
            assertEquals(setOf("p"), model.pendingPlaylists.value)
            release.complete(Unit)
            await { model.pendingPlaylists.value.isEmpty() }
            assertEquals("New name", model.playlistSummaries.value["p"]!!.getString("title"))
            assertEquals(1, fake.calls.count { it.first == "provider_rename_playlist" })
            assertFalse(fake.calls.any { it.first == "provider_delete_playlist" })
        }
    }

    @Test fun lateFavoriteResultCannotEnterAnotherProviderWithTheSameRevision() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fake = FakeCore { method, params -> when (method) {
            "provider_account_snapshot" -> authenticated().put("providerId", params.text("providerId", "qqmusic"))
            "provider_set_favorite" -> { started.complete(Unit); release.await(); JSONObject("""{"status":"applied","authRevision":1,"favorite":true}""") }
            else -> JSONObject()
        } }
        withModel(fake) { model ->
            val track = writableTrack()
            main { model.refreshAccount(); model.favorite(track) }
            started.await()
            main { model.navigate(NativeRoute("home", providerId = "plugin.provider")) }
            await { model.account.value.providerId == "plugin.provider" && model.account.value.authenticated }
            release.complete(Unit)
            await { model.pendingFavorites.value.isEmpty() }
            assertFalse(model.favorites.value.containsKey(track.key))
            assertEquals("qqmusic", fake.calls.single { it.first == "provider_set_favorite" }.second.text("providerId"))
        }
    }

    private suspend fun withModel(fake: FakeCore, waitForHome: Boolean = true, block: suspend (NativeViewModel) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val model = withContext(Dispatchers.Main) { NativeViewModel(app, fake) }
        try { if (waitForHome) await { model.page.value.loaded }; block(model) }
        finally { withContext(Dispatchers.Main) { model.viewModelScope.cancel(); model.closePicker(); model.stopObserving() } }
    }
    private fun homeFeed(id: String) = JSONObject("""{"madeForYou":[{"id":"$id"}]}""")
    private fun homeCache(id: String?, awaiting: Boolean = false, refreshRequired: Boolean = false) = JSONObject()
        .put("awaitingAccount", awaiting).put("refreshRequired", refreshRequired)
        .put("snapshot", id?.let { JSONObject().put("stale", false).put("feed", homeFeed(it)) } ?: JSONObject.NULL)
    private suspend fun <T> main(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }
    private suspend fun await(condition: () -> Boolean) { withTimeout(5000) { while (!condition()) delay(10) } }
    private class FakeCore(val respond: suspend (String, JSONObject) -> JSONObject) : NativeCoreApi {
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, JSONObject>>()
        override suspend fun invoke(method: String, params: JSONObject, host: Boolean): Any? {
            calls += method to JSONObject(params.toString())
            return respond(method, params)
        }
    }
    private fun writableTrack() = Track(JSONObject("""{"id":"qqmusic:track:1","title":"Test song","provider":{"providerId":"qqmusic","trackId":"1"}}"""))
    private fun authenticated(revision: Long = 1) = JSONObject("""{"state":"authenticated","revision":$revision,"profile":{"nickname":"Test","maskedIdentity":"test"},"capabilities":{"favoriteRead":true,"favoriteWrite":true,"playlistRead":true,"playlistWrite":true}}""")
}
