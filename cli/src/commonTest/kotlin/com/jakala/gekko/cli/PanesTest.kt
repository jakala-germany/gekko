package com.jakala.gekko.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val D = "$"

private var counter = 0

/** A project on disk with the given files (paths below the project folder). */
internal fun paneProject(files: Map<String, String>, config: GekkoConfig = GekkoConfig()): Project {
    val root = "/tmp/gekko-panes-test-${nowMillis()}-${counter++}"
    files.forEach { (name, text) ->
        makeDirs("$root/${name.substringBeforeLast('/')}")
        writeTextFile("$root/$name", text.trimIndent())
    }
    return Project(root, config)
}

internal val LOGIN_PANE =
    """
    identity: [title, email]
    elements:
      title: { id: Login.Title }
      email: { id: Login.Email }
      submit:
        android: { id: login_submit }
        ios: { text: "Sign in" }
      dialog: { id: Login.Dialog }
      dialog.ok: { text: OK, within: dialog }
      row: { id: Login.Row, index: 1 }
    actions:
      signIn:
        params: [user, password]
        steps:
          - say: Enter the user
            do: { type: { element: email, value: "${D}{user}" } }
          - say: Enter the password
            do: { type: { id: Login.Password, value: "${D}{password}" } }
          - say: Submit
            do: { tap: submit }
    """

class PanesTest {
    private fun step(yaml: String): Step {
        return Yamls.parse(Step.serializer(), yaml.trimIndent())
    }

    private fun expand(project: Project, platform: Platform, yaml: String, scope: String? = null): Step {
        return PaneExpander(project.panes, platform, scope).step(step(yaml))
    }

    @Test
    fun aPlainStringIsAnElementAndAMapCanAddKeys() {
        val tap = step("do: { tap: auth/login.submit }").action.tap!!
        val indexed = step("do: { tap: { element: cart.row, index: 2 } }").action.tap!!
        val typed = step("do: { type: { element: login.email, value: x } }").action.type!!
        val longPress = step("do: { longPress: login.row }").action.longPress!!
        val scroll = step("do: { scroll: list.main }").action.scroll!!

        assertEquals("auth/login.submit", tap.element)
        assertEquals("cart.row", indexed.element)
        assertEquals(2, indexed.index)
        assertEquals("login.email", typed.element)
        assertEquals("login.row", longPress.element)
        assertEquals("list.main", scroll.element)
    }

    @Test
    fun aPlainStringWorksForEveryActionThatTakesASelectorAndInTheConditions() {
        val parsed =
            step(
                """
                do: { assertVisible: login.title }
                if: { notVisible: banner.close }
                until: { assertNotVisible: login.spinner }
                """
            )

        assertEquals("login.title", parsed.action.assertVisible!!.element)
        assertEquals("banner.close", parsed.condition!!.notVisible!!.element)
        assertEquals("login.spinner", parsed.until!!.assertNotVisible!!.element)
    }

    @Test
    fun aSelectorKeyThatDoesNotExistStillHasAHint() {
        val failure = assertFailsWith<IllegalArgumentException> { step("do: { tap: { elemnt: login.submit } }") }

        assertTrue(failure.message!!.contains("did you mean 'element'"), failure.message)
    }

    @Test
    fun typeNeedsItsValueAndATapNeedsSomethingToLookFor() {
        assertFailsWith<Exception> { step("do: { type: login.email }") }
        assertFailsWith<IllegalArgumentException> { step("do: { tap: { index: 1 } }") }
    }

    @Test
    fun assertPaneAndAPaneConditionAreParsed() {
        val parsed = step("do: { assertPane: auth/login }\nif: { pane: onboarding }")

        assertEquals("auth/login", parsed.action.assertPane)
        assertEquals("onboarding", parsed.condition!!.pane)
        assertFailsWith<IllegalArgumentException> { step("do: { tap: x.y }\nif: { pane: a, visible: x.y }") }
    }

    @Test
    fun aPaneFileHasElementsThatAreOneSelectorOrOnePerPlatform() {
        val pane = Yamls.parse(PaneFile.serializer(), LOGIN_PANE.trimIndent())

        assertEquals("Login.Email", pane.elements.getValue("email").resolve(Platform.Web)!!.id!!.shared)
        assertEquals("login_submit", pane.elements.getValue("submit").resolve(Platform.Android)!!.id!!.shared)
        assertEquals("Sign in", pane.elements.getValue("submit").resolve(Platform.Ios)!!.text)
        assertNull(pane.elements.getValue("submit").resolve(Platform.Web))
        assertEquals(listOf("title", "email"), pane.identity)
        assertEquals(listOf("user", "password"), pane.actions.getValue("signIn").params)
    }

    @Test
    fun aPaneFileIsStrict() {
        fun rejection(yaml: String): String {
            return assertFailsWith<Exception> { Yamls.parse(PaneFile.serializer(), yaml.trimIndent()) }
                .message
                .orEmpty()
        }

        assertTrue(rejection("elements:\n  a: { id: x, text: y, idd: z }").contains("Unknown key 'idd'"))
        assertTrue(rejection("elements:\n  a: { android: { id: x }, text: y }").contains("not both"))
        assertTrue(rejection("elements:\n  a: { android: { id: x, element: b.c } }").contains("another element"))
        assertTrue(rejection("elements:\n  a: { point: \"10%,10%\" }").contains("point"))
        assertTrue(rejection("elements:\n  a: { index: 1 }").contains("a selector needs"))
        assertTrue(rejection("identity: [missing]").contains("not an element"))
        assertTrue(rejection("elements:\n  'a..b': { id: x }").contains("element name"))
        assertTrue(rejection("actions:\n  go: { steps: [] }").contains("at least one step"))
    }

    @Test
    fun aPaneIsNamedByItsPathAlsoWhenTheProjectFolderIsWrittenWithADotOrATrailingSlash() {
        val project = paneProject(mapOf("panes/auth/login.yaml" to LOGIN_PANE))

        for (root in listOf("${project.root}/.", "${project.root}/", "${project.root}//")) {
            val panes = Project(root, GekkoConfig()).panes

            assertEquals(listOf("auth/login"), panes.names, root)
            assertTrue(panes.pane("auth/login").elements.containsKey("email"), root)
        }
    }

    @Test
    fun theNameOfAPaneIsItsPathAndAnElementMayHaveDots() {
        val project =
            paneProject(
                mapOf(
                    "panes/auth/login.yaml" to LOGIN_PANE,
                    "panes/checkout/payment.yaml" to "elements:\n  card.number: { id: Pay.Card }",
                    "panes/home.yml" to "elements:\n  title: { id: Home.Title }",
                )
            )

        assertEquals(listOf("auth/login", "checkout/payment", "home"), project.panes.names)
        assertEquals(("checkout/payment" to "card.number"), project.panes.splitRef("checkout/payment.card.number"))
        assertEquals(("home" to "title"), project.panes.splitRef("home.title"))
        assertNull(project.panes.splitRef("title"))
        assertNull(project.panes.splitRef("a/b"))
        assertEquals(
            ElementRef("checkout/payment", "card.number"),
            project.panes.resolveElement("checkout/payment.card.number", null),
        )
    }

    @Test
    fun anElementOfThePaneItselfWinsAndAnAmbiguousNameIsAnError() {
        val project =
            paneProject(
                mapOf(
                    "panes/search.yaml" to
                        "elements:\n  filter.apply: { id: Search.Apply }\n  field: { id: Search.Field }",
                    "panes/filter.yaml" to "elements:\n  apply: { id: Filter.Apply }",
                    "panes/other.yaml" to "elements:\n  field: { id: Other.Field }",
                )
            )

        assertEquals(ElementRef("search", "field"), project.panes.resolveElement("field", "search"))
        assertEquals(ElementRef("other", "field"), project.panes.resolveElement("other.field", "search"))
        assertEquals(ElementRef("filter", "apply"), project.panes.resolveElement("filter.apply", null))
        val failure = assertFailsWith<IllegalStateException> { project.panes.resolveElement("filter.apply", "search") }
        assertTrue(failure.message!!.contains("ambiguous"), failure.message)
        assertTrue(failure.message!!.contains("search.filter.apply"), failure.message)
    }

    @Test
    fun anUnknownPaneOrElementSaysWhatItIsAndWhatYouMeant() {
        val project = paneProject(mapOf("panes/auth/login.yaml" to LOGIN_PANE))

        fun message(reference: String, scope: String? = null): String {
            return assertFailsWith<IllegalArgumentException> { project.panes.resolveElement(reference, scope) }
                .message
                .orEmpty()
        }

        assertTrue(message("auth/logn.submit").contains("Did you mean 'auth/login'"), message("auth/logn.submit"))
        assertTrue(message("auth/login.submitt").contains("Did you mean 'auth/login.submit'"))
        assertTrue(message("submitt", "auth/login").contains("Did you mean 'submit'"))
        assertTrue(message("submit").contains("<pane>.<element>"))
    }

    @Test
    fun aReferenceBecomesTheSelectorOfThePlatform() {
        val project = paneProject(mapOf("panes/auth/login.yaml" to LOGIN_PANE))

        val android = expand(project, Platform.Android, "do: { tap: auth/login.submit }").action.tap!!
        val ios = expand(project, Platform.Ios, "do: { tap: auth/login.submit }").action.tap!!

        assertEquals("login_submit", android.toSelector(Platform.Android).id)
        assertNull(android.element)
        assertEquals("Sign in", ios.toSelector(Platform.Ios).text)
        assertNull(ios.toSelector(Platform.Ios).id)
    }

    @Test
    fun theFocusActionsTakeReferencesToo() {
        val project = paneProject(mapOf("panes/auth/login.yaml" to LOGIN_PANE))

        val focused = expand(project, Platform.Android, "do: { assertFocused: auth/login.submit }")
        val pressed = expand(project, Platform.Ios, "do: { pressKey: { key: down, until: auth/login.submit, max: 4 } }")

        assertEquals("login_submit", focused.action.assertFocused!!.toSelector(Platform.Android).id)
        assertEquals("Sign in", pressed.action.pressKey!!.until!!.toSelector(Platform.Ios).text)
        assertEquals(4, pressed.action.pressKey!!.max)
        assertEquals(
            listOf("auth/login.submit"),
            step("do: { assertFocused: auth/login.submit }").action.elementReferences(),
        )
        assertEquals(
            listOf("auth/login.submit"),
            step("do: { pressKey: { key: down, until: auth/login.submit } }").action.elementReferences(),
        )
    }

    @Test
    fun theKeysOfTheStepAreAddedAndWinOverTheElement() {
        val project = paneProject(mapOf("panes/auth/login.yaml" to LOGIN_PANE))

        val own = expand(project, Platform.Android, "do: { assertVisible: auth/login.row }").action.assertVisible!!
        val indexed =
            expand(project, Platform.Android, "do: { assertVisible: { element: auth/login.row, index: 3 } }")
                .action
                .assertVisible!!
        val texted =
            expand(project, Platform.Android, "do: { assertVisible: { element: auth/login.title, text: Hi } }")
                .action
                .assertVisible!!

        assertEquals(1, own.toSelector(Platform.Android).index)
        assertEquals(3, indexed.toSelector(Platform.Android).index)
        assertEquals("Login.Title", texted.toSelector(Platform.Android).id)
        assertEquals("Hi", texted.toSelector(Platform.Android).text)
    }

    @Test
    fun anElementCannotBeCombinedWithAnIdOfTheStep() {
        val project = paneProject(mapOf("panes/auth/login.yaml" to LOGIN_PANE))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                expand(project, Platform.Android, "do: { tap: { element: auth/login.title, id: Other } }")
            }

        assertTrue(failure.message!!.contains("takes no id of its own"), failure.message)
    }

    @Test
    fun aTypeStepKeepsItsValueAndATypeIntoASystemElementIsRefused() {
        val project =
            paneProject(
                mapOf(
                    "panes/auth/login.yaml" to LOGIN_PANE,
                    "panes/permission.yaml" to "elements:\n  allow: { text: Allow, system: true }",
                )
            )

        val typed =
            expand(project, Platform.Ios, "do: { type: { element: auth/login.email, value: Ada } }").action.type!!
        val tapped = expand(project, Platform.Ios, "do: { tap: permission.allow }").action.tap!!

        assertEquals("Ada", typed.value)
        assertEquals("Login.Email", typed.selector(Platform.Ios).id)
        assertTrue(tapped.system)
        assertFailsWith<IllegalArgumentException> {
            expand(project, Platform.Ios, "do: { type: { element: permission.allow, value: x } }")
        }
    }

    @Test
    fun anElementThatIsWithinAnotherOneUsesItsId() {
        val project = paneProject(mapOf("panes/auth/login.yaml" to LOGIN_PANE))

        val spec = expand(project, Platform.Android, "do: { tap: auth/login.dialog.ok }").action.tap!!

        assertEquals("OK", spec.toSelector(Platform.Android).text)
        assertEquals("Login.Dialog", spec.toSelector(Platform.Android).within)
    }

    @Test
    fun aWithinThatIsNotAnElementStaysAnId() {
        val project = paneProject(mapOf("panes/p.yaml" to "elements:\n  a: { text: OK, within: Some.Dialog }"))

        val spec = expand(project, Platform.Android, "do: { tap: p.a }").action.tap!!

        assertEquals("Some.Dialog", spec.toSelector(Platform.Android).within)
    }

    @Test
    fun aWithinElementThatIsMoreThanAnIdIsAnError() {
        val project =
            paneProject(
                mapOf("panes/p.yaml" to "elements:\n  box: { id: B, index: 1 }\n  a: { text: OK, within: box }")
            )

        val failure =
            assertFailsWith<IllegalArgumentException> { expand(project, Platform.Android, "do: { tap: p.a }") }

        assertTrue(failure.message!!.contains("only an id"), failure.message)
    }

    @Test
    fun anElementWithoutASelectorForAPlatformNeedsAPlatformOnTheStep() {
        val project = paneProject(mapOf("panes/auth/login.yaml" to LOGIN_PANE))
        val steps = listOf(step("do: { tap: auth/login.submit }"))
        val limited = listOf(step("do: { tap: auth/login.submit }\nplatform: [android, ios]"))

        val failure =
            assertFailsWith<IllegalStateException> {
                project.panes.validate(steps, null, listOf(Platform.Android, Platform.Ios, Platform.Web))
            }
        project.panes.validate(limited, null, listOf(Platform.Android, Platform.Ios, Platform.Web))

        assertTrue(failure.message!!.contains("no selector for web"), failure.message)
        assertTrue(failure.message!!.contains("auth/login.submit"), failure.message)
    }

    @Test
    fun theIdentityIsAllItsElementsAndAPaneWithoutOneCannotBeAsserted() {
        val project =
            paneProject(mapOf("panes/auth/login.yaml" to LOGIN_PANE, "panes/plain.yaml" to "elements:\n  a: { id: A }"))

        val selectors = project.panes.identity("auth/login", Platform.Ios)

        assertEquals(listOf("Login.Title", "Login.Email"), selectors.map { it.id })
        val failure = assertFailsWith<IllegalArgumentException> { project.panes.identity("plain", Platform.Ios) }
        assertTrue(failure.message!!.contains("no identity"), failure.message)
    }

    @Test
    fun anActionGetsItsParamsAndNothingElse() {
        val project = paneProject(mapOf("panes/auth/login.yaml" to LOGIN_PANE))

        val steps = project.panes.action("auth/login", "signIn", mapOf("user" to "ada", "password" to "p\"w"))

        assertEquals("ada", steps[0].action.type!!.value)
        assertEquals("p\"w", steps[1].action.type!!.value)
        val missing =
            assertFailsWith<IllegalArgumentException> {
                project.panes.action("auth/login", "signIn", mapOf("user" to "a"))
            }
        assertTrue(missing.message!!.contains("Missing parameter 'password'"), missing.message)
        val extra =
            assertFailsWith<IllegalArgumentException> {
                project.panes.action("auth/login", "signIn", mapOf("user" to "a", "password" to "b", "x" to "c"))
            }
        assertTrue(extra.message!!.contains("'x' is not a param"), extra.message)
    }

    @Test
    fun aParameterThatIsNotDeclaredBreaksThePane() {
        val project =
            paneProject(
                mapOf(
                    "panes/a.yaml" to
                        """
                        elements:
                          x: { id: X }
                        actions:
                          go:
                            params: [one]
                            steps:
                              - do: { type: { id: X, value: "${D}{two}" } }
                        """,
                    "panes/b.yaml" to "elements:\n  x: { text: \"${D}{one}\" }",
                )
            )

        val problems = project.panes.problems()

        assertEquals(2, problems.size, problems.toString())
        assertTrue(problems.any { it.contains("'${D}{two}' in action 'go'") }, problems.toString())
        assertTrue(problems.any { it.contains("only actions take parameters") }, problems.toString())
    }

    @Test
    fun runtimeVariablesAreLeftToTheStep() {
        val project =
            paneProject(
                mapOf(
                    "panes/a.yaml" to
                        """
                        elements:
                          total: { text: "${D}{var.total}" }
                        """
                )
            )

        assertEquals(emptyList(), project.panes.problems())
        assertEquals(
            "${D}{var.total}",
            expand(project, Platform.Ios, "do: { assertVisible: a.total }").action.assertVisible!!.text,
        )
    }

    @Test
    fun useFindsAFlowOrAnActionOfAPane() {
        val project =
            paneProject(
                mapOf(
                    "panes/auth/login.yaml" to LOGIN_PANE,
                    "flows/greet.yaml" to "name: greet\nsteps:\n  - do: { tap: { id: Greet } }",
                )
            )

        val flow = project.loadCallable("greet", emptyMap())
        val action = project.loadCallable("auth/login.signIn", mapOf("user" to "a", "password" to "b"))
        val own = project.loadCallable("signIn", mapOf("user" to "a", "password" to "b"), "auth/login")

        assertNull(flow.pane)
        assertEquals("auth/login", action.pane)
        assertEquals("auth/login.signIn", own.name)
        assertEquals(3, action.steps.size)
    }

    @Test
    fun aNameThatIsAFlowAndAnActionIsAmbiguous() {
        val project =
            paneProject(
                mapOf(
                    "panes/auth/login.yaml" to LOGIN_PANE,
                    "flows/auth/login.signIn.yaml" to "name: x\nsteps:\n  - do: { tap: { id: X } }",
                )
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                project.loadCallable("auth/login.signIn", mapOf("user" to "a", "password" to "b"))
            }

        assertTrue(failure.message!!.contains("ambiguous"), failure.message)
    }

    @Test
    fun aMissingFlowSaysWhenItLookedLikeAPaneAction() {
        val project = paneProject(mapOf("panes/auth/login.yaml" to LOGIN_PANE))

        val failure = assertFailsWith<StepFailure> { project.loadCallable("auth/login.signn", emptyMap()) }

        assertTrue(failure.message!!.startsWith("Flow 'auth/login.signn' not found in flows/"), failure.message)
        assertTrue(failure.message!!.contains("Did you mean 'auth/login.signIn'"), failure.message)
    }

    @Test
    fun currentListsThePanesWhoseIdentityIsOnScreen() {
        val project =
            paneProject(
                mapOf(
                    "panes/auth/login.yaml" to LOGIN_PANE,
                    "panes/home.yaml" to "identity: [t]\nelements:\n  t: { id: Home.Title }",
                )
            )
        val bounds = com.jakala.gekko.protocol.Bounds(0, 0, 100, 200)
        fun node(id: String, top: Int): com.jakala.gekko.protocol.UiNode {
            return com.jakala.gekko.protocol.UiNode(
                id = id,
                bounds = com.jakala.gekko.protocol.Bounds(0, top, 100, top + 10),
            )
        }
        val screen =
            listOf(
                com.jakala.gekko.protocol.UiNode(
                    bounds = bounds,
                    children = listOf(node("Login.Title", 0), node("Login.Email", 20)),
                )
            )

        assertEquals(listOf("auth/login"), project.panes.current(screen, Platform.Android))
    }

    @Test
    fun checkReportsBrokenReferencesPanesAndUnusedThings() {
        val project =
            paneProject(
                mapOf(
                    "panes/auth/login.yaml" to LOGIN_PANE,
                    "panes/extra.yaml" to
                        "elements:\n  lonely: { id: L }\nactions:\n  idle:\n    steps:\n      - do: { tap: lonely }",
                    "panes/broken.yaml" to "elements:\n  a: { idd: x }",
                    "tests/ok.yaml" to
                        """
                        name: Ok
                        steps:
                          - say: Sign in
                            do: { use: auth/login.signIn, with: { user: a, password: b } }
                          - say: There
                            do: { assertPane: auth/login }
                          - say: Dialog
                            do: { tap: auth/login.dialog.ok }
                        """,
                    "tests/bad.yaml" to
                        """
                        name: Bad
                        steps:
                          - say: Typo
                            do: { tap: auth/login.submitt }
                        """,
                    "tests/bad-action.yaml" to
                        """
                        name: Bad action
                        steps:
                          - say: No such action
                            do: { use: auth/login.signOut }
                        """,
                )
            )

        val report = Checker.report(project, emptyList())

        assertTrue(
            report.problems.any { it.contains("tests/bad.yaml") && it.contains("Did you mean 'auth/login.submit'") },
            report.problems.toString(),
        )
        assertTrue(report.problems.any { it.contains("pane action 'auth/login.signOut'") }, report.problems.toString())
        assertTrue(
            report.problems.any { it.contains("panes/broken.yaml") && it.contains("Unknown key 'idd'") },
            report.problems.toString(),
        )
        assertTrue(report.problems.none { it.contains("tests/ok.yaml") }, report.problems.toString())
        assertTrue(report.warnings.any { it.contains("pane 'extra' is not used") }, report.warnings.toString())
        assertTrue(report.warnings.any { it.contains("action 'idle' is not used") }, report.warnings.toString())
        assertTrue(report.warnings.any { it.contains("element 'lonely' is not used") }, report.warnings.toString())
        assertTrue(report.warnings.any { it.contains("element 'row' is not used") }, report.warnings.toString())
        // used by a step, as the identity, as the within of an element, and inside an action that is called
        assertTrue(
            report.warnings.none { it.contains("element 'title'") || it.contains("element 'dialog'") },
            report.warnings.toString(),
        )
        assertTrue(
            report.warnings.none { it.contains("element 'submit'") || it.contains("element 'email'") },
            report.warnings.toString(),
        )
    }

    @Test
    fun nothingIsReportedAsUnusedWhenOnlySomeTestsAreChecked() {
        val project =
            paneProject(
                mapOf(
                    "panes/auth/login.yaml" to LOGIN_PANE,
                    "tests/t.yaml" to "name: T\nsteps:\n  - do: { assertPane: auth/login }",
                )
            )

        val report = Checker.report(project, listOf("${project.root}/tests/t.yaml"))

        assertEquals(emptyList(), report.problems)
        assertEquals(emptyList(), report.warnings)
    }

    @Test
    fun anElementOfAPlatformThatTheProjectDoesNotTestIsNotAnError() {
        val project =
            paneProject(
                mapOf(
                    "panes/auth/login.yaml" to LOGIN_PANE,
                    "tests/t.yaml" to "name: T\nsteps:\n  - do: { tap: auth/login.submit }",
                )
            )
        val web =
            paneProject(
                mapOf(
                    "panes/auth/login.yaml" to LOGIN_PANE,
                    "tests/t.yaml" to "name: T\nsteps:\n  - do: { tap: auth/login.submit }",
                ),
                GekkoConfig(app = AppIds(web = "https://example.com")),
            )

        assertEquals(emptyList(), Checker.check(project, emptyList()))
        assertTrue(Checker.check(web, emptyList()).any { it.contains("no selector for web") })
    }

    @Test
    fun aGroupSetupAndUsesCanNameAnActionWithoutParams() {
        val project =
            paneProject(
                mapOf(
                    "panes/home.yaml" to
                        "elements:\n  menu: { id: Home.Menu }\nactions:\n  open:\n    steps:\n      - do: { tap: menu }",
                    "tests/group/group.yaml" to "setup: [home.open]",
                    "tests/group/t.yaml" to "name: T\nuses: [home.open]\nsteps:\n  - do: { tap: { id: X } }",
                )
            )

        val report = Checker.report(project, emptyList())

        assertEquals(emptyList(), report.problems)
        assertEquals(emptyList(), report.warnings)
    }

    @Test
    fun aPaneCanRefuseToBeCalledWithTheWrongParams() {
        val project =
            paneProject(
                mapOf(
                    "panes/auth/login.yaml" to LOGIN_PANE,
                    "tests/t.yaml" to "name: T\nsteps:\n  - do: { use: auth/login.signIn, with: { user: a } }",
                )
            )

        val problems = Checker.check(project, emptyList())

        assertTrue(problems.any { it.contains("Missing parameter 'password'") }, problems.toString())
    }
}
