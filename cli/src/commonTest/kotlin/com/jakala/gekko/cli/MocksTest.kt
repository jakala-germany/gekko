package com.jakala.gekko.cli

import com.jakala.gekko.protocol.MockHit
import com.jakala.gekko.protocol.MockMatcher
import com.jakala.gekko.protocol.MockRules
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MocksTest {
    private fun spec(
        url: String,
        file: String? = null,
        method: String? = null,
        bodyContains: String? = null,
    ): MockSpec {
        return MockSpec(url = url, file = file, method = method, bodyContains = bodyContains)
    }

    @Test
    fun aGlobStandsForAnyTextAndNothingElse() {
        val regex = Regex(MockRules.globToRegex("https://api.example.com/v1/routes*"))

        assertTrue(regex.matches("https://api.example.com/v1/routes"))
        assertTrue(regex.matches("https://api.example.com/v1/routes?from=A&to=B"))
        assertTrue(regex.matches("https://api.example.com/v1/routes/42/stops"))
        assertFalse(regex.matches("https://api.example.com/v1/route"))
        assertFalse(regex.matches("http://api.example.com/v1/routes"))
        // The dots of the host are dots, not any character
        assertFalse(regex.matches("https://apiXexample.com/v1/routes"))
    }

    @Test
    fun theSpecialCharactersOfAUrlAreLiteral() {
        val regex = Regex(MockRules.globToRegex("https://a.example.com/s?q=(a+b)[1]{2}|^\$"))

        assertTrue(regex.matches("https://a.example.com/s?q=(a+b)[1]{2}|^\$"))
        assertFalse(regex.matches("https://a.example.com/sq=(a+b)[1]{2}|^\$"))
    }

    @Test
    fun aGlobWithTheQueryMatchesTheQuery() {
        val regex = Regex(MockRules.globToRegex("https://api.example.com/v1/routes?*to=Airport*"))

        assertTrue(regex.matches("https://api.example.com/v1/routes?from=A&to=Airport"))
        assertTrue(regex.matches("https://api.example.com/v1/routes?to=Airport&from=A"))
        assertFalse(regex.matches("https://api.example.com/v1/routes?to=Station"))
    }

    @Test
    fun theFileDecidesTheContentTypeUnlessTheMockSetsOne() {
        val mocks = MockSet()
        mocks.add(spec("https://a/*", file = "routes.json"), "{}")
        mocks.add(
            MockSpec(url = "https://b/*", file = "page.html", headers = mapOf("content-type" to "text/x-custom")),
            "<p/>",
        )
        mocks.add(spec("https://c/*"), "")

        val (json, html, none) = mocks.rules()

        assertEquals(mapOf("Content-Type" to "application/json"), json.headers)
        assertEquals(mapOf("content-type" to "text/x-custom"), html.headers)
        assertTrue(none.headers.isEmpty())
    }

    @Test
    fun theBodyTravelsAsBase64() {
        val mocks = MockSet()

        mocks.add(spec("https://a/*", file = "a.json"), """{"name":"Zürich"}""")

        assertEquals("""{"name":"Zürich"}""", Base64.decode(mocks.rules().single().bodyBase64).decodeToString())
    }

    @Test
    fun theLastMockThatMatchesWins() {
        val mocks = MockSet()
        mocks.add(spec("https://api/*", file = "all.json"), "all")
        mocks.add(spec("https://api/routes*", file = "routes.json"), "routes")

        val matcher = MockMatcher(mocks.rules())

        val routes = matcher.match("GET", "https://api/routes?x=1") { null }
        val other = matcher.match("GET", "https://api/other") { null }
        assertEquals("routes", Base64.decode(routes!!.bodyBase64).decodeToString())
        assertEquals("all", Base64.decode(other!!.bodyBase64).decodeToString())
    }

    @Test
    fun theSameMockSetAgainReplacesTheEarlierOne() {
        val mocks = MockSet()
        mocks.add(spec("https://api/routes*", file = "day.json"), "day")

        mocks.add(spec("https://api/routes*", file = "night.json"), "night")

        assertEquals(1, mocks.rules().size)
        assertEquals("night", Base64.decode(mocks.rules().single().bodyBase64).decodeToString())
        // The mock that was replaced before anyone used it is not "unused": it was changed
        assertEquals(listOf("https://api/routes* → night.json"), mocks.unused())
    }

    @Test
    fun theSameUrlWithAnotherMethodOrBodyFilterIsAnotherMock() {
        val mocks = MockSet()
        mocks.add(spec("https://api/*", file = "a.json", method = "GET"), "a")
        mocks.add(spec("https://api/*", file = "b.json", method = "POST"), "b")
        mocks.add(spec("https://api/*", file = "c.json", method = "POST", bodyContains = "x"), "c")

        assertEquals(3, mocks.rules().size)
    }

    @Test
    fun aMockThatWasReplacedAfterItWasUsedKeepsItsHits() {
        val mocks = MockSet()
        val first = mocks.add(spec("https://api/routes*", file = "day.json"), "day")
        mocks.record(listOf(MockHit(first.rule.id, "GET", "https://api/routes")))

        mocks.add(spec("https://api/routes*", file = "night.json"), "night")

        assertEquals(listOf("https://api/routes* → night.json"), mocks.unused())
    }

    @Test
    fun aRequestThatAReplacedMockAnsweredCountsForTheMockThatReplacedIt() {
        val mocks = MockSet()
        // A mock at the start of a test is set before the app starts and again by its step
        val first = mocks.add(spec("https://api/*", file = "a.json"), "a")
        mocks.add(spec("https://api/*", file = "a.json"), "a")

        val lines = mocks.record(listOf(MockHit(first.rule.id, "GET", "https://api/x")))

        assertEquals(listOf("mocked GET https://api/x → a.json"), lines)
        assertTrue(mocks.unused().isEmpty())
    }

    @Test
    fun hitsAreCountedPerMockAndLogged() {
        val mocks = MockSet()
        val used = mocks.add(spec("https://api/routes*", file = "routes.json"), "x")
        mocks.add(spec("https://api/profile*", file = "profile.json", method = "GET"), "y")

        val lines = mocks.record(listOf(MockHit(used.rule.id, "GET", "https://api/routes?from=A")))

        assertEquals(listOf("mocked GET https://api/routes?from=A → routes.json"), lines)
        assertEquals(listOf("GET https://api/profile* → profile.json"), mocks.unused())
    }

    @Test
    fun aMockWithoutAFileIsDescribedByItsStatus() {
        val mocks = MockSet()
        mocks.add(MockSpec(url = "https://api/*", status = 503), "")

        assertEquals(listOf("https://api/* → status 503"), mocks.unused())
    }

    @Test
    fun clearingTheMocksKeepsWhatWasSetForTheReport() {
        val mocks = MockSet()
        mocks.add(spec("https://api/*", file = "a.json"), "a")

        mocks.clear()

        assertFalse(mocks.isActive)
        assertTrue(mocks.hasBeenSet)
        assertEquals(1, mocks.unused().size)
    }

    @Test
    fun resettingForgetsEverything() {
        val mocks = MockSet()
        mocks.add(spec("https://api/*", file = "a.json"), "a")

        mocks.reset()

        assertFalse(mocks.hasBeenSet)
        assertTrue(mocks.unused().isEmpty())
        assertEquals("m1", mocks.add(spec("https://api/*"), "").rule.id)
    }

    @Test
    fun mocksThatAreTooBigTogetherAreRefused() {
        val mocks = MockSet()
        val big = "x".repeat(1_500_000)
        mocks.add(spec("https://a/*", file = "a.json"), big)

        assertFailsWith<StepFailure> { mocks.add(spec("https://b/*", file = "b.json"), big) }
        assertEquals(1, mocks.rules().size)
    }

    @Test
    fun aReplacementThatIsTooBigLeavesTheSetAsTheAppHasIt() {
        val mocks = MockSet()
        mocks.add(spec("https://a/*", file = "a.json"), "small")
        mocks.add(spec("https://b/*", file = "b.json"), "x".repeat(1_000_000))

        assertFailsWith<StepFailure> { mocks.add(spec("https://a/*", file = "a.json"), "x".repeat(2_000_000)) }

        assertEquals(listOf("m1", "m2"), mocks.rules().map { it.id })
        assertEquals(listOf("https://a/* → a.json", "https://b/* → b.json"), mocks.unused())
    }

    @Test
    fun aValidHeaderIsAccepted() {
        val mock = MockSpec(url = "https://a/*", headers = mapOf("Retry-After" to "30", "X_Custom.1" to "a \"b\"\tc"))

        assertEquals(2, mock.headers.size)
    }

    @Test
    fun valuesAreEscapedForAJsonString() {
        assertEquals("a\\\"b\\\\c\\nd\\te", jsonEscape("a\"b\\c\nd\te"))
        assertEquals("\\u0001", jsonEscape("\u0001"))
        assertEquals("Zürich ✓", jsonEscape("Zürich ✓"))
    }

    @Test
    fun matchingLooksAtTheBodyOnlyWhenARuleWantsIt() {
        val mocks = MockSet()
        mocks.add(spec("https://api/graphql", file = "a.json", method = "POST", bodyContains = "SearchRoutes"), "a")
        val matcher = MockMatcher(mocks.rules())
        var asked = 0

        val miss = matcher.match("POST", "https://api/other") { asked++.let { null } }
        val hit =
            matcher.match("POST", "https://api/graphql") { """{"operationName":"SearchRoutes"}""".also { asked++ } }
        val wrongBody = matcher.match("POST", "https://api/graphql") { "{}" }

        assertNull(miss)
        assertTrue(hit != null)
        assertNull(wrongBody)
        assertEquals(1, asked)
        assertTrue(matcher.needsBody("POST", "https://api/graphql"))
        assertFalse(matcher.needsBody("GET", "https://api/graphql"))
        assertTrue(matcher.matchesUrl("https://api/graphql"))
        assertFalse(matcher.matchesUrl("https://api/other"))
    }
}
