package org.squiddev.cobalt;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.squiddev.cobalt.compiler.CompileException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

/**
 * Lua driven assertion tests
 */
@RunWith(Parameterized.class)
public class AssertionTest {
	private final String name;
	private ScriptDrivenHelpers helpers;

	public AssertionTest(String name) {
		this.name = name;
		this.helpers = new ScriptDrivenHelpers("/assert/");
	}

	@Parameterized.Parameters(name = "{0}")
	public static Collection<Object[]> getTests() {
		Object[][] tests = {
			{"baseIssues"},
			{"stringIssues"},
			{"debug"},
			{"debug-coroutine-hook"},
			{"gc"}
		};

		return Arrays.asList(tests);
	}

	@Before
	public void setup() {
		helpers.setup();
	}

	@Test
	public void run() throws IOException, CompileException, LuaError {
		helpers.loadScript(name).call(helpers.state);
	}
}
