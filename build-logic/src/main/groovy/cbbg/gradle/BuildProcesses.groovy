package cbbg.gradle

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/** Child builds share core outputs and must not run concurrently. */
abstract class BuildProcesses implements BuildService<BuildServiceParameters.None> {}
