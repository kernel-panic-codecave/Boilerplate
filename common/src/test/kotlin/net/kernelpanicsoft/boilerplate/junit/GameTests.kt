package net.kernelpanicsoft.boilerplate.junit

import net.kernelpanicsoft.archie.events.gametest.AGametestEvents
import net.kernelpanicsoft.archie.gametest.junit.GameTestRunner
import net.kernelpanicsoft.boilerplate.gametest.boilerplateGameTests
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Bridges the real GameTest suite ([net.kernelpanicsoft.boilerplate.gametest.BoilerplateGameTest])
 * into a plain JUnit 5 run (`./gradlew :boilerplate-common:test`, or an IDE test runner) via
 * [GameTestRunner] - each `loader:side` in the configured matrix launches that loader's own
 * `runGametest`/`runGametestClient` Gradle task and reports every declared `@GameTest`/
 * `@ClientGameTest` method as its own JUnit [org.junit.jupiter.api.DynamicTest], parsed from the
 * launched process's log output, rather than running any of it in this JVM. Disabled by default -
 * see [GameTestRunner.PROP_ENABLED] and `common/build.gradle.kts`'s own `test { }` block for the
 * opt-in system properties.
 */
@Execution(ExecutionMode.CONCURRENT)
class GameTests {
	@TestFactory
	fun tests(): Collection<DynamicContainer> =
		GameTestRunner.tests("boilerplate", "boilerplate", AGametestEvents.ArchieGameTestBuilder::boilerplateGameTests)
}
